# Setup local no Mac

Este guia prepara o projeto para rodar localmente usando Docker Desktop. O PostgreSQL deve subir pelo `docker compose`; não crie banco local fora do Docker.

## 1. Abrir Docker Desktop

1. Abra o app Docker Desktop no macOS.
2. Aguarde o status ficar como `Docker Desktop is running`.
3. Se o Docker pedir permissões ou atualização, conclua antes de continuar.

## 2. Verificar Docker e Compose

Na raiz do projeto:

```bash
docker version
docker compose version
```

Se algum comando falhar, o Docker Desktop ainda não está disponível para o terminal.

## 3. Subir infraestrutura

```bash
docker compose up -d
```

Verifique os containers:

```bash
docker compose ps
```

Serviços esperados:

- `access-control-postgres`
- `access-control-rabbitmq`
- `access-control-prometheus`
- `access-control-pgadmin`, apenas se usar `--profile tools`

Para subir pgAdmin também:

```bash
docker compose --profile tools up -d
```

## 4. Rodar backend

Em um terminal na raiz do projeto:

```bash
./mvnw spring-boot:run
```

O backend deve responder em:

- `http://localhost:8080/api/health`
- `http://localhost:8080/actuator/health`

Se aparecer erro `role "access_user" does not exist`, o backend está conectando em um PostgreSQL local diferente do container. Confirme que o Docker Compose subiu corretamente e que a porta `5432` não está ocupada por outro Postgres.

Para diagnosticar conflito de porta:

```bash
lsof -nP -iTCP:5432 -sTCP:LISTEN
```

Se aparecer `postgres` local além do Docker, pare o PostgreSQL local antes de iniciar o backend. Exemplos comuns:

```bash
brew services stop postgresql@16
brew services stop postgresql@15
brew services stop postgresql
```

Se você usa Postgres.app, encerre o app pela interface. Depois rode:

```bash
./scripts/dev-up.sh
./mvnw spring-boot:run
```

## 5. Rodar frontend

Em outro terminal:

```bash
cd frontend
npm install
npm run dev
```

Acesse:

- `http://localhost:3000`

Login inicial:

- Email: `admin@empresa.local`
- Senha: `Admin@123456`

## 6. Atalhos opcionais

Subir infraestrutura e checar PostgreSQL:

```bash
./scripts/dev-up.sh
```

Verificar ambiente e portas:

```bash
./scripts/dev-check.sh
```
