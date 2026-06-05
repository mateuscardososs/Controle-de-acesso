-- Performance indexes for analytics aggregation queries
CREATE INDEX IF NOT EXISTS idx_ae_event_time_desc        ON access_events (event_time DESC);
CREATE INDEX IF NOT EXISTS idx_ae_device_event_time      ON access_events (device_id, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_ae_area_event_time        ON access_events (area_id, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_ae_result_event_time      ON access_events (access_result, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_ae_type_event_time        ON access_events (event_type, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_ae_person_type_event_time ON access_events (person_type, event_time DESC);
CREATE INDEX IF NOT EXISTS idx_ae_person_id_event_time   ON access_events (person_id, event_time DESC) WHERE person_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_ae_release_event_time     ON access_events (release_method, event_time DESC) WHERE release_method IS NOT NULL;
