# Guest Workflow

O fluxo de visitantes permite receber cadastro publico na pagina inicial, criar convites administrativos por token e armazenar foto facial em ambiente de desenvolvimento, sem reconhecimento facial real e sem integrar Intelbras ainda.

## Fluxo

### Cadastro publico em `/`

1. Visitante acessa `/`, rota publica do frontend.
2. Visitante preenche dados, periodo da visita, responsavel/host e pode enviar foto facial.
3. Frontend envia `multipart/form-data` para `POST /api/public/visitor-registration`.
4. Backend valida campos obrigatorios, CPF, e-mail e janela da visita.
5. Backend cria `Guest`, gera convite interno e, se a foto vier no envio, salva a imagem e marca o visitante como `COMPLETED`.
6. Backend audita a criacao publica e publica alerta realtime em `/topic/system-alerts`.

Essa rota nao exige login e nao retorna CPF, e-mail ou token de convite na resposta publica.

### Convite administrativo por token

1. RH/Admin cria visitante em `POST /api/guests`.
2. Backend cria o visitante com status `PENDING_REGISTRATION`.
3. Backend gera token aleatorio em `guest_invites`, com expiracao configuravel por `GUEST_INVITE_EXPIRATION_HOURS`.
4. Backend tenta enviar o convite por e-mail se `APP_MAIL_ENABLED=true`; em dev, o envio pode ser pulado sem quebrar o fluxo.
5. Visitante acessa `/guest-registration/{token}` no frontend.
5. Frontend valida o token em `GET /api/guest-registration/{token}`.
6. Visitante complementa telefone/empresa e envia foto facial.
7. Frontend envia `multipart/form-data` para `POST /api/guest-registration/{token}/complete`.
8. Backend salva a imagem localmente e marca o visitante como `COMPLETED`.

## Endpoints administrativos

- `POST /api/guests`
- `GET /api/guests`
- `GET /api/guests?scope=today`
- `GET /api/guests/{id}`
- `PUT /api/guests/{id}`
- `PATCH /api/guests/{id}/cancel`
- `POST /api/guests/{id}/resend-invite`
- `POST /api/guests/{id}/complete-registration`

## Endpoints publicos

- `POST /api/public/visitor-registration`
- `GET /api/guest-registration/{token}`
- `POST /api/guest-registration/{token}/complete`

Os endpoints publicos retornam apenas dados necessarios para o cadastro do visitante, evitando expor CPF, email e token.

## Rotas frontend

- `/`: cadastro publico de visitante.
- `/login`: entrada administrativa.
- `/guest-registration/{token}`: cadastro publico a partir de convite.
- `/dashboard`, `/operations`, `/guests`, `/employees`, `/devices`, `/access-events`, `/areas`: rotas administrativas protegidas por JWT/RBAC.

## Upload facial

`FaceStorageService` salva arquivos em:

- `uploads/faces`

Configuravel por:

- `FACE_UPLOAD_DIR`

Validacoes:

- tamanho maximo de 5MB
- extensoes `jpg`, `jpeg`, `png`, `webp`
- conteudo precisa ser imagem valida
- nome final gerado pelo servidor para evitar path traversal

## Expiracao

Convites possuem expiracao por token. Um scheduler tambem marca visitantes vencidos como `EXPIRED` quando o periodo da visita termina sem conclusao/cancelamento.

## Auditoria e realtime

Eventos auditados:

- `GUEST_CREATED`
- `PUBLIC_GUEST_CREATED`
- `PUBLIC_GUEST_FACE_UPLOADED`
- `GUEST_UPDATED`
- `GUEST_CANCELLED`
- `GUEST_INVITE_RESENT`
- `GUEST_FACE_UPLOADED`
- `GUEST_REGISTRATION_COMPLETED`
- `GUEST_EXPIRED`

Alertas realtime sao publicados em `/topic/system-alerts` quando o visitante e criado, completa cadastro, e quando convite/visita expira ou e cancelado.

## E-mail transacional

O envio SMTP e opcional e esta descrito em [`mail-setup.md`](mail-setup.md). Mesmo quando o envio e desabilitado ou falha, o admin continua recebendo o `inviteUrl` para copiar manualmente.

Quando a sincronização Intelbras do visitante termina com sucesso, o backend envia o e-mail “Acesso de visitante liberado”. Se o envio estiver desabilitado ou falhar, a sincronização continua como `SYNCED` e o resultado fica registrado no detalhe do visitante e na auditoria.

## Proximos passos Intelbras

- sincronizar visitantes `COMPLETED` com o provider Intelbras
- enviar foto facial para cadastro biometrico real
- tratar callbacks reais de autorizacao/negacao
- implementar revogacao no provedor quando convite for cancelado ou expirar
