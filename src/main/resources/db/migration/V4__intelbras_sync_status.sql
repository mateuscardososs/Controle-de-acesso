ALTER TABLE employees
    ADD COLUMN sync_status VARCHAR(30) NOT NULL DEFAULT 'PENDING_SYNC',
    ADD COLUMN last_sync_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_sync_error TEXT,
    ADD COLUMN sync_attempts INTEGER NOT NULL DEFAULT 0;

ALTER TABLE guests
    ADD COLUMN sync_status VARCHAR(30) NOT NULL DEFAULT 'NOT_REQUIRED',
    ADD COLUMN last_sync_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_sync_error TEXT,
    ADD COLUMN sync_attempts INTEGER NOT NULL DEFAULT 0;

CREATE INDEX idx_employees_sync_status ON employees(sync_status);
CREATE INDEX idx_guests_sync_status ON guests(sync_status);
