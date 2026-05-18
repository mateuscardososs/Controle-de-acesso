ALTER TABLE audit_logs
    ADD COLUMN actor_ip VARCHAR(45),
    ADD COLUMN old_data JSONB,
    ADD COLUMN new_data JSONB,
    ADD COLUMN correlation_id VARCHAR(80);

ALTER TABLE devices
    ADD COLUMN last_heartbeat_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN communication_failures INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN online_status VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN';

CREATE INDEX idx_audit_logs_correlation_id ON audit_logs(correlation_id);
CREATE INDEX idx_devices_online_status ON devices(online_status);
