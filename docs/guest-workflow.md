# Guest Workflow

O fluxo de visitantes permite criar convites, receber cadastro publico por token e armazenar foto facial em ambiente de desenvolvimento, sem reconhecimento facial real e sem integrar Intelbras ainda.

## Fluxo

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

## Endpoints publicos por token

- `GET /api/guest-registration/{token}`
- `POST /api/guest-registration/{token}/complete`

O endpoint publico retorna apenas dados necessarios para o cadastro do visitante, evitando expor CPF e email.

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
- `GUEST_UPDATED`
- `GUEST_CANCELLED`
- `GUEST_INVITE_RESENT`
- `GUEST_FACE_UPLOADED`
- `GUEST_REGISTRATION_COMPLETED`
- `GUEST_EXPIRED`

Alertas realtime sao publicados em `/topic/system-alerts` quando o visitante e criado, completa cadastro, e quando convite/visita expira ou e cancelado.

## E-mail transacional

O envio SMTP e opcional e esta descrito em [`mail-setup.md`](mail-setup.md). Mesmo quando o envio e desabilitado ou falha, o admin continua recebendo o `inviteUrl` para copiar manualmente.

## Proximos passos Intelbras

- sincronizar visitantes `COMPLETED` com o provider Intelbras
- enviar foto facial para cadastro biometrico real
- tratar callbacks reais de autorizacao/negacao
- implementar revogacao no provedor quando convite for cancelado ou expirar
