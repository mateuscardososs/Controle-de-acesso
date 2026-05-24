# Backup e Restore em Producao Local

Este procedimento cobre PostgreSQL, uploads de faces, logs tecnicos do backend/Nginx e manifesto operacional. Execute no servidor local do evento, a partir da raiz do projeto.

## Onde os dados ficam

- PostgreSQL: volume Docker `postgres_data`.
- Faces/uploads: volume Docker `uploads_faces`, montado no backend em `/app/uploads/faces`.
- Logs do backend: volume Docker `backend_logs`, montado no backend em `/app/logs`.
- Logs do Nginx: volume Docker `nginx_logs`, montado em `/var/log/nginx`.
- Prometheus: volume Docker `prometheus_data`.
- Grafana: volume Docker `grafana_data`.
- Backups: diretorio do host definido por `BACKUP_DIR`, por padrao `./backups`, e copia adicional no volume Docker `backups_data`.

## Retencao de logs

O backend grava logs JSON em `/app/logs/access-control-api.log` com rotacao por data e tamanho.

Padroes de producao:

- `LOG_MAX_FILE_SIZE=50MB`
- `LOG_MAX_HISTORY=90`
- `LOG_TOTAL_SIZE_CAP=10GB`

Os logs tecnicos nao substituem os eventos de acesso no banco. Eventos de pessoas, auditoria administrativa e logs tecnicos devem ser tratados como trilhas separadas.

## Backup manual

```bash
./scripts/backup.sh manual
```

Sem argumento, o script faz backup `hourly`:

```bash
./scripts/backup.sh
```

O backup cria:

- `backups/runs/<timestamp>/db/postgres.dump`
- `backups/runs/<timestamp>/files/uploads-faces.tar.gz`
- `backups/runs/<timestamp>/logs/technical-logs.tar.gz`
- `backups/runs/<timestamp>/manifest.txt`
- `backups/latest-success.txt`

Tambem mantem copias organizadas em:

- `backups/db/hourly`
- `backups/db/daily`
- `backups/db/manual`
- `backups/files`
- `backups/logs`
- `backups/manifests`

## Agendamento recomendado no host

Use cron no servidor local. E simples, visivel para a equipe e nao depende de um container extra de scheduler.

Instalacao assistida:

```bash
./scripts/install-backup-cron.sh
```

O script detecta o caminho atual do projeto, evita entradas duplicadas e mostra o crontab final. Para instalar manualmente, use:

```cron
0 * * * * cd /caminho/consuma_catraca && ./scripts/backup.sh hourly >> /caminho/consuma_catraca/backups/backup-cron.log 2>&1
15 2 * * * cd /caminho/consuma_catraca && ./scripts/backup.sh daily >> /caminho/consuma_catraca/backups/backup-cron.log 2>&1
```

Nota Mac vs Linux: no macOS o cron do usuario nao roda automaticamente quando o sistema hiberna. Em servidor Linux (Ubuntu/Debian), o cron do usuario `deployer` roda continuamente; use o mesmo script de instalacao sem alteracoes.

Retencao padrao:

- backups horarios: 14 dias;
- backups diarios: 90 dias;
- backups manuais: 90 dias.

Configure em `.env.production`:

```env
BACKUP_DIR=./backups
BACKUP_HOURLY_RETENTION_DAYS=14
BACKUP_DAILY_RETENTION_DAYS=90
BACKUP_MANUAL_RETENTION_DAYS=90
APP_ENVIRONMENT=production-local
```

## Verificar ultimo backup bem-sucedido

```bash
cat backups/latest-success.txt
cat backups/manifests/latest-success.txt
tail -n 100 backups/backup-cron.log
```

Valide tambem o tamanho dos arquivos:

```bash
du -h backups/runs/*/db/postgres.dump | tail
du -h backups/runs/*/files/uploads-faces.tar.gz | tail
```

## Restore em emergencia

Pare a operacao antes de restaurar. O script para backend, frontend e Nginx, recria o banco e restaura uploads de faces quando o arquivo existir.

```bash
./scripts/restore.sh backups/runs/<timestamp>
```

O script exige confirmacao literal:

```text
RESTAURAR access_control
```

Depois do restore:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml ps
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=100 backend
```

Abra a aplicacao, confira login, dashboard e a pagina de eventos de acesso.

## Teste de restore em ambiente temporario

1. Copie `docker-compose.prod.yml`, `.env.production` e o diretorio de backup para uma maquina ou pasta de teste.
2. Altere `COMPOSE_PROJECT_NAME` para evitar colisao com producao, por exemplo `access-control-restore-test`.
3. Suba apenas PostgreSQL:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml up -d postgres
```

4. Execute restore no backup escolhido:

```bash
./scripts/restore.sh backups/runs/<timestamp>
```

5. Valide contagens basicas:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml exec -T postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select count(*) from access_events;"
```

## Liberar espaco com seguranca

Nunca apague `postgres_data` ou `uploads_faces` para liberar espaco. Para backups antigos, prefira ajustar as variaveis de retencao e executar:

```bash
./scripts/backup.sh manual
```

Depois remova apenas diretorios antigos dentro de `backups/runs`, `backups/files`, `backups/logs`, `backups/manifests` ou dumps antigos em `backups/db/*`.

## Antes de atualizar o sistema

1. Execute `./scripts/backup.sh manual`.
2. Confirme `backups/latest-success.txt`.
3. Guarde o timestamp do backup.
4. Rode `docker compose -f docker-compose.prod.yml config`.
5. Atualize a aplicacao.
6. Valide login, dashboard, eventos de acesso e manual release.

## Checklist diario

- `cat backups/latest-success.txt` mostra backup do dia.
- `du -h backups/runs/<ultimo>/db/postgres.dump` tem tamanho plausivel.
- `docker compose --env-file .env.production -f docker-compose.prod.yml ps` sem containers reiniciando em loop.
- Grafana mostra backend e PostgreSQL como ativos.
- `docker compose --env-file .env.production -f docker-compose.prod.yml exec backend printenv TZ APP_TIMEZONE` mostra `America/Recife`.
- Disco com espaco livre suficiente para pelo menos 7 dias de operacao.

## Recarregar Nginx sem downtime

Apos alterar `nginx.conf` ou certificados, recarregue sem derrubar conexoes:

```bash
./scripts/reload-nginx.sh
```

O script verifica se o container `consuma_catraca-nginx-1` esta rodando e executa `nginx -s reload` dentro dele.
