-- Normaliza valores legados/inválidos de sync_status que não correspondem ao enum SyncStatus atual.
-- Causa do bug: linhas com sync_status='PENDING' (valor antigo, hoje 'PENDING_SYNC') quebravam a
-- hidratação JPA (@Enumerated STRING -> "No enum constant ... PENDING"), derrubando GET /api/guests
-- e a importação ao consultar um CPF existente. Valores válidos:
-- NOT_REQUIRED, PENDING_SYNC, SYNCING, SYNCED_WITH_WARNINGS, SYNCED, SYNC_FAILED.

UPDATE guests
SET sync_status = 'PENDING_SYNC'
WHERE sync_status NOT IN ('NOT_REQUIRED', 'PENDING_SYNC', 'SYNCING', 'SYNCED_WITH_WARNINGS', 'SYNCED', 'SYNC_FAILED');

UPDATE employees
SET sync_status = 'PENDING_SYNC'
WHERE sync_status NOT IN ('NOT_REQUIRED', 'PENDING_SYNC', 'SYNCING', 'SYNCED_WITH_WARNINGS', 'SYNCED', 'SYNC_FAILED');
