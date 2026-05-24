# Dev Tunnel

## Alternar entre local e túnel

O modo local é o padrão do projeto. Use túnel apenas quando precisar expor a aplicação para testes externos.

Local:

```properties
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws
```

Túnel:

```properties
NEXT_PUBLIC_API_URL=https://<backend-tunnel>
NEXT_PUBLIC_WS_URL=wss://<backend-tunnel>/ws
```

Em HTTPS público, o WebSocket precisa ser `wss://`.

## URLs

- Frontend local: `http://localhost:3000`
- Backend local: `http://localhost:8080`
- Frontend público: `https://<frontend-tunnel>`
- Backend público: `https://<backend-tunnel>`

## Frontend

O Next.js não carrega `.env.example`. Use `frontend/.env.local`:

```properties
NEXT_PUBLIC_API_URL=http://localhost:8080
NEXT_PUBLIC_WS_URL=ws://localhost:8080/ws
```

Para testar por túnel público, troque temporariamente para:

```properties
NEXT_PUBLIC_API_URL=https://<backend-tunnel>
NEXT_PUBLIC_WS_URL=wss://<backend-tunnel>/ws
```

Subir:

```bash
cd frontend
npm run dev
```

Sempre reinicie o servidor Next.js depois de mudar `.env.local`.

## Backend

Subir:

```bash
APP_CORS_ALLOWED_ORIGINS=http://localhost:3000,http://127.0.0.1:3000,https://<frontend-tunnel> \
./mvnw spring-boot:run
```

Com Intelbras real:

```bash
APP_INTELBRAS_MODE=real \
APP_INTELBRAS_DEFAULT_USERNAME=admin \
APP_INTELBRAS_DEFAULT_PASSWORD='<senha-real-da-controladora>' \
APP_CORS_ALLOWED_ORIGINS=http://localhost:3000,http://127.0.0.1:3000,https://<frontend-tunnel> \
./mvnw spring-boot:run
```

## Testes Rápidos

Health local:

```bash
curl -i http://localhost:8080/api/health
```

Health público:

```bash
curl -i https://<backend-tunnel>/api/health
```

Preflight CORS:

```bash
curl -i -X OPTIONS \
  -H "Origin: https://<frontend-tunnel>" \
  -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: content-type,x-request-id" \
  https://<backend-tunnel>/api/public/visitor-registration
```

WebSocket:

```text
wss://<backend-tunnel>/ws
```

## Cadastro Público

Abra:

```text
https://<frontend-tunnel>/
```

Envie o formulário com uma imagem `PNG`, `JPG` ou `WEBP`. O endpoint usado é:

```text
POST https://<backend-tunnel>/api/public/visitor-registration
```

## Problemas Comuns

- `.env.example` é documentação; o Next.js lê `.env.local`.
- Em HTTPS público, WebSocket precisa ser `wss://`, não `ws://`.
- Não use barra final em `NEXT_PUBLIC_API_URL`; o client normaliza, mas o valor correto é sem barra final.
- `502` no domínio `*.devtunnels.ms` normalmente indica que o túnel não está conectado ao processo local, a porta local está parada ou o túnel não está visível publicamente.
- Se o Dev Tunnel pedir autorização, mude a visibilidade do túnel para `Public`.
- Se aparecer erro de CORS, confirme `APP_CORS_ALLOWED_ORIGINS` e reinicie o backend.
- Se aparecer `413`, reduza a imagem ou aumente `SPRING_SERVLET_MULTIPART_MAX_FILE_SIZE` e `SPRING_SERVLET_MULTIPART_MAX_REQUEST_SIZE`.
