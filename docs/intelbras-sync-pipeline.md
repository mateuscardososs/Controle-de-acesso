# Intelbras Sync Pipeline

Pipeline profissional de sincronização preparado para a controladora Intelbras real, usando provider fake nesta fase.

## Arquitetura

O visitante ou colaborador nunca interage diretamente com a Intelbras. O backend valida, armazena, audita e enfileira a sincronização.

Fluxo:

1. Colaborador criado/alterado ou visitante conclui cadastro facial.
2. Entidade recebe `syncStatus=PENDING_SYNC`.
3. Um evento `EmployeeReadyForSyncEvent` ou `GuestReadyForSyncEvent` e publicado.
4. O listener envia `IntelbrasSyncMessage` para RabbitMQ.
5. `IntelbrasSyncWorker` consome `intelbras.sync.queue`.
6. Worker chama `AccessControlProvider`.
7. Status transita para `SYNCING` e depois `SYNCED` ou `SYNC_FAILED`.
8. Auditoria, métricas e realtime são publicados.

## Status

- `NOT_REQUIRED`
- `PENDING_SYNC`
- `SYNCING`
- `SYNCED`
- `SYNC_FAILED`

Campos em `employees` e `guests`:

- `sync_status`
- `last_sync_at`
- `last_sync_error`
- `sync_attempts`

## Filas

- `intelbras.sync.queue`
- `intelbras.sync.dlq`

Exchange:

- `integration.events`

Routing key:

- `intelbras.sync.requested`

## Retry e DLQ

`INTELBRAS_SYNC_MAX_ATTEMPTS` controla o maximo de tentativas. O padrão e `3`.

Falhas antes do limite reenfileiram o evento com `attempt+1`. Ao exceder o limite, o worker lança rejeição sem requeue para a DLQ.

## Provider fake

`IntelbrasProvider` simula:

- latência
- sucesso
- falha ocasional
- sucesso parcial

Retorno:

- `ProviderSyncResult`
- `ProviderSyncStatus`

## Realtime

Eventos de sync são publicados em:

- `/topic/integration-sync`
- `/topic/system-alerts` para falhas finais

## Métricas Prometheus

- `intelbras.sync.success.count`
- `intelbras.sync.failed.count`
- `intelbras.sync.retry.count`
- `intelbras.sync.latency`

## Retry manual

Endpoint:

```http
POST /api/integration/retry/{type}/{id}
```

Permissões:

- `ADMIN`
- `HR`

Exemplo:

```bash
curl -X POST http://localhost:8080/api/integration/retry/guest/$GUEST_ID \
  -H "Authorization: Bearer $TOKEN"
```

## Futuro Intelbras real

Substituir o provider fake por client real mantendo:

- `AccessControlProvider`
- eventos de domínio
- worker
- auditoria
- métricas
- retry/DLQ
