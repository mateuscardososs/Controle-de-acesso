# Deploy com HTTPS gratuito (Caddy + Let's Encrypt)

## Como funciona

O projeto usa **Caddy** como proxy reverso externo com renovação automática de certificados via Let's Encrypt.

```
Internet
  │
  ▼ porta 80 (redirect → HTTPS)
  ▼ porta 443 (TLS, certificado automático)
[Caddy]
  │
  ▼ HTTP interno (porta 80 na rede Docker)
[nginx]  ←── roteamento, rate limiting, proteção de rotas
  │
  ├──▶ [frontend :3000]
  └──▶ [backend  :8080]
```

O nginx **não** fica exposto externamente para o domínio público — apenas o Caddy. O nginx ainda é acessível diretamente para uso administrativo via LAN (porta `ADMIN_HTTP_PORT`, padrão `8080`).

---

## Pré-requisitos

1. **Domínio apontado para o servidor** — DNS tipo A do domínio (ex: `cadastro.suaempresa.com.br`) deve resolver para o IP público do servidor.
2. **Portas abertas no firewall** — 80 (HTTP challenge + redirect) e 443 (HTTPS).
3. **Docker e Docker Compose** instalados no servidor.

---

## Configuração

### 1. Editar `.env.production`

Adicione ou atualize estas variáveis:

```env
# Domínio público (sem https://)
PUBLIC_DOMAIN=cadastro.suaempresa.com.br

# E-mail para notificações de renovação (Let's Encrypt)
ACME_EMAIL=seu-email@empresa.com.br

# Portas externas (não alterar se 80/443 estiverem livres)
HTTP_PORT=80
HTTPS_PORT=443

# Porta de acesso administrativo via LAN
ADMIN_HTTP_PORT=8080

# CORS — inclua o domínio HTTPS
APP_CORS_ALLOWED_ORIGINS=https://cadastro.suaempresa.com.br,http://admin.local,http://localhost
FRONTEND_PUBLIC_BASE_URL=https://cadastro.suaempresa.com.br
PUBLIC_BASE_URL=https://cadastro.suaempresa.com.br
```

### 2. Verificar o `Caddyfile`

O arquivo `Caddyfile` na raiz do projeto usa `{$PUBLIC_DOMAIN}` e `{$ACME_EMAIL}`. Não é necessário editá-lo; as variáveis de ambiente são suficientes.

### 3. Subir com Docker Compose

```bash
docker compose -f docker-compose.prod.yml up -d
```

Na **primeira inicialização**, o Caddy solicitará o certificado ao Let's Encrypt. Aguarde ~30 segundos. O certificado é renovado automaticamente antes do vencimento.

---

## Verificar se o certificado foi gerado

```bash
# Ver logs do Caddy
docker compose -f docker-compose.prod.yml logs caddy --tail=50

# Testar HTTPS externamente
curl -I https://cadastro.suaempresa.com.br/api/health

# Informações do certificado
openssl s_client -connect cadastro.suaempresa.com.br:443 -showcerts </dev/null 2>/dev/null | openssl x509 -noout -dates
```

Saída esperada nos logs do Caddy ao obter o certificado:
```
certificate obtained successfully
```

---

## Acesso administrativo (LAN)

O painel admin é acessível apenas pela rede local, sem HTTPS (tráfego interno):

```
http://IP-DO-SERVIDOR:8080
```

O nginx aplica restrição por IP (`allow 192.168.0.0/16; deny all;`). Ninguém da internet consegue acessar mesmo que conheça a porta.

Para mudar a porta do admin:
```env
ADMIN_HTTP_PORT=9090
```

---

## Testar a câmera no celular

1. Abra `https://cadastro.suaempresa.com.br/guest-registration/<token>` no celular.
2. Com HTTPS ativo, o botão **"Câmera ao vivo"** ficará disponível.
3. Em HTTP (LAN sem domínio), o fallback de **"Foto / Galeria"** aparece automaticamente.
4. Toque na área tracejada → o iOS/Android abre opção de câmera ou galeria.

---

## Diagrama de portas

| Porta | Protocolo | Serviço    | Acesso         |
|-------|-----------|------------|----------------|
| 80    | HTTP      | Caddy      | Público (redirect → 443) |
| 443   | HTTPS     | Caddy      | Público (TLS) |
| 8080  | HTTP      | nginx      | LAN apenas (admin) |
| 9090  | HTTP      | Prometheus | localhost apenas |
| 3001  | HTTP      | Grafana    | localhost apenas |

---

## Troubleshooting

**Caddy não emite certificado:**
- Verifique se o DNS do domínio aponta para o IP público do servidor: `dig +short cadastro.suaempresa.com.br`
- Verifique se as portas 80 e 443 estão abertas no firewall: `nc -zv IP-PUBLICO 80` e `nc -zv IP-PUBLICO 443`
- Veja os logs: `docker compose -f docker-compose.prod.yml logs caddy`

**Rate limit de Let's Encrypt (muitas tentativas):**
- Aguarde 1 hora. O Caddy retenta automaticamente.
- Em ambiente de teste, adicione `acme_ca https://acme-staging-v02.api.letsencrypt.org/directory` no bloco global do `Caddyfile` para usar o staging.

**Câmera ainda não funciona no celular em HTTPS:**
- Confirme que o URL no celular começa com `https://`
- Verifique se o certificado é válido: ícone de cadeado no navegador
- Alguns navegadores móveis exigem permissão de câmera por site; toque em "Permitir"

**WebSocket desconectando:**
- O Caddy preserva o header `Upgrade` por padrão. Verifique os logs do backend para erros de handshake.
