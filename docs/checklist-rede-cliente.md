# Checklist de rede do cliente

Use este checklist para publicar o `consuma_catraca` em servidor local, com acesso externo apenas ao cadastro publico de visitante. Preencha os campos antes do evento e marque cada validacao feita.

## Dados do ambiente

- [ ] Cliente/evento: ______________________________________________
- [ ] Responsavel de rede: ________________________________________
- [ ] Responsavel da aplicacao: ___________________________________
- [ ] Data/hora da validacao: _____________________________________

## 1. IP fixo do servidor

- [ ] Servidor com IP fixo na LAN.
- [ ] IP LAN do servidor: `____.____.____.____`
- [ ] Gateway: `____.____.____.____`
- [ ] DNS interno: `____.____.____.____`
- [ ] O IP nao esta dentro do pool DHCP dinamico, ou possui reserva DHCP fixa.

Validar no servidor:

```bash
ip addr
ip route
```

No macOS:

```bash
ifconfig
netstat -rn
```

## 2. Portas necessarias

Publicar pela internet somente:

- [ ] `80/tcp` para HTTP, se ainda nao houver TLS.
- [ ] `443/tcp` para HTTPS, quando houver certificado.

Nao publicar para a internet:

- [ ] `3000/tcp` frontend Next.js.
- [ ] `8080/tcp` backend Spring Boot.
- [ ] `3001/tcp` Grafana.
- [ ] `9090/tcp` Prometheus.
- [ ] `5432/tcp` PostgreSQL.
- [ ] `5672/tcp` RabbitMQ.
- [ ] `15672/tcp` RabbitMQ Management.

Observabilidade deve ficar local:

- [ ] `GRAFANA_PORT=127.0.0.1:3001`
- [ ] `PROMETHEUS_PORT=127.0.0.1:9090`

Intelbras:

- [ ] Servidor consegue acessar os IPs das controladoras pela LAN.
- [ ] Firewall permite saida do servidor para as portas configuradas nas controladoras Intelbras.
- [ ] Nenhuma porta Intelbras precisa ser publicada para a internet.

## 3. Dominio publico

- [ ] Dominio publico: `cadastro.________________________`
- [ ] DNS publico aponta para o IP publico do cliente.
- [ ] NAT/redirecionamento aponta `80/443` para o IP LAN fixo do servidor.
- [ ] O proxy/NAT preserva o header `Host`.
- [ ] Se houver proxy reverso antes do servidor, ele aceita apenas o hostname publico `cadastro...` vindo da internet.
- [ ] Hostname interno/admin definido: `admin.local` ou ____________________
- [ ] `admin.local` resolve para o IP LAN do servidor apenas na rede local.

Variaveis recomendadas em `.env.production`:

```env
PUBLIC_BASE_URL=https://cadastro.seudominio.com.br
FRONTEND_PUBLIC_BASE_URL=https://cadastro.seudominio.com.br
APP_CORS_ALLOWED_ORIGINS=https://cadastro.seudominio.com.br,http://admin.local,http://localhost,http://127.0.0.1
NEXT_PUBLIC_API_URL=
NEXT_PUBLIC_WS_URL=/ws
```

## 4. Rotas publicas permitidas

No hostname publico, somente estas rotas devem responder:

- [ ] `GET /`
- [ ] `GET /guest-registration`
- [ ] `GET /guest-registration/*`
- [ ] `POST /api/public/visitor-registration`
- [ ] `GET /api/config/lounges`
- [ ] `GET /api/health`

Rotas tecnicas liberadas para o frontend:

- [ ] `GET /_next/static/*`
- [ ] `GET /favicon.ico`

Teste no servidor, trocando o IP pelo IP LAN fixo:

```bash
curl -i -H 'Host: cadastro.seudominio.com.br' http://IP_DO_SERVIDOR/
curl -i -H 'Host: cadastro.seudominio.com.br' http://IP_DO_SERVIDOR/api/health
curl -i -H 'Host: cadastro.seudominio.com.br' http://IP_DO_SERVIDOR/api/config/lounges
```

## 5. Rotas administrativas bloqueadas

No hostname publico, estas rotas nao podem exibir conteudo administrativo:

- [ ] `/dashboard`
- [ ] `/logs`
- [ ] `/logsvisitante`
- [ ] `/access-events`
- [ ] `/employees`
- [ ] `/devices`
- [ ] `/areas`
- [ ] `/operations`
- [ ] `/api/auth/**`
- [ ] `/api/admin/**`
- [ ] `/api/access-events/**`
- [ ] `/api/devices/**`
- [ ] `/api/employees/**`
- [ ] `/api/dashboard/**`
- [ ] `/actuator/**`
- [ ] `/ws/**`
- [ ] `/grafana`
- [ ] `/prometheus`

Validar bloqueios:

```bash
curl -i -H 'Host: cadastro.seudominio.com.br' http://IP_DO_SERVIDOR/dashboard
curl -i -H 'Host: cadastro.seudominio.com.br' http://IP_DO_SERVIDOR/api/auth/me
curl -i -H 'Host: cadastro.seudominio.com.br' http://IP_DO_SERVIDOR/ws
curl -i -H 'Host: cadastro.seudominio.com.br' http://IP_DO_SERVIDOR/actuator/health
curl -i -H 'Host: cadastro.seudominio.com.br' http://IP_DO_SERVIDOR/grafana
curl -i -H 'Host: cadastro.seudominio.com.br' http://IP_DO_SERVIDOR/prometheus
```

Resultado esperado:

- [ ] `404`, `403` ou outro bloqueio.
- [ ] Nunca HTML do painel admin.
- [ ] Nunca tela de login admin pelo dominio publico.

## 6. Teste pelo 4G do celular

No celular:

- [ ] Desligar Wi-Fi.
- [ ] Abrir `https://cadastro.seudominio.com.br`.
- [ ] Confirmar que aparece somente o formulario publico de visitante.
- [ ] Confirmar campos: nome, CPF, telefone, e-mail opcional, dia, camarote, camera e botao finalizar cadastro.
- [ ] Abrir `https://cadastro.seudominio.com.br/dashboard`.
- [ ] Confirmar que o painel admin nao aparece.
- [ ] Abrir `https://cadastro.seudominio.com.br/logs`.
- [ ] Confirmar que logs internos nao aparecem.

## 7. Teste pela rede local

Em uma maquina administrativa conectada na LAN:

- [ ] Abrir `http://admin.local`.
- [ ] Login admin funciona.
- [ ] Dashboard abre pela LAN.
- [ ] Logs internos abrem pela LAN.
- [ ] Dispositivos/colaboradores abrem pela LAN.
- [ ] WebSocket/realtime do painel funciona pela LAN.

Validar tambem:

```bash
curl -i -H 'Host: admin.local' http://IP_DO_SERVIDOR/api/health
curl -i -H 'Host: admin.local' http://IP_DO_SERVIDOR/dashboard
```

## 8. Teste de cadastro

Pelo dominio publico em 4G:

- [ ] Preencher nome.
- [ ] Preencher CPF valido.
- [ ] Preencher telefone.
- [ ] Preencher e-mail opcional, se houver.
- [ ] Selecionar dia.
- [ ] Selecionar camarote.
- [ ] Capturar foto pela camera.
- [ ] Clicar em finalizar cadastro.
- [ ] Confirmar mensagem: `Cadastro recebido com sucesso. Aguarde validacao da organizacao.`

Depois, pela LAN/admin:

- [ ] Validar que o visitante apareceu no painel interno.
- [ ] Validar que o cadastro nao abriu nenhuma rota administrativa no dominio publico.

## 9. Teste de camera

No celular em 4G:

- [ ] Ao tocar em `Ativar camera`, o navegador pede permissao.
- [ ] Ao permitir, a camera frontal abre.
- [ ] A foto e capturada.
- [ ] A foto aparece como pre-visualizacao.
- [ ] O botao `Refazer` funciona.

Teste negativo:

- [ ] Negar permissao da camera.
- [ ] Confirmar mensagem amigavel de camera nao autorizada.
- [ ] Reautorizar camera nas configuracoes do navegador e testar novamente.

Observacao: camera em navegadores moveis exige HTTPS, exceto em `localhost`.

## 10. Teste de sync Intelbras

Pelo painel admin na LAN:

- [ ] Confirmar controladoras cadastradas e online.
- [ ] Confirmar que o servidor consegue alcancar os IPs das controladoras.
- [ ] Fazer um cadastro de visitante de teste.
- [ ] Validar que o sync foi disparado.
- [ ] Validar status de sync do visitante no painel.
- [ ] Validar no log interno se houve erro de comunicacao com Intelbras.
- [ ] Fazer uma tentativa real controlada de acesso, se a operacao permitir.

Comandos uteis:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=200 backend
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=200 backend | grep -i intelbras
```

## 11. Teste de logs internos

Pelo painel admin na LAN:

- [ ] Abrir `/logs`.
- [ ] Abrir `/logsvisitante`.
- [ ] Confirmar que eventos recentes aparecem.
- [ ] Confirmar que essas telas nao abrem pelo dominio publico em 4G.

Pelo servidor:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=100 nginx
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=100 backend
```

## 12. Plano de contingencia se internet cair

Se a internet externa cair, mas a LAN continuar funcionando:

- [ ] Painel admin continua operando por `admin.local`.
- [ ] Operacao local continua acessando dashboard, logs e dispositivos pela LAN.
- [ ] Cadastros externos pelo dominio publico ficam indisponiveis ate a internet/NAT voltar.
- [ ] Visitantes presentes no local podem ser cadastrados pela equipe interna no painel admin, se o processo operacional permitir.
- [ ] Opcional: liberar Wi-Fi local controlado para convidados acessarem o dominio com DNS interno apontando para o servidor, mantendo apenas as rotas publicas.

Se o servidor cair:

- [ ] Acionar responsavel da aplicacao.
- [ ] Verificar energia, rede e Docker.
- [ ] Subir containers com os comandos da secao 13.
- [ ] Se houver perda de dados, avaliar restore a partir do ultimo backup validado.

Se o link voltar:

- [ ] Testar `https://cadastro.seudominio.com.br` pelo 4G.
- [ ] Repetir teste rapido de cadastro.
- [ ] Conferir logs do Nginx e backend.

## 13. Como reiniciar Docker

No diretorio do projeto:

```bash
cd /caminho/para/consuma_catraca
docker compose --env-file .env.production -f docker-compose.prod.yml restart
```

Reiniciar apenas Nginx:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml restart nginx
```

Reiniciar apenas backend:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml restart backend
```

Subir tudo com build, quando necessario:

```bash
./scripts/prod-up.sh
```

## 14. Como verificar saude

Status dos containers:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml ps
```

Saude da API pelo Nginx local:

```bash
curl http://localhost/api/health
```

Saude do Nginx:

```bash
curl http://localhost/nginx-health
```

Logs recentes:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=100 nginx
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=100 backend
```

## 15. Como validar backup

Gerar backup manual:

```bash
./scripts/backup.sh manual
```

Confirmar ultimo sucesso:

```bash
cat backups/latest-success.txt
cat backups/manifests/latest-success.txt
```

Conferir arquivos do backup:

```bash
ls -lah backups/runs
find backups/runs -maxdepth 3 -type f | tail -50
```

Validar que o backup contem:

- [ ] Dump do PostgreSQL: `db/postgres.dump`
- [ ] Uploads de faces: `files/uploads-faces.tar.gz`
- [ ] Logs tecnicos: `logs/technical-logs.tar.gz`
- [ ] Manifesto: `manifest.txt`

Nao fazer restore durante o evento sem autorizacao expressa do responsavel da operacao.

## 16. Como recarregar Nginx

Apos alterar `nginx.conf`:

```bash
./scripts/reload-nginx.sh
```

Se falhar:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml ps nginx
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=100 nginx
docker exec consuma_catraca-nginx-1 nginx -t
```

Depois de recarregar:

```bash
curl http://localhost/nginx-health
curl -i -H 'Host: cadastro.seudominio.com.br' http://localhost/api/health
```

## Checklist final de liberacao

- [ ] IP fixo configurado.
- [ ] NAT publica somente `80/443`.
- [ ] Dominio publico resolvendo corretamente.
- [ ] Admin acessivel somente pela LAN.
- [ ] Rotas publicas testadas pelo 4G.
- [ ] Rotas admin bloqueadas pelo 4G.
- [ ] Cadastro publico testado.
- [ ] Camera testada.
- [ ] Sync Intelbras testado.
- [ ] Logs internos testados pela LAN.
- [ ] Backup manual validado.
- [ ] Plano de contingencia alinhado com a equipe do cliente.
