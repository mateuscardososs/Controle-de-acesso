# Windows Server com Docker Desktop e WSL2

Este guia cobre a operacao do `consuma_catraca` em Windows Server usando PowerShell, Docker Desktop com backend WSL2 e a arquitetura Docker ja existente em `docker-compose.prod.yml`.

Nota de suporte: a documentacao atual da Docker informa que Docker Desktop nao e suportado em versoes Server do Windows, como Windows Server 2019 ou 2022. Use este procedimento somente quando a operacao Docker Desktop + WSL2 em Windows Server ja tiver sido aceita pela equipe/cliente. Para instalacao de WSL em Windows Server, siga a documentacao da Microsoft.

Referencias oficiais:

- Docker Desktop no Windows: <https://docs.docker.com/desktop/setup/install/windows-install/>
- Docker Desktop com WSL2: <https://docs.docker.com/desktop/features/wsl/>
- WSL no Windows Server: <https://learn.microsoft.com/windows/wsl/install-on-server>

## 1. Preparar Windows Server

Abra PowerShell como Administrador.

Em Windows Server 2022/2025 com Desktop Experience:

```powershell
wsl.exe --install
Restart-Computer
```

Em Windows Server 2019 ou Server Core, habilite os recursos manualmente e reinicie:

```powershell
Enable-WindowsOptionalFeature -Online -FeatureName Microsoft-Windows-Subsystem-Linux,VirtualMachinePlatform
Restart-Computer
```

Apos reiniciar:

```powershell
wsl.exe --set-default-version 2
wsl.exe -l -v
```

Confirme que a distribuicao padrao aparece com `VERSION 2`.

## 2. Instalar Docker Desktop

1. Baixe o instalador oficial do Docker Desktop para Windows.
2. Durante a instalacao, selecione o backend WSL2.
3. Abra Docker Desktop pelo menu Iniciar.
4. Em Settings > General, confirme `Use the WSL 2 based engine`.
5. Em Settings > Resources > WSL Integration, habilite a distribuicao Linux usada pela operacao quando aplicavel.
6. Confirme no PowerShell:

```powershell
docker version
docker compose version
```

Se o usuario operacional nao conseguir usar Docker, adicione-o ao grupo `docker-users` e faca logoff/logon:

```powershell
net localgroup docker-users "<USUARIO>" /add
```

## 3. Copiar o projeto

Crie um diretorio operacional simples, sem espacos no caminho:

```powershell
New-Item -ItemType Directory -Force C:\consuma_catraca
```

Copie o projeto inteiro para `C:\consuma_catraca`, preservando:

- `docker-compose.prod.yml`
- `Dockerfile`
- `frontend\Dockerfile`
- `nginx.conf`
- `scripts\*.ps1`
- `src\`
- `frontend\`
- `infra\`

Entre na raiz:

```powershell
cd C:\consuma_catraca
```

## 4. Criar `.env.production`

Crie `C:\consuma_catraca\.env.production` com valores reais. Exemplo minimo:

```env
COMPOSE_PROJECT_NAME=consuma_catraca
APP_ENVIRONMENT=production-windows-server
TZ=America/Recife
APP_TIMEZONE=America/Recife

POSTGRES_DB=access_control
POSTGRES_USER=access_user
POSTGRES_PASSWORD=troque-esta-senha

RABBITMQ_DEFAULT_USER=access_user
RABBITMQ_DEFAULT_PASS=troque-esta-senha

JWT_SECRET=troque-por-um-segredo-longo-com-mais-de-32-caracteres
PUBLIC_BASE_URL=http://localhost
NEXT_PUBLIC_API_URL=
NEXT_PUBLIC_WS_URL=/ws
NEXT_PUBLIC_SIMULATOR_ENABLED=false

HTTP_PORT=80
PROMETHEUS_PORT=127.0.0.1:9090
GRAFANA_PORT=127.0.0.1:3001
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=troque-esta-senha

APP_INTELBRAS_MODE=real
APP_INTELBRAS_DEFAULT_USERNAME=admin
APP_INTELBRAS_DEFAULT_PASSWORD=

BACKUP_DIR=.\backups
BACKUP_HOURLY_RETENTION_DAYS=14
BACKUP_DAILY_RETENTION_DAYS=90
BACKUP_MANUAL_RETENTION_DAYS=90
```

Para criar o primeiro admin em producao, habilite temporariamente:

```env
APP_SEED_ADMIN_ENABLED=true
APP_SEED_ADMIN_EMAIL=admin@empresa.local
APP_SEED_ADMIN_NAME=Administrador
APP_SEED_ADMIN_PASSWORD=troque-esta-senha-admin
```

Suba o backend uma vez, confirme login, depois volte `APP_SEED_ADMIN_ENABLED=false` e reinicie o backend.

## 5. Subir a stack

Valide a composicao:

```powershell
docker compose --env-file .env.production -f docker-compose.prod.yml config
```

Suba com build:

```powershell
docker compose --env-file .env.production -f docker-compose.prod.yml up -d --build
```

Acompanhe:

```powershell
docker compose --env-file .env.production -f docker-compose.prod.yml ps
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=100 backend
```

## 6. Validar healthcheck

Valide os containers:

```powershell
docker compose --env-file .env.production -f docker-compose.prod.yml ps
```

Valide HTTP pelo Nginx:

```powershell
Invoke-WebRequest http://localhost/nginx-health -UseBasicParsing
Invoke-WebRequest http://localhost/api/health -UseBasicParsing
```

Valide backend por dentro da rede Docker, se necessario:

```powershell
docker compose --env-file .env.production -f docker-compose.prod.yml exec backend curl -fsS http://localhost:8080/api/health
```

## 7. Validar usuario admin

O script lista apenas dados operacionais do admin. Ele nao consulta nem exibe `password_hash`, senha ou hash.

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\scripts\check-admin-user.ps1
```

## 8. Validar backup manual

Execute:

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\scripts\backup.ps1 -Scope manual
```

O backup deve criar:

- `backups\runs\<timestamp>\db\postgres.dump`
- `backups\runs\<timestamp>\files\uploads-faces.tar.gz`
- `backups\runs\<timestamp>\logs\technical-logs.tar.gz`
- `backups\runs\<timestamp>\manifest.txt`
- `backups\latest-success.txt`
- `backups\backup-task.log`

Valide:

```powershell
Get-Content .\backups\latest-success.txt
Get-ChildItem .\backups\runs -Directory | Sort-Object Name -Descending | Select-Object -First 5
Get-Content .\backups\backup-task.log -Tail 100
```

## 9. Configurar Task Scheduler

Crie duas tarefas: backup horario e backup diario.

Acao do backup horario:

- Program/script: `powershell.exe`
- Add arguments: `-NoProfile -ExecutionPolicy Bypass -File "C:\consuma_catraca\scripts\backup.ps1" -Scope hourly`
- Start in: `C:\consuma_catraca`

Agendamento recomendado:

- Horario: diariamente, repetir a cada 1 hora, duracao indefinida.
- Diario: diariamente as 02:15 com `-Scope daily`.

Marque:

- Run whether user is logged on or not.
- Run with highest privileges.
- Stop the task if it runs longer than 2 hours.
- If the task fails, restart every 10 minutes, attempt 3 times.

Teste manualmente no Task Scheduler com `Run`, depois confira:

```powershell
Get-Content C:\consuma_catraca\backups\backup-task.log -Tail 100
Get-Content C:\consuma_catraca\backups\latest-success.txt
```

## 10. Recarregar Nginx

Apos alterar `nginx.conf`, recarregue sem derrubar a stack:

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\scripts\reload-nginx.ps1
```

Se o projeto usar outro `COMPOSE_PROJECT_NAME`, o script detecta pelo `.env.production`. Tambem e possivel informar o container:

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\scripts\reload-nginx.ps1 -ContainerName consuma_catraca-nginx-1
```

## 11. Reiniciar containers

Reiniciar somente backend:

```powershell
docker compose --env-file .env.production -f docker-compose.prod.yml restart backend
```

Reiniciar Nginx:

```powershell
docker compose --env-file .env.production -f docker-compose.prod.yml restart nginx
```

Recriar a stack apos atualizacao:

```powershell
docker compose --env-file .env.production -f docker-compose.prod.yml up -d --build
docker compose --env-file .env.production -f docker-compose.prod.yml ps
```

Parar a stack:

```powershell
docker compose --env-file .env.production -f docker-compose.prod.yml down
```

Nao remova volumes Docker em operacao normal. Eles armazenam PostgreSQL, uploads, logs, Prometheus, Grafana e a copia interna de backups.

## 12. Checklist rapido

- `docker compose --env-file .env.production -f docker-compose.prod.yml config` sem erro.
- `docker compose --env-file .env.production -f docker-compose.prod.yml ps` sem restart loop.
- `Invoke-WebRequest http://localhost/nginx-health -UseBasicParsing` responde OK.
- `Invoke-WebRequest http://localhost/api/health -UseBasicParsing` responde OK.
- `powershell.exe -ExecutionPolicy Bypass -File .\scripts\check-admin-user.ps1` lista admins sem hash/senha.
- `powershell.exe -ExecutionPolicy Bypass -File .\scripts\backup.ps1 -Scope manual` cria `backups\runs`.
- Task Scheduler atualiza `backups\latest-success.txt`.
