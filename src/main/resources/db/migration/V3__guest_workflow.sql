ALTER TABLE guests
    ADD COLUMN phone VARCHAR(30),
    ADD COLUMN company VARCHAR(150),
    ADD COLUMN visit_reason VARCHAR(255),
    ADD COLUMN host_name VARCHAR(150),
    ADD COLUMN invited_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN completed_at TIMESTAMP WITH TIME ZONE;

UPDATE guests
SET invited_at = COALESCE(created_at, NOW())
WHERE invited_at IS NULL;

CREATE TABLE guest_invites (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    guest_id UUID NOT NULL REFERENCES guests(id),
    token VARCHAR(120) NOT NULL UNIQUE,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    used_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_guest_invites_token ON guest_invites(token);
CREATE INDEX idx_guest_invites_guest_id ON guest_invites(guest_id);
CREATE INDEX idx_guests_status ON guests(status);
CREATE INDEX idx_guests_visit_start ON guests(visit_start);
