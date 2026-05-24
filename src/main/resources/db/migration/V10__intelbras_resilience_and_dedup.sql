ALTER TABLE devices
    ADD COLUMN IF NOT EXISTS last_success_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS last_failure_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN IF NOT EXISTS last_error TEXT;

CREATE UNIQUE INDEX IF NOT EXISTS ux_access_events_intelbras_rec_no
    ON access_events(device_id, origin, controller_rec_no)
    WHERE controller_rec_no IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_access_events_intelbras_natural_key
    ON access_events(device_id, origin, event_time, external_user_id, controller_door, controller_method)
    WHERE controller_rec_no IS NULL
      AND external_user_id IS NOT NULL
      AND controller_door IS NOT NULL
      AND controller_method IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_devices_online_status ON devices(online_status);
CREATE INDEX IF NOT EXISTS idx_devices_last_failure_at ON devices(last_failure_at DESC);
