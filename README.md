# access-control-api

Backend base enterprise para controle de acesso do Sport Club do Recife, preparado para integração operacional futura com controladoras Intelbras. Nesta etapa a comunicação Intelbras real ainda não foi implementada: eventos, sincronização e webhooks estão estruturados em modo preparado/simulado.

## Stack

- Java 21, Spring Boot 3, Maven
- PostgreSQL, Flyway, Spring Data JPA
- Spring Security, JWT, BCrypt, RBAC, Validation, Actuator
- RabbitMQ, WebSocket STOMP, Micrometer/Prometheus
- Next.js, React, TypeScript, Tailwind, React Query, Axios
- Docker Compose

## Subir infraestrutura

Guia completo para Mac: [docs/local-setup-mac.md](docs/local-setup-mac.md).

```bash
docker compose up -d
```

Serviços:

- API: `http://localhost:8080`
- PostgreSQL: `localhost:5432`, database `access_control`, user `access_user`, password `access_pass`
- RabbitMQ Management: `http://localhost:15672`, user `access_user`, password `access_pass`
- Prometheus: `http://localhost:9090`
- pgAdmin opcional: `docker compose --profile tools up -d pgadmin`, URL `http://localhost:5050`

## Rodar aplicação e testes

```bash
./mvnw spring-boot:run
./mvnw test
./mvnw -DskipTests package
```

Health e métricas:

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/api/health
```

`/actuator/prometheus` fica protegido por JWT. Exponha-o apenas em rede interna ou configure o Prometheus com autenticação Bearer.

## Autenticação e RBAC

Usuário inicial de desenvolvimento:

- Email: `admin@sport.local`
- Senha: `Admin@123456`
- Role: `ADMIN`

A senha é seedada via `ApplicationRunner` e armazenada com BCrypt em `users.password_hash`.

Roles:

- `ADMIN`: acesso total.
- `HR`: gerencia colaboradores, convidados, áreas e permissões; visualiza dispositivos e eventos.
- `SECURITY_VIEWER`: visualiza dashboard, dispositivos e eventos.

Fluxo JWT:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"admin@sport.local","password":"Admin@123456"}' \
  | jq -r '.accessToken')

curl http://localhost:8080/api/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

Endpoints de autenticação:

- `POST /api/auth/login`: público.
- `POST /api/auth/register`: somente `ADMIN`.
- `GET /api/auth/me`: autenticado.
- `GET /api/dashboard/summary`: `ADMIN`, `HR`, `SECURITY_VIEWER`.

Proteção principal:

- `/api/employees/**`: `ADMIN`, `HR`.
- `/api/guests/**`: `ADMIN`, `HR`.
- `/api/areas/**`: `ADMIN`, `HR`.
- `GET /api/devices/**`: `ADMIN`, `HR`, `SECURITY_VIEWER`.
- Alterações em `/api/devices/**`: `ADMIN`, `HR`.
- `GET /api/access-events/**`: `ADMIN`, `HR`, `SECURITY_VIEWER`.
- Simulação em `/api/access-events/**`: `ADMIN`, `HR`.
- `/api/simulator/**`: `ADMIN`, `HR`.
- `/api/health` e `/actuator/health`: públicos.
- `/actuator/prometheus`: autenticado.

Erros `401` e `403` usam a resposta padronizada `ApiError`.

## Frontend administrativo

O frontend mínimo está em `frontend/`.

```bash
cd frontend
npm install
npm run dev
```

URL local:

- `http://localhost:3000`

Configuração:

- `NEXT_PUBLIC_API_URL=http://localhost:8080`

Telas disponíveis:

- `/login`
- `/dashboard`
- `/employees`
- `/devices`
- `/access-events`
- `/areas`

O token JWT é guardado em `localStorage` para desenvolvimento e enviado via interceptor Axios em `Authorization: Bearer <token>`.

## Smoke test local

Pré-requisitos:

```bash
docker compose up -d
./mvnw spring-boot:run
cd frontend && npm run dev
```

Roteiro recomendado:

1. Abrir `http://localhost:3000/login`.
2. Entrar com `admin@sport.local` e `Admin@123456`.
3. Criar uma área em `http://localhost:3000/areas`.
4. Criar um dispositivo em `http://localhost:3000/devices`, vinculado à área criada.
5. Criar um colaborador em `http://localhost:3000/employees`.
6. Simular um evento pela collection [docs/http/access-control-smoke.http](docs/http/access-control-smoke.http), usando o CPF e o dispositivo criados.
7. Abrir `http://localhost:3000/dashboard` e conferir os cards.
8. Abrir `http://localhost:3000/access-events` e conferir o evento simulado.

Também é possível executar o fluxo somente por HTTP usando [docs/http/access-control-smoke.http](docs/http/access-control-smoke.http). Preencha `@token`, `@areaId`, `@deviceId` e `@employeeId` conforme as respostas das chamadas anteriores.

## Arquitetura

O backend preserva os módulos de domínio (`employees`, `areas`, `devices`, `permissions`, `events`, `audit`) e adiciona uma camada preparada para integração Intelbras em `integration/intelbras`, com client, DTOs, mapper, service, scheduler, webhook e simulator.

Fluxo operacional preparado:

1. REST API recebe cadastros, alterações e simulações.
2. Services salvam no PostgreSQL e publicam Spring Application Events.
3. Listeners desacoplados publicam logs, RabbitMQ e WebSocket.
4. Auditoria centralizada registra mudanças com `correlation_id`, `actor_ip`, `old_data`, `new_data` e `details`.
5. Scheduler fake busca devices online a cada 30 segundos e loga tentativa de sincronização Intelbras.

## Eventos internos

- `EmployeeCreatedEvent`
- `EmployeeUpdatedEvent`
- `EmployeeDeactivatedEvent`
- `AccessPermissionChangedEvent`
- `DeviceStatusChangedEvent`
- `AccessEventReceivedEvent`

## Mensageria RabbitMQ

Exchanges:

- `access.events`
- `integration.events`
- `audit.events`

Filas:

- `employee.sync.queue`
- `access.event.queue`
- `audit.queue`

Cada fila possui DLQ correspondente com exchange `access-control.dlx`. Consumers e publishers estão preparados e atualmente apenas registram logs simulados.

## WebSocket realtime

Endpoint STOMP:

- `/ws`

Tópicos:

- `/topic/access-events`
- `/topic/device-status`
- `/topic/system-alerts`

Eventos simulados publicam realtime em `/topic/access-events`.

Payloads ricos e exemplos de teste estão documentados em [`docs/realtime.md`](docs/realtime.md).

## Visitantes

O workflow de convidados com convite por token, cadastro publico e upload facial local está documentado em [`docs/guest-workflow.md`](docs/guest-workflow.md).

Configuração de SMTP e modo dev sem envio estão em [`docs/mail-setup.md`](docs/mail-setup.md).

## Exemplos REST

Criar colaborador:

```bash
curl -X POST http://localhost:8080/api/employees \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"fullName":"Leao da Ilha","cpf":"00000000000","email":"leao@sport.com.br","status":"ACTIVE"}'
```

Criar área:

```bash
curl -X POST http://localhost:8080/api/areas \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"Ilha do Retiro - Social","description":"Acesso social"}'
```

Criar dispositivo:

```bash
curl -X POST http://localhost:8080/api/devices \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"name":"Catraca Social 01","model":"Intelbras SS 5530 MF FACE","serialNumber":"INTELBRAS-001","ipAddress":"192.168.10.50","location":"Entrada social","operationType":"ENTRY_EXIT","status":"UNKNOWN","areaId":"UUID_DA_AREA"}'
```

Simular evento pelo endpoint existente:

```bash
curl -X POST http://localhost:8080/api/access-events/simulate \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"personType":"EMPLOYEE","personId":"UUID_DO_COLABORADOR","deviceId":"UUID_DO_DISPOSITIVO","eventType":"ENTRY","accessResult":"ALLOWED","origin":"SIMULATION","rawPayload":{"source":"manual-test"}}'
```

Simular evento no formato Intelbras preparado:

```bash
curl -X POST http://localhost:8080/api/simulator/access-event \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"cpf":"00000000000","deviceId":"UUID_DO_DISPOSITIVO","eventType":"ENTRY","result":"ALLOWED"}'
```

## Observabilidade

- Logs estruturados JSON via Logback encoder.
- `X-Correlation-Id` é aceito ou gerado automaticamente por request.
- Request logging inclui método, path, status, duração e IP.
- Prometheus coleta `/actuator/prometheus`.

## Próximos passos Intelbras real

- Preencher [docs/intelbras-integration-checklist.md](docs/intelbras-integration-checklist.md) com o equipamento real antes de codificar chamadas reais.
- Definir protocolo/API real por modelo de controladora.
- Implementar `IntelbrasClient` com autenticação, timeout, retry e circuit breaker.
- Criar mapeadores reais de payloads Intelbras.
- Sincronizar pessoas, faces, permissões e estados de device.
- Processar webhooks reais de eventos e heartbeats.
- Evoluir armazenamento do token no frontend para cookie httpOnly quando houver BFF ou backend web dedicado.
- Adicionar CRUD completo de convidados e permissões.
- Adicionar filtros/paginação em eventos, colaboradores e dispositivos.
- Evoluir DLQ/retry policy e dashboards operacionais.
