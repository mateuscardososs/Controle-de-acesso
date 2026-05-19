# Mail Setup

O envio de e-mails transacionais e opcional. Em desenvolvimento, o padrão e não enviar e-mail real e manter o link de convite retornando na API e visivel no admin.

## Variaveis

```env
APP_MAIL_ENABLED=false
APP_MAIL_FROM=no-reply@empresa.local
FRONTEND_PUBLIC_BASE_URL=http://localhost:3000

SMTP_HOST=localhost
SMTP_PORT=1025
SMTP_USERNAME=
SMTP_PASSWORD=
SMTP_AUTH=false
SMTP_STARTTLS=false
```

Tambem existem as propriedades Spring equivalentes:

- `app.mail.enabled`
- `app.mail.from`
- `app.frontend.public-base-url`
- `spring.mail.host`
- `spring.mail.port`
- `spring.mail.username`
- `spring.mail.password`
- `spring.mail.properties.mail.smtp.auth`
- `spring.mail.properties.mail.smtp.starttls.enable`

## Modo dev sem envio

Com `APP_MAIL_ENABLED=false`, o fluxo nao falha:

- cria visitante
- gera token
- retorna `inviteUrl`
- registra auditoria de envio pulado
- loga `mail_delivery_skipped`

Use o botão de copiar link na tela `/guests`.

## Mailtrap

```env
APP_MAIL_ENABLED=true
APP_MAIL_FROM=no-reply@empresa.local
SMTP_HOST=sandbox.smtp.mailtrap.io
SMTP_PORT=2525
SMTP_USERNAME=seu_usuario_mailtrap
SMTP_PASSWORD=sua_senha_mailtrap
SMTP_AUTH=true
SMTP_STARTTLS=true
```

## Gmail App Password

Use uma senha de app, nunca a senha normal da conta.

```env
APP_MAIL_ENABLED=true
APP_MAIL_FROM=sua-conta@gmail.com
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_USERNAME=sua-conta@gmail.com
SMTP_PASSWORD=sua_app_password
SMTP_AUTH=true
SMTP_STARTTLS=true
```

## E-mails enviados

- convite de visitante
- reenvio de convite
- confirmação de cadastro concluído

Se o SMTP falhar, o backend registra falha em auditoria, loga o motivo e preserva o fluxo. O `inviteUrl` continua retornando para uso manual.

## Riscos de produção

- proteger credenciais em secrets manager ou variáveis de ambiente
- usar domínio e remetente verificados
- configurar SPF, DKIM e DMARC
- monitorar bounce/spam
- definir rate limit para reenvio de convite
