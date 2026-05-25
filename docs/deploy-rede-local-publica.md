# Deploy em rede local com cadastro publico

Este guia cobre o cenario em que o `consuma_catraca` roda em um servidor local na rede do evento e o responsavel de rede faz DNS/NAT do dominio publico para esse servidor.

Objetivo:

- expor para a internet somente o cadastro publico de visitante;
- manter painel administrativo, WebSocket admin, Actuator, Grafana e Prometheus restritos a LAN/localhost;
- evitar qualquer exposicao direta de backend, frontend, banco, RabbitMQ, Grafana ou Prometheus fora do Nginx.

## Hostnames

Use dois nomes:

- Publico: `cadastro.seudominio.com.br`
- Interno/admin: `admin.local` ou `localhost`

Na rede local, aponte `admin.local` para o IP LAN do servidor, por DNS interno ou pelo arquivo `hosts` das maquinas administrativas.

Exemplo:

```text
192.168.10.20 admin.local
```

Para internet, aponte `cadastro.seudominio.com.br` para o IP publico que sera redirecionado/NATeado para o servidor local.

## Como o Nginx separa os acessos

O `nginx.conf` tem dois blocos principais:

- `cadastro.seudominio.com.br` como `default_server`, fechado por padrao e liberando apenas rotas publicas.
- `admin.local`, `localhost` e hosts com IP privado, liberados apenas para origens de LAN/localhost via `allow`/`deny`.

Isso faz com que o hostname publico nunca encaminhe `/dashboard`, `/api/auth/**`, `/ws/**`, `/actuator/**` ou rotas administrativas.

Rotas permitidas no hostname publico:

- `GET /`
- `GET /guest-registration`
- `GET /guest-registration/*`
- `POST /api/public/visitor-registration`
- `GET /api/config/lounges`
- `GET /api/health`

O `POST /api/public/visitor-registration` tem rate limit por IP no Nginx:

- 10 requisicoes por minuto;
- `burst 20`;
- resposta JSON amigavel com status `429 Too Many Requests`;
- payload limitado a `10MB`, alinhado ao limite multipart do backend para foto da camera.

Rotas tecnicas tambem liberadas no hostname publico para a pagina Next.js funcionar:

- `GET /_next/static/*`
- `GET /favicon.ico`

Todas as demais rotas no hostname publico retornam `404`.

## Rotas que nao devem ficar publicas

Estas rotas permanecem bloqueadas no hostname publico:

- `/dashboard`
- `/logs`
- `/logsvisitante`
- `/access-events`
- `/employees`
- `/devices`
- `/areas`
- `/operations`
- `/api/auth/**`
- `/api/admin/**`
- `/api/access-events/**`
- `/api/devices/**`
- `/api/employees/**`
- `/api/dashboard/**`
- `/actuator/**`
- `/ws/**`
- `/grafana`
- `/prometheus`

Grafana e Prometheus tambem continuam publicados apenas em `127.0.0.1` pelo `docker-compose.prod.yml`:

- `GRAFANA_PORT=127.0.0.1:3001`
- `PROMETHEUS_PORT=127.0.0.1:9090`

Nao altere essas variaveis para `0.0.0.0` em ambiente com internet chegando ao servidor.

## Observacao sobre convites por token

A rota visual `GET /guest-registration/*` fica acessivel no hostname publico porque foi solicitada como rota publica.

Os endpoints `/api/guest-registration/**`, usados pelo fluxo de convite por token, nao foram liberados no hostname publico porque nao estao na lista de rotas publicas permitidas deste deploy. Se o evento precisar concluir convites por token a partir da internet, aprove explicitamente essa exposicao antes de adicionar excecoes no Nginx.

## Variaveis recomendadas

Em `.env.production`, mantenha o frontend usando chamadas relativas pelo Nginx:

```env
NEXT_PUBLIC_API_URL=
NEXT_PUBLIC_WS_URL=/ws
```

Configure as origens conhecidas do ambiente:

```env
PUBLIC_BASE_URL=https://cadastro.seudominio.com.br
FRONTEND_PUBLIC_BASE_URL=https://cadastro.seudominio.com.br
APP_CORS_ALLOWED_ORIGINS=https://cadastro.seudominio.com.br,http://admin.local,http://localhost,http://127.0.0.1
```

Se o deploy ainda estiver sem TLS, use `http://` temporariamente e troque para `https://` quando os certificados forem ativados.

## Logs e fotos

O Nginx usa um formato de access log sanitizado que registra metodo, caminho normalizado e status, sem query string e sem corpo da requisicao. Assim, fotos/base64 enviadas por payload ou por parametro acidental nao entram nos logs do proxy.

No backend, o Logback tambem mascara campos sensiveis como `facePhoto`, `photoData`, `biometricData`, `token`, `authorization` e `password`.

## Regras para o NAT/firewall

No roteador/firewall:

1. Redirecione somente `80` e, quando houver TLS, `443` para o servidor local.
2. Nao redirecione `3000`, `8080`, `3001`, `9090`, `5432`, `5672` ou `15672`.
3. Preserve o header `Host` recebido pelo cliente.
4. Sempre que possivel, preserve o IP de origem do cliente.

Ponto importante: se o NAT/reverse proxy mascarar todo acesso externo como um IP privado da LAN, o Nginx nao consegue distinguir esse trafego de um acesso interno real no bloco admin. Nesse caso, faca uma destas protecoes na camada de rede:

- permitir no proxy publico somente o hostname `cadastro.seudominio.com.br`;
- bloquear `Host: admin.local` no proxy publico;
- publicar o admin em uma porta/interface acessivel apenas pela LAN;
- usar firewall local para aceitar admin somente das sub-redes administrativas.

## Subir e recarregar

Validar Compose:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml config --quiet
```

Subir producao:

```bash
./scripts/prod-up.sh
```

Recarregar Nginx depois de alterar `nginx.conf`:

```bash
./scripts/reload-nginx.sh
```

## Validacao

Substitua `192.168.10.20` pelo IP LAN do servidor.

Validar rotas publicas:

```bash
curl -i -H 'Host: cadastro.seudominio.com.br' http://192.168.10.20/
curl -i -H 'Host: cadastro.seudominio.com.br' http://192.168.10.20/api/health
curl -i -H 'Host: cadastro.seudominio.com.br' http://192.168.10.20/api/config/lounges
```

Validar rate limit do cadastro publico sem enviar foto real:

```bash
for i in $(seq 1 35); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -X POST \
    -H 'Host: cadastro.seudominio.com.br' \
    http://192.168.10.20/api/public/visitor-registration
done
```

Apos o burst, as respostas devem passar a retornar `429` com mensagem JSON amigavel. Em uso real, requisicoes normais de visitantes nao devem chegar perto desse volume por IP.

Validar bloqueios no hostname publico:

```bash
curl -i -H 'Host: cadastro.seudominio.com.br' http://192.168.10.20/dashboard
curl -i -H 'Host: cadastro.seudominio.com.br' http://192.168.10.20/api/auth/me
curl -i -H 'Host: cadastro.seudominio.com.br' http://192.168.10.20/ws
curl -i -H 'Host: cadastro.seudominio.com.br' http://192.168.10.20/actuator/health
curl -i -H 'Host: cadastro.seudominio.com.br' http://192.168.10.20/grafana
curl -i -H 'Host: cadastro.seudominio.com.br' http://192.168.10.20/prometheus
```

Todos devem retornar `404` ou outro bloqueio, nunca conteudo administrativo.

Validar admin pela LAN:

```bash
curl -i -H 'Host: admin.local' http://192.168.10.20/api/health
curl -i -H 'Host: admin.local' http://192.168.10.20/dashboard
```

O `/dashboard` pode retornar HTML do Next.js ou redirecionar para login, mas nao deve retornar `404` pelo Nginx quando chamado de uma origem LAN permitida.

Validar que observabilidade nao foi publicada pelo Nginx:

```bash
curl -i -H 'Host: admin.local' http://192.168.10.20/grafana
curl -i -H 'Host: admin.local' http://192.168.10.20/prometheus
```

Ambos devem retornar `404` no Nginx. Acesse Grafana e Prometheus somente no proprio servidor, por `127.0.0.1:3001` e `127.0.0.1:9090`, ou por tunel seguro/SSH quando necessario.

## Checklist antes do evento

- DNS publico de `cadastro.seudominio.com.br` aponta para o IP publico correto.
- NAT encaminha apenas `80/443` para o servidor local.
- DNS interno ou `hosts` resolve `admin.local` para o IP LAN do servidor.
- `GRAFANA_PORT` e `PROMETHEUS_PORT` continuam em `127.0.0.1`.
- `docker compose --env-file .env.production -f docker-compose.prod.yml config --quiet` passa.
- Validacoes com `curl -H 'Host: cadastro.seudominio.com.br'` bloqueiam painel e APIs admin.
- Admin abre pela LAN com `http://admin.local`.
- Nenhuma porta direta do backend, frontend, banco, RabbitMQ, Grafana ou Prometheus foi publicada para internet.
