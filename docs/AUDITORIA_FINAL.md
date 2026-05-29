# Auditoria Final — consuma_catraca

**Data:** 2026-05-28
**Branch:** main (mudanças locais não commitadas + 1 PR mergeado)
**Tipo:** auditoria read-only (sem alteração de código)

---

## Nota final de prontidão: **7.0 / 10**

> Sistema está **arquiteturalmente sólido** e com **build/testes verdes**, mas há **bloqueadores reais de produção** ligados a segredos versionáveis, seed admin permanentemente habilitado, ausência de seed da área "Portaria" e divergência de senhas fracas. Não é "não pronto" — é **quase pronto, condicionado a um sprint de hardening de 1–2 dias**.

---

## O que está funcionando

- **Build do backend**: `./mvnw clean package -DskipTests` → BUILD SUCCESS.
- **Testes unitários do backend**: 133 testes, **0 falhas, 0 erros**; 18 skipped (Testcontainers/Docker daemon não rodando localmente — esperado).
- **Build do frontend Next.js**: `npm run build` → "Compiled successfully in 2.8s", todas as rotas geradas.
- **docker compose -f docker-compose.prod.yml --env-file .env.production config**: válido, rede `internal`/`public` corretas, volumes nomeados, healthchecks em todos serviços.
- **Cadastro público (`/`) e interno (`/guests`)** chamam o mesmo caminho lógico (`completedVisitorRegistration`) com defaults distintos para `visitReason`/`hostName` e `createInvite=true|false`. Regra unificada após o commit recente em [GuestService.java](src/main/java/br/com/sport/accesscontrol/guests/GuestService.java).
- **Regra de áreas**: `LoungeAreaResolver.resolveForLounge()` retorna **Portaria + área do camarote**, e `resolveAllForEmployee()` devolve **TODAS as áreas ativas** (Acesso Total). [LoungeAreaResolver.java:33-58](src/main/java/br/com/sport/accesscontrol/areas/LoungeAreaResolver.java#L33-L58).
- **Sync seletivo por área**: `IntelbrasRealProvider.syncPerson` itera por **todas** as conexões das áreas permitidas e devolve `PARTIAL_SUCCESS` se uma parte funcionar. [IntelbrasRealProvider.java:64-82](src/main/java/br/com/sport/accesscontrol/integration/intelbras/provider/IntelbrasRealProvider.java#L64-L82).
- **Cooldown 15s**: `AccessCooldownService` (in-memory, ConcurrentHashMap) bloqueia `recordImported` com flag de auditoria e **lança em `manualRelease`**. Chave: CPF > personId > externalUserId > rawCardName + deviceId. [AccessEventService.java:154-199, 276-283](src/main/java/br/com/sport/accesscontrol/events/AccessEventService.java#L154-L199).
- **RBAC do frontend**: `navigation.ts` filtra menu por papel; `AdminShell` redireciona para `/login` se não houver token.
- **Nginx em dois servers**: server público restringe rotas a `/`, `/guest-registration`, `/api/public/visitor-registration`, `/api/config/lounges`, `/api/health`. Server admin **bloqueia /actuator, /grafana, /prometheus** e só aceita IPs privados. Rate-limit `10r/m` por IP no cadastro público com burst 20.
- **Migrations**: V1→V15 consistentes; V15 reverte secondary_area_id e cria N:N. Backfill correto. [V15__person_allowed_areas.sql](src/main/resources/db/migration/V15__person_allowed_areas.sql).
- **AdminUserSeeder** **bloqueia em prod** se a senha default for usada e se a senha não estiver setada quando o seed está ON.
- **Cleanup de visitantes** com 4 modos (`CANCELLED`, `FAILED`, `TEST_RECORDS`, `ALL` com frase "LIMPAR") + cleanup de eventos do dashboard com frase "LIMPAR_EVENTOS".
- **Backup script** (`scripts/backup.sh`) com escopo hourly/daily/manual e cron via `install-backup-cron.sh`.
- **Grafana provisioning** já tem alertas para backend down, postgres down, datasource, contact-points e notification-policies.
- **Dedup Intelbras**: por RecNo, natural key (CreateTime+UserID+Door+Method), janela temporal e fallback por personId/deviceTime/origin. [AccessEventService.java:117-153](src/main/java/br/com/sport/accesscontrol/events/AccessEventService.java#L117-L153).
- **applyAreaAuthorizationCheck**: se a pessoa identificada não tiver a área do dispositivo nas allowed, o evento é gravado mas marcado `DENIED` com `decisionReason="Sem permissão para a área X"`. [AccessEventService.java:610-654](src/main/java/br/com/sport/accesscontrol/events/AccessEventService.java#L610-L654).

---

## Bloqueadores críticos

1. **[CRÍTICO] `APP_SEED_ADMIN_ENABLED=true` em [.env.production](.env.production#L54)**
   Significa que **toda vez que o backend reiniciar** o seeder roda. Não é destrutivo (pula se já existe), mas mantém uma porta lógica permanentemente "engatilhada" e a senha default `Consuma123!` na vista. **Recomendado:** após o primeiro boot, mudar para `false` e remover linhas `APP_SEED_ADMIN_*`. Documentar no runbook.

2. **[CRÍTICO] Segredos reais em [.env.production](.env.production) no working tree**
   Embora `.gitignore` proteja `*.env`, o arquivo existe em disco com:
   - `POSTGRES_PASSWORD=Consuma123!` (fraca, reutilizada)
   - `RABBITMQ_DEFAULT_PASS=Consuma123!` (fraca, reutilizada)
   - `GRAFANA_ADMIN_PASSWORD=Consuma123!` (fraca, reutilizada)
   - `APP_SEED_ADMIN_PASSWORD=Consuma123!` (fraca, reutilizada — é a senha do **admin do sistema**)
   - `APP_INTELBRAS_DEFAULT_PASSWORD=consuma123` (fraca, padrão)
   - `JWT_SECRET=9pJ3VwLzQx7NfY2mK8rHtA4sBcE1uD6oPqR5xTnL0jHvM9yKcW2zF8aQeU7pR4mX` (real, em texto)
   - `GRAFANA_SMTP_PASSWORD=Javamaven1!` (parece App Password real do Gmail do desenvolvedor)
   - `GRAFANA_SMTP_USER=mateusdasilvacardoso091@gmail.com` (e-mail pessoal)
   **Recomendado antes do evento:** trocar todas as senhas para valores fortes únicos, retirar credenciais pessoais e usar conta institucional, considerar gerenciador de segredos (Vault, AWS Secrets, ou no mínimo `chmod 600`).

3. **[CRÍTICO] Não há seed/migration que crie a área "Portaria"**
   `LoungeAreaResolver.portariaArea()` busca por nome `Portaria` (case-insensitive). Não encontrei nenhuma migration que insira essa linha em `areas` (somente V15 faz backfill que **assume** a existência). Se "Portaria" não foi cadastrada manualmente na UI antes do evento, **todo convidado vai sair sem Portaria nas allowedAreas**, e o cadastro só dá acesso ao camarote — não à entrada geral. **Verificar no banco antes do evento:** `SELECT name FROM areas WHERE LOWER(TRIM(name)) IN ('portaria','front 1','front 2','front 3','institucional 1','institucional vereadores');` precisa devolver 6 linhas.

4. **[CRÍTICO] `frontend-public/` está vazio em código (só configs)**
   Diretórios `app/`, `components/`, `lib/`, `public/` vazios. Há `package.json` declarando "consuma-catraca-public" mas nenhuma página. Como o cadastro público agora vive em `frontend/app/page.tsx`, o serviço `frontend-public` aparentemente **não está em uso**. Se houve intenção de servir esse cadastro num servidor diferente, **não está pronto**. Decisão antes do evento: ou (a) deletar `frontend-public/` ou (b) implementar a rota lá. Hoje funciona apenas o caminho `frontend → nginx server público`.

---

## Riscos médios

- **Cooldown in-memory por instância**: se houver scale horizontal do backend, mesma pessoa em 2 instâncias diferentes consegue burlar o cooldown. Em produção single-instance (cenário do evento) **não é problema**. Documentar no runbook.
- **Webhook Intelbras `/api/integration/intelbras/webhooks/events`** está exposto e **aceita qualquer payload** sem autenticação — devolve só `{"status":"accepted","mode":"prepared"}`. Está marcado como "prepared", ou seja, ainda não processa o payload. **Risco:** quem souber a URL pode floodar o endpoint. Recomendado: bloquear `/api/integration/intelbras/webhooks` no nginx do server público (atualmente está permitido pelo catch-all "anyRequest().hasRole(ADMIN)" do SecurityConfig — então **na verdade exige auth** porque webhook não está na lista permitAll. Está OK do ponto de vista de segurança, mas a URL ainda existe sem implementação real).
- **Simuladores expostos por flag**: `AccessEventSimulatorController` e `IntelbrasSimulatorController` têm `matchIfMissing = true` no `@ConditionalOnProperty` — se `APP_SIMULATOR_ENABLED` faltar, **eles ficam ativos**. Em `.env.production` está `APP_SIMULATOR_ENABLED=false`, ok. Mas: convém apertar para `matchIfMissing = false` no código antes do evento.
- **`AdminUserSeeder` valida senha default em produção**, mas também loga `critical_default_admin_credentials_detected` durante os testes (visto no log do `mvn test`) — não é problema funcional, é só ruído. OK.
- **`InvitedDay` no admin form**: cadastro interno aceita qualquer data inclusive **passada**. O `localDate()` apenas pré-popula com hoje; nada impede 1900-01-01. Validar `invitedDay >= LocalDate.now()` antes do evento.
- **GuestService `update(...)` sobrescreve `phone`/`company`** mesmo se `null`/blank vier no request, o que pode apagar dados existentes. (Diferente de `completeRegistration` que faz check de blank.) Risco baixo no fluxo atual mas vale corrigir.
- **`POSTGRES_USER=admin`** em prod: usuário aplicativo deveria ser limitado (não superuser). PostgreSQL no compose roda com o usuário superuser do banco — risco baixo enquanto o banco não está exposto.
- **`GRAFANA_SMTP_ENABLED=true`** mas SMTP institucional pendente: alerta de evento crítico pode acabar caindo no Gmail pessoal do desenvolvedor.
- **`/api/integration/intelbras/webhooks`** + outros endpoints reais (`/api/integration`) **NÃO** estão no `permitAll` do SecurityConfig, então estão protegidos por JWT. ✅
- **Dispositivos pré-cadastrados sem credenciais Intelbras** vão sair como "rejected: missing_credentials" silenciosamente (apenas log). Antes do evento: revisar lista de devices em `/devices` e confirmar todos os intelbras reais têm IP, model contendo "intelbras"/"SS55xx", username, password.
- **Convidado com camarote não-cadastrado em `areas`** recebe apenas Portaria (warn no log: `lounge_area_not_found ... fallback=portaria_only`). Conferir consistência entre `APP_LOUNGES` e tabela `areas`.

---

## Ajustes rápidos antes do evento

| # | Ajuste | Local | Tempo |
|---|---|---|---|
| 1 | Trocar todas as senhas em `.env.production` | [.env.production](.env.production) | 15 min |
| 2 | Inserir a área **Portaria** se ainda não existir | DB ou UI `/areas` | 5 min |
| 3 | Conferir que existem 6 áreas (Portaria + 5 camarotes) com nomes idênticos a `APP_LOUNGES` (case-insensitive) | DB | 5 min |
| 4 | Configurar IP/credencial Intelbras em cada device em `/devices` | UI | 30 min |
| 5 | Validar SMTP institucional para alertas Grafana | `.env.production` GRAFANA_SMTP_* | 15 min |
| 6 | Confirmar `APP_SEED_ADMIN_ENABLED=false` após primeiro boot | `.env.production` | 1 min |
| 7 | Confirmar `APP_SIMULATOR_ENABLED=false` em prod | já está ok | — |
| 8 | Smoke test E2E: cadastro público → sync → liberação no device físico → cooldown blocked | manual | 30–60 min |
| 9 | `crontab -l` confirmar backup horário instalado no servidor | servidor prod | 5 min |
| 10 | Revisar `chmod 600 .env.production` | servidor prod | 1 min |

---

## Problemas de UX

- **Cadastro público (frontend/app/page.tsx)**: não há indicador de upload em andamento da foto (só "Finalizando..." do botão); aceita CPF inválido até o submit (sem feedback inline). Cabeçalho do telefone diz "(81) 99999-0000" — placeholder regional fixo.
- **Cadastro interno (`/guests`)**: textos de erro de CPF/camarote são funcionais, mas o estado `loungeError` só aparece se a mutation falhar — não revalida ao remover o erro. O `select` de status na busca tem "Todos / Cadastro pendente / Completo / Expirado / Cancelado" mas não exibe "Convidado" (`INVITED`) porque foi removido da lista `statuses` — coerente com fluxo atual, mas atenção.
- **Tela `/guests` ainda exibe campos legados em "Detalhes"**: mostra `company ?? "Sem empresa"` e `hostName` que para visitantes vindos do form curto **vêm vazios ou com texto fixo "Credenciamento interno"**. Visual confuso.
- **Sidebar do admin** não tem link explícito para `/access-events` (existe a página mas não está em `navigation.ts`). É acessível só pela URL.
- **Logs visitante** (`/logsvisitante`): separado do `/logs` administrativo, mas ambos puxam dos mesmos endpoints; verifique se o filtro padrão (origem, deviceId) faz sentido para o operador da entrada.
- **Operações ao vivo** (`/operations`) tem o `AccessEventSimulator` embutido — em produção com simulator off, esse componente provavelmente fica vazio/erro 404. Verificar.
- **Falta de mensagens distintas para "cooldown bloqueado"** versus "área não permitida" na tabela de eventos no frontend — ambos aparecem como `DENIED`, e a diferenciação fica no campo `decisionReason` (que está exibido como title/tooltip, não na tabela principal).
- **Login**: não há "esqueci minha senha" e mensagens de erro são genéricas — aceitável em ambiente fechado.

---

## Problemas de segurança

1. **`.env.production` em disco com senhas fracas idênticas** (5 serviços usam `Consuma123!`). Vide bloqueador #2.
2. **JWT_SECRET, GRAFANA_SMTP_PASSWORD e Intelbras password versionados em texto** (no working tree). Vide bloqueador #2.
3. **`APP_INTELBRAS_DEFAULT_USERNAME/PASSWORD`** caem como fallback para qualquer device sem credencial própria — se um device for adicionado sem senha, ele usa a default `admin/consuma123`. Mitigar via revisão manual.
4. **Webhook Intelbras** sem auth no controller. Hoje protegido por JWT do filter chain (cai em `anyRequest().hasRole(ADMIN)`), o que é o comportamento desejado, mas o controller não anota `@PreAuthorize`. Confirmar com smoke test `curl -X POST http://localhost/api/integration/intelbras/webhooks/events -d '{}'` → deve responder 401/403.
5. **`/api/auth/me` e `/api/auth/login`** estão `permitAll` para `POST /login` e `authenticated` para `GET /me` — correto.
6. **`/api/auth/register`** exige `ADMIN`. ✅
7. **CORS** permite credenciais com lista de origens vinda do env — ok desde que não tenha `*`. Atualmente `.env.production` lista 3 origens explícitas. ✅
8. **`/ws/**` está `permitAll` no Security**, mas o handshake real é interceptado por `RealtimeAuthenticationInterceptor` (testado, 5 testes). ✅
9. **`/actuator/health` e `/actuator/prometheus` expostos** — health é trivial; prometheus contém métricas que **podem vazar nomes de devices/áreas**. Nginx prod admin bloqueia `/actuator`, então não é exposto externamente. Internal-only OK.
10. **CPF é logado em vários pontos** (`enrichPersonSnapshot`, `personKey`). Em produção o nível root é INFO; revisar `logging.level` se quiser ocultar.
11. **Face photos** ficam em `uploads/faces/` no volume — não há criptografia at-rest. Aceitar via runbook.
12. **`personKey` por CPF** vazado em log: `cpf:12345678901` em info logs. Reduzir para `cpf:****6789` se possível.
13. **Rate-limit nginx** apenas no `/api/public/visitor-registration` (10r/m por IP). Demais endpoints públicos (`/api/config/lounges`, `/api/health`) sem rate-limit — risco baixo.

---

## Problemas de regra de negócio

- **Validade 15h–04h** corretamente computada via `LocalTime.of(15, 0)` e `plusDays(1).atTime(4, 0)` no fuso `America/Recife`. ✅ [GuestService.java:524-530](src/main/java/br/com/sport/accesscontrol/guests/GuestService.java#L524-L530) e [IntelbrasSyncWorker.java:341-353](src/main/java/br/com/sport/accesscontrol/integration/sync/IntelbrasSyncWorker.java#L341-L353).
- **`adminVisitorRegistration` injeta `visitReason="Convidado São João/Superfeito"` e `hostName="Credenciamento interno"`** automaticamente. Isso é arbitrário e vai aparecer no detalhe de cada visitante. Tudo bem se intencional.
- **`completeRegistration` (público antigo via invite token)** mantém o fluxo de invite + complete; o novo cadastro público de home não usa esse caminho. Convém deletar `/guest-registration/[token]` se não há mais convites via e-mail (`APP_MAIL_ENABLED=false`).
- **`AccessEventService.applyAreaAuthorizationCheck`** só dispara se a pessoa for resolvida (`personId != null`) e tiver allowedAreas mapeadas. Para **visitantes vindos de cadastro público antes de V15** ou que não bateram com guest no `findById`, **passa direto sem checar área** — é uma decisão consciente, comentada como "compatibilidade com dados antigos". Confirmar se o evento começa com base limpa.
- **`recordImported` ignora cooldown se `accessResult != ALLOWED`** — correto (não faz sentido bloquear evento negado), mas então um `DENIED` real **não atualiza o timer**. Match com a regra.
- **Convidado Front 1 recebe Portaria + Front 1** ✅ — confirmado em `LoungeAreaResolver.resolveForLounge`.
- **Convidado Front 2 não recebe Front 1** ✅ — apenas o camarote cujo nome bate.
- **Colaborador recebe Acesso Total** ✅ — `resolveAllForEmployee` + frontend `displayAllowedAreas: "Acesso Total"`.
- **`cooldown` aplicado também em `manualRelease`** lança IllegalStateException — bom, evita dupla liberação manual.
- **`InvitedDay`** pode ser passado (data antiga) sem validação — risco operacional baixo mas é furo de regra.

---

## Código morto/desnecessário

- **`frontend-public/`** — package separado totalmente vazio. **Recomendado: deletar** ou reativar se há plano de servidor separado. Como o build em prod já vai funcionar sem ele (nginx serve `/` via `frontend_upstream`), **não bloqueia**.
- **`PublicGuestRegistrationController` + `frontend/app/guest-registration/[token]/page.tsx`** — fluxo de invite por e-mail, hoje `APP_MAIL_ENABLED=false`. Funciona mas é via convite; o cadastro público novo usa o home. **Avaliar manter** para futuro ou remover.
- **`GuestService.legacyCleanupGuests`** — caminho legado quando `mode == null`, mantido por compatibilidade. Frontend só envia mode preenchido. **Remover quando seguro.**
- **Construtores duplicados de `GuestService` e `AccessEventService`** sem `LoungeAreaResolver`/sem `cooldownService` — mantidos para testes legados. Verificar se ainda há teste usando-os; se não, remover.
- **Campos `company`, `visitReason`, `hostName` no DTO `Guest`/`GuestResponse`** — não são mais editáveis no UI público; apenas `visitReason` exposto no `Guest.ts` ainda como `string` obrigatório, mas todos os pontos de criação interno/público passam defaults fixos.
- **`AccessEventSimulator` no `/operations`** — ativo apenas se `APP_SIMULATOR_ENABLED=true`. Em prod fica como UI inerte mas pode confundir o operador. Esconder por feature flag no frontend (`NEXT_PUBLIC_SIMULATOR_ENABLED`).
- **`IntelbrasWebhookController`** — devolve "prepared" e nada faz. Se o caminho real é polling (e é, via `IntelbrasEventPollingScheduler` a cada 5s), **remover o webhook controller** ou implementar.
- **`Guest.GuestRequest` DTO** mantém campos obrigatórios `visitReason`, `hostName`, `visitStart`, `visitEnd` — só usado pelo endpoint legado `POST /api/guests` (chamado via `guestService.create` no frontend mas `frontend/app/guests/page.tsx` **não usa**, só usa `createVisitorRegistration`). **Remover endpoint legado** ou marcar deprecated.
- **Migration V14** ficou parcialmente revertida (V15 dropa `secondary_area_id`). Tudo bem como histórico, mas não há mais nada usando "área secundária por device".

---

## Checklist final de homologação

- [ ] Trocar senhas reais em `.env.production` (5 serviços + Intelbras + JWT)
- [ ] `chmod 600 .env.production`
- [ ] Criar/confirmar área **Portaria** no DB
- [ ] Conferir as 6 áreas: Portaria, Front 1, Front 2, Front 3, Institucional 1, Institucional Vereadores
- [ ] Cadastrar todos devices reais em `/devices` com IP + model contendo "intelbras" ou "SS55xx" + username + password
- [ ] Confirmar que dispositivos online aparecem como ONLINE após health interval (30s)
- [ ] `curl -fsS http://localhost/api/health` → 200
- [ ] `docker compose -f docker-compose.prod.yml ps` → todos healthy
- [ ] Smoke test cadastro público (home `/`) — CPF válido, foto, camarote → 201
- [ ] Smoke test cadastro interno `/guests` (logado como ADMIN) → 201
- [ ] Verificar `allowedAreaNames` no GET `/api/guests` retorna `["Portaria", "<camarote>"]`
- [ ] Verificar sync Intelbras manual (`POST /api/guests/{id}/sync`) → SYNCED ou PARTIAL_SUCCESS
- [ ] Smoke test cooldown: 2 leituras consecutivas mesma pessoa mesmo device em <15s → segunda gera evento `cooldown_blocked=true`
- [ ] Smoke test área não permitida: convidado Front 1 passa em device Front 2 → DENIED com decisionReason
- [ ] Confirmar export CSV `/api/access-events/export.csv` baixa arquivo
- [ ] Confirmar cleanup visitantes (modes CANCELLED/FAILED/ALL) funciona
- [ ] Confirmar cleanup eventos `/api/admin/cleanup/access-events` exige frase "LIMPAR_EVENTOS"
- [ ] Mudar `APP_SEED_ADMIN_ENABLED=false` após primeiro boot
- [ ] Confirmar `APP_SIMULATOR_ENABLED=false` em prod
- [ ] `crontab -l` mostrar entrada de backup horário
- [ ] Backup manual de teste: `./scripts/backup.sh manual` e validar `backups/runs/<stamp>`
- [ ] Restaurar de backup em ambiente de homologação para validar o `restore.sh`
- [ ] Verificar acesso ao admin **só via IP privado/admin.local** (testar de IP público deveria dar 404)
- [ ] Verificar Grafana + Prometheus expostos só em 127.0.0.1
- [ ] Configurar SMTP institucional para alertas Grafana (hoje no Gmail pessoal)
- [ ] Validar que `frontend-public/` está apagado ou implementado

---

## Comandos executados e resultados

| Comando | Resultado |
|---|---|
| `./mvnw test` | **BUILD SUCCESS** — Tests run: **133**, Failures: 0, Errors: 0, Skipped: 18 (Testcontainers/Docker daemon ausente local) |
| `./mvnw clean package -DskipTests` | **BUILD SUCCESS** (exit 0) |
| `cd frontend && npm run build` | **✓ Compiled successfully in 2.8s**; rotas geradas: `/`, `/access-events`, `/areas`, `/dashboard`, `/devices`, `/employees`, `/guest-registration`, `/guest-registration/[token]`, `/guests`, `/login`, `/logs`, `/logsvisitante`, `/operations` |
| `docker compose -f docker-compose.prod.yml --env-file .env.production config` | OK — services: postgres, rabbitmq, backend, frontend, nginx, prometheus, grafana, postgres-exporter; networks: internal+public; volumes nomeados; healthchecks em todos |
| `docker compose -f docker-compose.prod.yml ps` | **NÃO EXECUTADO** — Docker Desktop não está rodando localmente (foi a causa dos 18 testes skipped) |
| `curl http://localhost/api/health` | **NÃO EXECUTADO** — backend não está em execução no ambiente de auditoria |

Testes Testcontainers que foram skipped: `AccessControlApiApplicationTests` (18). Esses precisam de Docker daemon e o painel integraria PostgreSQL/Rabbit real. **Recomendado rodar no servidor antes do evento** com `./mvnw verify -Pintegration` (ou equivalente) tendo Docker disponível.

---

## Decisão final: **QUASE PRONTO**

O sistema **compila, testa, builda e tem regra de negócio coerente com a documentação**. Há **3 bloqueadores reais** que precisam ser resolvidos antes do go-live:

1. Segredos fracos/reais em `.env.production` → trocar todas as senhas.
2. `APP_SEED_ADMIN_ENABLED=true` → desligar após primeiro boot.
3. Garantia de que a área **Portaria** existe no DB.

Mais 1 ponto operacional:
4. Confirmar a lista de devices Intelbras com credenciais corretas via UI.

Resolvidos esses 4 itens + checklist de smoke test executado, **o sistema está pronto para o evento**. Sem eles, há risco real de:
- Convidado entrar no camarote mas não conseguir passar pela Portaria.
- Comprometimento de credenciais.
- Conta admin de seed coexistindo com a real.

Tempo estimado de hardening: **1 dia útil**.
