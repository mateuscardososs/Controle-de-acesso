ALTER TABLE access_events
    ADD COLUMN IF NOT EXISTS event_category VARCHAR(60),
    ADD COLUMN IF NOT EXISTS recognition_status VARCHAR(60),
    ADD COLUMN IF NOT EXISTS passage_status VARCHAR(60),
    ADD COLUMN IF NOT EXISTS release_method VARCHAR(60),
    ADD COLUMN IF NOT EXISTS operator_user_id UUID,
    ADD COLUMN IF NOT EXISTS manual_reason TEXT,
    ADD COLUMN IF NOT EXISTS controller_method VARCHAR(120),
    ADD COLUMN IF NOT EXISTS controller_door VARCHAR(80),
    ADD COLUMN IF NOT EXISTS controller_reader_id VARCHAR(80),
    ADD COLUMN IF NOT EXISTS controller_rec_no VARCHAR(120),
    ADD COLUMN IF NOT EXISTS decision_reason TEXT,
    ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMP WITH TIME ZONE;

UPDATE access_events
SET occurred_at = COALESCE(occurred_at, event_time)
WHERE occurred_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_access_events_event_time_device_id ON access_events(event_time DESC, device_id);
CREATE INDEX IF NOT EXISTS idx_access_events_access_result_event_time ON access_events(access_result, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_access_events_event_type_event_time ON access_events(event_type, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_access_events_person_cpf ON access_events(person_cpf);
CREATE INDEX IF NOT EXISTS idx_access_events_person_name ON access_events(person_name);
CREATE INDEX IF NOT EXISTS idx_access_events_origin ON access_events(origin);
CREATE INDEX IF NOT EXISTS idx_access_events_occurred_at ON access_events(occurred_at DESC);
CREATE INDEX IF NOT EXISTS idx_access_events_release_method ON access_events(release_method);
CREATE INDEX IF NOT EXISTS idx_access_events_raw_payload_gin ON access_events USING GIN(raw_payload);
