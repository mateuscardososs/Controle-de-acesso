# Teste Presencial das Controladoras Intelbras

Checklist para homologacao local no Sao Joao de Caruaru/Superfeito.

## Antes de ligar a operacao

- Confirmar IP fixo do servidor.
- Confirmar IP fixo de cada controladora/catraca.
- Confirmar que servidor e controladoras estao na mesma rede/VLAN operacional.
- Confirmar usuario/senha Intelbras de cada controladora.
- Confirmar portas HTTP liberadas entre servidor e controladoras.
- Confirmar `.env.production` com:
  - `APP_INTELBRAS_MODE=real`
  - `INTELBRAS_CONNECT_TIMEOUT_MS=3000ms`
  - `INTELBRAS_READ_TIMEOUT_MS=5000ms`
  - `INTELBRAS_RETRY_ATTEMPTS=2`
  - `INTELBRAS_RETRY_BACKOFF_MS=300ms`
  - `INTELBRAS_HEALTH_INTERVAL_SECONDS=30s`
  - `INTELBRAS_IMPORT_INTERVAL_SECONDS=5s`
  - `INTELBRAS_DEDUP_WINDOW_SECONDS=300s`

## Rede e credenciais

Para cada controladora:

```bash
ping <ip-da-controladora>
```

Validar no sistema:

- device cadastrado com IP correto;
- area correta;
- modelo Intelbras SS 55xx quando aplicavel;
- credenciais configuradas;
- status em `/api/devices/status`.

## Health/status

Validar:

```bash
curl -H "Authorization: Bearer <token>" http://<servidor>/api/devices/status
```

Esperado por controladora:

- `deviceId`;
- `deviceName`;
- `controllerIp`;
- `online=true` apos comunicacao bem-sucedida;
- `lastSuccessAt` preenchido;
- `lastFailureAt` vazio ou anterior ao ultimo sucesso;
- `lastError` vazio apos retorno da rede.

## Sincronizacao de pessoa, face e card

Para um convidado/colaborador de teste:

- cadastrar pessoa com CPF e foto;
- acionar sync;
- verificar logs do backend com `intelbras_real_sync_person_success`;
- validar na controladora que usuario/card/face existem;
- repetir sync da mesma pessoa e confirmar que nao cria duplicidade indevida.

## Eventos de acesso

Executar em pelo menos duas controladoras:

- acesso liberado por face;
- acesso negado por pessoa inexistente/sem permissao;
- passagem confirmada;
- evento sem passagem, quando a controladora suportar;
- leitura por card, se usada;
- liberação manual no sistema em caso de falha facial.

Validar:

- tela `/access-events`;
- filtros por pessoa, CPF, catraca, resultado e metodo;
- `release_method=MANUAL_ADMIN_RELEASE` para contingencia;
- ausencia de eventos duplicados quando o polling roda mais de uma vez.

## Queda e retorno de rede

Para uma controladora de teste:

1. Desconectar cabo/rede ou bloquear IP.
2. Aguardar ao menos `INTELBRAS_HEALTH_INTERVAL_SECONDS`.
3. Validar `/api/devices/status`:
   - `online=false` ou status offline;
   - `lastFailureAt` preenchido;
   - `lastError` com motivo tecnico.
4. Validar Grafana:
   - `controller_communication_failures_total`;
   - `controller_online_status=0`.
5. Restaurar rede.
6. Validar:
   - `online=true`;
   - `lastSuccessAt` atualizado;
   - importacao de eventos volta sem reiniciar aplicacao.

## Duplicidade

Rodar importacao/polling com o mesmo conjunto de eventos mais de uma vez.

Esperado:

- mesmo `controller_rec_no` nao duplica `access_events`;
- quando `RecNo` nao existir, chave natural por device/origem/horario/usuario/porta/metodo evita duplicidade;
- janela curta `INTELBRAS_DEDUP_WINDOW_SECONDS` cobre reenvios proximos da controladora.

## Grafana

Validar no dashboard `Controle de Acesso - Produção Local`:

- backend up;
- PostgreSQL up;
- acessos por minuto;
- liberados vs negados;
- falhas de reconhecimento;
- liberações manuais;
- falhas de comunicação por controladora;
- status online/offline das controladoras;
- latência de comunicação com controladoras.

## Evidencias minimas

Guardar prints ou anotacoes de:

- IPs testados;
- horario do teste;
- controladora testada;
- evento liberado;
- evento negado;
- queda de rede;
- retorno de rede;
- deduplicacao validada;
- backup recente em `backups/latest-success.txt`.
