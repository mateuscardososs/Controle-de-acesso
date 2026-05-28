-- Área secundária opcional por dispositivo (até 2 áreas por device)
ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS secondary_area_id UUID REFERENCES areas(id);

CREATE INDEX IF NOT EXISTS idx_devices_secondary_area_id ON devices(secondary_area_id);

-- Campos de cooldown nos eventos de acesso (auditoria de bloqueio por intervalo mínimo)
ALTER TABLE access_events
    ADD COLUMN IF NOT EXISTS cooldown_blocked BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE access_events
    ADD COLUMN IF NOT EXISTS cooldown_reason VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_access_events_cooldown ON access_events(cooldown_blocked) WHERE cooldown_blocked = TRUE;
