# WebSocket Realtime

O backend expõe STOMP em:

- `ws://localhost:8080/ws`

Tópicos:

- `/topic/access-events`
- `/topic/device-status`
- `/topic/system-alerts`

## Access events

Eventos criados por `/api/access-events/simulate` ou `/api/simulator/access-event` publicam um payload rico em `/topic/access-events`:

```json
{
  "id": "uuid",
  "personType": "EMPLOYEE",
  "personId": "uuid",
  "personName": "Leao da Ilha",
  "personCpf": "00000000000",
  "deviceId": "uuid",
  "deviceName": "Catraca Social 01",
  "areaId": "uuid",
  "areaName": "Ilha do Retiro - Social",
  "eventType": "ENTRY",
  "accessResult": "ALLOWED",
  "eventTime": "2026-05-18T20:00:00Z",
  "origin": "INTELBRAS_SIMULATOR",
  "createdAt": "2026-05-18T20:00:01Z"
}
```

Campos enriquecidos podem vir como `null` quando a pessoa, dispositivo ou area nao puderem ser resolvidos. O frontend continua compativel com o payload legado `{ "accessEventId": "uuid" }`.

## Device status

Mudancas de status publicam em `/topic/device-status`:

```json
{
  "deviceId": "uuid",
  "deviceName": "Catraca Social 01",
  "status": "ONLINE",
  "lastSeenAt": "2026-05-18T20:00:00Z",
  "lastHeartbeatAt": "2026-05-18T20:00:00Z",
  "communicationFailures": 0,
  "message": "Device status changed"
}
```

## System alerts

Alertas operacionais publicam em `/topic/system-alerts`:

```json
{
  "id": "uuid",
  "severity": "WARNING",
  "title": "Dispositivo offline",
  "message": "Dispositivo Catraca Social 01 atingiu o limite de falhas de comunicacao.",
  "source": "device-health",
  "createdAt": "2026-05-18T20:00:00Z"
}
```

Severidades: `INFO`, `WARNING`, `ERROR`, `CRITICAL`.

## Como testar

1. Suba o backend em `localhost:8080`.
2. Suba o frontend em `localhost:3000`.
3. Faça login como `ADMIN` ou `HR`.
4. Acesse `/operations`.
5. Use o simulador visual ou envie:

```bash
curl -X POST http://localhost:8080/api/simulator/access-event \
  -H 'Content-Type: application/json' \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"cpf":"00000000000","deviceId":"UUID_DO_DISPOSITIVO","eventType":"ENTRY","result":"ALLOWED"}'
```

O evento deve aparecer em `/topic/access-events` com os campos enriquecidos quando disponiveis.
