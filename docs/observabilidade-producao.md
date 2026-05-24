# Observabilidade de producao local

Este guia descreve a stack de observabilidade para o ambiente local de producao do controle de acesso.

## Servicos

O `docker-compose.prod.yml` sobe:

- `prometheus`: coleta metricas da API, PostgreSQL e do proprio Prometheus.
- `grafana`: dashboards provisionados automaticamente.
- `postgres-exporter`: status, conexoes e metricas do PostgreSQL.

Os servicos de observabilidade ficam na rede Docker interna. Apenas Prometheus e Grafana possuem porta local exposta para operacao.

Nota operacional: o `node-exporter` esta desativado nesta composicao Mac/local para evitar target inexistente no Prometheus. Em servidor Linux, reative depois adicionando o servico no Compose e o job correspondente no `infra/prometheus/prometheus.yml` no mesmo deploy.

Portas padrao:

- Grafana: `127.0.0.1:3001`
- Prometheus: `127.0.0.1:9090`
- Nginx/aplicacao: `HTTP_PORT`, por padrao `80`

## Variaveis

Configure no `.env.production`:

```bash
PROMETHEUS_PORT=127.0.0.1:9090
PROMETHEUS_RETENTION_TIME=30d

GRAFANA_PORT=127.0.0.1:3001
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=<senha-forte-unica>
```

O `postgres-exporter` usa as mesmas variaveis do PostgreSQL:

```bash
POSTGRES_DB=access_control
POSTGRES_USER=access_user
POSTGRES_PASSWORD=<senha-forte>
```

Se for necessario usar DSN unico, mantenha documentado no `.env.production`, por exemplo:

```bash
POSTGRES_EXPORTER_DATA_SOURCE_NAME=postgresql://access_user:<senha>@postgres:5432/access_control?sslmode=disable
```

Por padrao o Compose usa `DATA_SOURCE_URI`, `DATA_SOURCE_USER` e `DATA_SOURCE_PASS`, evitando duplicar a senha em mais uma URL.

## Subir a stack

Validar configuracao:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml config
```

Subir:

```bash
./scripts/prod-up.sh
```

Ou diretamente:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml up -d --build
```

## Acessar Grafana

Abra:

```text
http://127.0.0.1:3001
```

Login:

- Usuario: valor de `GRAFANA_ADMIN_USER`
- Senha: valor de `GRAFANA_ADMIN_PASSWORD`

O datasource `Prometheus`, o dashboard `Controle de Acesso - Produção Local` e os alertas de operacao sao provisionados automaticamente.

## Validar Prometheus

Abra:

```text
http://127.0.0.1:9090/targets
```

Targets esperados como `UP`:

- `prometheus`
- `access-control-api`
- `postgres-exporter`

Consulta rapida:

```promql
up
```

Backend:

```promql
up{job="access-control-api"}
```

PostgreSQL:

```promql
up{job="postgres-exporter"}
```

## Validar Actuator

O endpoint de metricas do backend fica disponivel dentro da rede Docker para o Prometheus:

```text
http://backend:8080/actuator/prometheus
```

Para testar de fora, use um container na rede do Compose:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml exec prometheus \
  wget -qO- http://backend:8080/actuator/prometheus | head
```

Healthcheck do backend:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml exec prometheus \
  wget -qO- http://backend:8080/actuator/health
```

## Seguranca

- O Nginx nao possui rota para `/actuator/prometheus`.
- O backend nao publica porta no host em `docker-compose.prod.yml`; Prometheus coleta via rede interna Docker.
- Prometheus e Grafana ficam bindados em `127.0.0.1` por padrao. Para expor em rede, altere `PROMETHEUS_PORT` e `GRAFANA_PORT` conscientemente.
- Troque `GRAFANA_ADMIN_PASSWORD` antes de subir em producao.
- Nao coloque senhas reais em arquivos versionados.

## Dashboard inicial

O dashboard provisionado cobre:

- backend up/down;
- requisicoes por minuto;
- erros HTTP 5xx por minuto;
- latencia HTTP;
- memoria JVM;
- PostgreSQL up/down;
- conexoes e transacoes PostgreSQL;
- status dos targets Prometheus.
- acessos por minuto;
- liberados vs negados;
- erros de acesso;
- falhas de reconhecimento;
- passagens confirmadas/nao confirmadas;
- liberacoes manuais;
- falhas de comunicacao por controladora;
- status online/offline das controladoras;
- latencia de comunicacao com controladoras.
- cards operacionais de controladoras offline, falhas Intelbras em 5 minutos, acessos negados em 5 minutos e targets Prometheus down.

## Alertas provisionados

Os alertas ficam em `infra/grafana/provisioning/alerting` e sao carregados pelo Grafana no boot. O contact point `Operacao` usa o email definido em `GRAFANA_ALERT_EMAIL_TO`. Se a variavel nao estiver configurada, o fallback `operacao-alertas@example.invalid` e usado e as notificacoes nao chegam; configure antes do evento.

Regras criadas:

- Backend down: `1 - max(up{job="access-control-api"}) > 0` por 1 minuto. Critico.
- PostgreSQL down: `1 - max(pg_up) > 0` por 1 minuto. Critico.
- Controladora offline: `sum(controller_online_status == bool 0) > 0` por 1 minuto. Critico.
- Falhas de comunicacao Intelbras: `sum(increase(controller_communication_failures_total[5m])) > 3`. Warning.
- Erros HTTP 5xx: `sum(increase(http_server_requests_seconds_count{job="access-control-api", status=~"5.."}[5m])) > 5`. Warning.
- Alto numero de acessos negados: `sum(increase(access_events_denied_total[5m])) > 20`. Warning.
- Prometheus target down: `sum(1 - up) > 0` por 1 minuto. Critico.

Backup atrasado ainda nao tem alerta provisionado porque hoje o sucesso do backup fica em arquivo (`backups/latest-success.txt`), nao em metrica Prometheus. Ate existir um exporter ou script que publique essa idade como metrica, valide pelo checklist operacional:

```bash
cat backups/latest-success.txt
tail -n 100 backups/backup-cron.log
```

## Configurar alertas por email (Gmail SMTP)

O `docker-compose.prod.yml` ja repassa as variaveis SMTP para o Grafana. Para ativar:

1. Crie uma App Password no Google (nao use a senha normal da conta):
   - Conta Google > Seguranca > Verificacao em 2 etapas (ativar se nao estiver).
   - Seguranca > Pesquisar "senhas de aplicativo" > Outro (nome personalizado) > "Grafana Consuma Catraca" > Gerar.
   - Copie a senha de 16 caracteres sem espacos.

2. Preencha no `.env.production` (nao versionar):

```env
GRAFANA_SMTP_ENABLED=true
GRAFANA_SMTP_USER=seu-email@gmail.com
GRAFANA_SMTP_PASSWORD=abcdefghijklmnop
GRAFANA_SMTP_FROM_ADDRESS=seu-email@gmail.com
GRAFANA_ALERT_EMAIL_TO=destino-alertas@empresa.local
```

3. Reinicie o Grafana:

```bash
docker compose --env-file .env.production -f docker-compose.prod.yml restart grafana
```

4. Teste em Grafana > Alerting > Contact points > Operacao > Test.

O contact point `Operacao` usa `${GRAFANA_ALERT_EMAIL_TO}` e ja aponta para a politica de notificacao padrao. Nenhuma alteracao em arquivos versionados e necessaria.

## Testar alertas

Validar arquivos e subir observabilidade:

```bash
docker compose -f docker-compose.prod.yml config
docker compose --env-file .env.production -f docker-compose.prod.yml up -d prometheus grafana
docker compose --env-file .env.production -f docker-compose.prod.yml logs --tail=100 grafana
```

No Grafana, abra:

```text
Alerting > Alert rules
```

Teste operacional simples:

- Backend down: pare o backend por mais de 1 minuto e confira `Backend down`.
- PostgreSQL down: pare o PostgreSQL apenas em janela controlada e confira `PostgreSQL down`.
- Target down: pare `postgres-exporter` ou backend e confira `Prometheus target down`.
- Controladora offline/falhas Intelbras: desligue a controladora ou bloqueie a comunicacao em teste controlado.
- Erros 5xx e negados: gere carga de teste em ambiente de homologacao, nao durante entrada ativa.

## Silenciar alertas

Durante manutencao planejada:

1. Acesse `Alerting > Silences`.
2. Crie silence por labels, por exemplo `component=backend` ou `severity=warning`.
3. Defina janela curta e motivo claro.
4. Remova o silence ao fim da manutencao se ainda estiver ativo.

## Metricas customizadas

As metricas customizadas sao agregadas e nao usam dados pessoais como tag. Nunca adicione CPF, nome, email, foto, biometria, token ou `raw_payload` em tags do Prometheus. Tags devem ser de baixa cardinalidade; `device_id`, `device_name` e `controller_ip` sao aceitaveis neste ambiente porque ha poucas controladoras fixas.

Counters de eventos de acesso:

- `access_events_total`: eventos de acesso salvos.
- `access_events_allowed_total`: acessos liberados.
- `access_events_denied_total`: acessos negados.
- `access_events_error_total`: eventos de acesso com erro.
- `manual_admin_release_total`: liberacoes manuais administrativas.
- `recognition_success_total`: reconhecimentos bem-sucedidos.
- `recognition_failure_total`: falhas de reconhecimento.
- `passage_confirmed_total`: passagens confirmadas.
- `passage_not_confirmed_total`: passagens nao confirmadas.

Metricas de controladoras:

- `controller_online_status`: gauge `1` online e `0` offline.
- `controller_communication_failures_total`: falhas de comunicacao com controladoras.
- `controller_last_success_timestamp`: timestamp Unix da ultima comunicacao bem-sucedida.
- `controller_request_duration_seconds`: histograma de latencia das chamadas para controladoras.

Tags permitidas nas metricas de acesso:

- `result`
- `event_type`
- `origin`
- `release_method`
- `device_id`
- `device_name`

Tags permitidas nas metricas de controladora:

- `device_id`
- `device_name`
- `controller_ip`
- `operation`
- `result`

## Consultas PromQL uteis

Acessos por minuto:

```promql
sum(rate(access_events_total[1m])) * 60
```

Liberados vs negados:

```promql
sum(rate(access_events_allowed_total[5m])) * 60
sum(rate(access_events_denied_total[5m])) * 60
```

Falhas de reconhecimento por minuto:

```promql
sum(rate(recognition_failure_total[5m])) * 60
```

Liberacoes manuais por minuto:

```promql
sum(rate(manual_admin_release_total[5m])) * 60
```

Controladoras offline:

```promql
controller_online_status == bool 0
```

Falhas de comunicacao por controladora:

```promql
sum(rate(controller_communication_failures_total[5m])) by (device_name) * 60
```

Latencia p95 de comunicacao:

```promql
histogram_quantile(0.95, sum(rate(controller_request_duration_seconds_bucket[5m])) by (le, device_name, operation))
```

## Alertas recomendados

- Backend down: `up{job="access-control-api"} == 0` por 1 minuto.
- PostgreSQL down: `up{job="postgres-exporter"} == 0` por 1 minuto.
- Controladora offline: `controller_online_status == bool 0` por 2 minutos.
- Erro alto por minuto: `sum(rate(access_events_error_total[5m])) * 60 > 1`.
- Falhas consecutivas de reconhecimento: `sum(rate(recognition_failure_total[5m])) * 60 > 10`, ajustar conforme operacao real.
- Disco alto: monitorar pelo sistema operacional do servidor enquanto `node-exporter` estiver desativado.
- Backup atrasado: validar `backups/latest-success.txt` via checklist operacional. Ainda nao ha metrica Prometheus nativa para esse arquivo.
