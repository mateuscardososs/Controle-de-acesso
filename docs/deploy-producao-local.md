# Deploy de producao local

Este modo sobe a aplicacao completa em um unico servidor com Docker Compose:

- Nginx exposto em `HTTP_PORT` para receber trafego externo.
- Next.js acessivel apenas pela rede interna do Compose.
- Spring Boot acessivel apenas pela rede interna do Compose.
- PostgreSQL e RabbitMQ sem portas publicas.
- Volumes persistentes para banco, RabbitMQ, uploads de faces e logs.

O `docker-compose.yml` de desenvolvimento continua separado. Use producao apenas com `docker-compose.prod.yml` e `.env.production`.

## Preparar ambiente

1. Copie o arquivo de exemplo:

   ```bash
   cp .env.production.example .env.production
   ```

2. Edite `.env.production` e troque pelo menos:

   - `POSTGRES_PASSWORD`
   - `RABBITMQ_DEFAULT_PASS`
   - `JWT_SECRET`
   - `PUBLIC_BASE_URL`
   - `APP_CORS_ALLOWED_ORIGINS`

3. Confira a configuracao:

   ```bash
   docker compose --env-file .env.production -f docker-compose.prod.yml config
   ```

Tambem e possivel rodar a validacao estatica pedida sem env real:

```bash
docker compose -f docker-compose.prod.yml config
```

## Subir e parar

Subir com build:

```bash
./scripts/prod-up.sh
```

Parar sem remover volumes:

```bash
./scripts/prod-down.sh
```

Parar removendo volumes persistentes, apenas quando quiser apagar dados:

```bash
./scripts/prod-down.sh -v
```

## Rotas expostas pelo Nginx

- `/` encaminha para o frontend Next.js.
- `/api` encaminha para o backend Spring Boot.
- `/ws` encaminha WebSocket/STOMP para o backend.
- `/nginx-health` retorna healthcheck simples do proxy.

O frontend de producao e buildado para usar o mesmo host do Nginx:

- `NEXT_PUBLIC_API_URL=` deixa as chamadas REST relativas.
- `NEXT_PUBLIC_WS_URL=/ws` usa o proxy para realtime.

## Volumes persistentes

O Compose cria estes volumes:

- `postgres_data`: dados do PostgreSQL.
- `rabbitmq_data`: dados do RabbitMQ.
- `uploads_faces`: arquivos enviados para `FACE_UPLOAD_DIR`.
- `backend_logs`: logs JSON do Spring Boot.
- `nginx_logs`: logs do Nginx.

## Backup

Gerar backup:

```bash
./scripts/backup.sh
```

O backup fica em `backups/YYYYmmdd-HHMMSS` e inclui:

- dump custom do PostgreSQL;
- tar dos uploads de faces;
- tar dos logs do backend e Nginx.

Restaurar:

```bash
./scripts/restore.sh backups/YYYYmmdd-HHMMSS
```

O restore pede confirmacao digitando `RESTAURAR`, para backend/frontend/nginx, recria o banco e restaura uploads.

## HTTPS futuro

O `nginx.conf` ja tem um bloco comentado para `443 ssl`. Quando houver certificados:

1. Monte os arquivos em `./infra/nginx/certs`.
2. Habilite a porta `443` no `docker-compose.prod.yml`.
3. Descomente e ajuste o bloco HTTPS no `nginx.conf`.
4. Atualize `PUBLIC_BASE_URL`, `FRONTEND_PUBLIC_BASE_URL` e `APP_CORS_ALLOWED_ORIGINS` para `https://...`.

## Validacao antes de publicar

```bash
docker compose -f docker-compose.prod.yml config
./mvnw test
./mvnw -DskipTests package
cd frontend && npm run build
```
