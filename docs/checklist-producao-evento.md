# Checklist de Producao para Evento

Checklist rapido para operar o `consuma_catraca` no servidor local do evento sem alterar HTTP/HTTPS.

## Antes de abrir a entrada

1. Validar configuracao Docker:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml config
```

2. Confirmar timezone do backend:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml exec backend printenv TZ APP_TIMEZONE
docker compose --env-file .env.production -f docker-compose.prod.yml exec backend date
```

O esperado e `America/Recife`. A validade de convidado deve sair de `15:00` do `invitedDay` ate `04:00` do dia seguinte nesse timezone.

3. Confirmar usuario admin:

```bash
./scripts/check-admin-user.sh
```

Deve mostrar pelo menos um usuario com `role = ADMIN` e `active = true`. Se nao houver, crie via seed antes de abrir a entrada (ver instrucoes no script).

4. Confirmar credenciais Intelbras:

- `APP_INTELBRAS_DEFAULT_PASSWORD` deve ficar vazio por padrao.
- Cada controladora real deve ter senha propria cadastrada no sistema.
- Dispositivo sem senha deve falhar com: `Credenciais Intelbras não configuradas para este dispositivo.`

5. Confirmar backup:

```bash
cat backups/latest-success.txt
cat backups/manifests/latest-success.txt
tail -n 100 backups/backup-cron.log
```

6. Confirmar observabilidade:

- Prometheus sem target `node-exporter` nesta composicao Mac/local.
- Targets esperados: `prometheus`, `access-control-api`, `postgres-exporter`.
- Em servidor Linux, reative `node-exporter` apenas adicionando servico no Compose e job no Prometheus juntos.

7. Confirmar alertas Grafana (opcional, mas recomendado):

- Se `GRAFANA_SMTP_ENABLED=true` e `GRAFANA_ALERT_EMAIL_TO` estiver configurado, teste enviando um alerta manual pelo Grafana (`Alerting > Contact points > Test`).
- Se email nao estiver configurado, os alertas ficam visivos apenas no Grafana. Configure antes do evento se possivel (ver `docs/observabilidade-producao.md`).

## Instalar cron de backup

Preferencial:

```bash
./scripts/install-backup-cron.sh
```

Entrada manual equivalente:

```cron
0 * * * * cd /caminho/consuma_catraca && ./scripts/backup.sh hourly >> backups/backup-cron.log 2>&1
```

O instalador detecta o caminho atual, evita duplicidade e mostra o crontab final.

## Backup manual

Antes de atualizacao, manutencao ou restore:

```bash
./scripts/backup.sh manual
cat backups/latest-success.txt
```

## Restore seguro

1. Parar a operacao na entrada.
2. Separar o backup alvo em `backups/runs/<timestamp>`.
3. Executar:

```bash
./scripts/restore.sh backups/runs/<timestamp>
```

4. Confirmar com o texto literal pedido pelo script.
5. Validar login, dashboard, visitantes, colaboradores, logs e sync Intelbras.

Nunca apague volumes Docker para tentar restaurar dados. Use sempre `scripts/restore.sh`.

## Recarregar Nginx apos reinicio do backend

Quando o backend ou o frontend for reiniciado e o Nginx nao retomar sozinho:

```bash
./scripts/reload-nginx.sh
```

Nao derruba conexoes ativas. Se o container nao estiver rodando o script avisa com instrucao de recuperacao.
