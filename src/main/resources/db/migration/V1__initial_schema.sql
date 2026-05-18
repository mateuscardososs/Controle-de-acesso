CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(150) NOT NULL,
    email VARCHAR(150) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(40) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE employees (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name VARCHAR(150) NOT NULL,
    cpf VARCHAR(14) NOT NULL UNIQUE,
    email VARCHAR(150),
    phone VARCHAR(30),
    registration_number VARCHAR(80),
    face_photo_url TEXT,
    status VARCHAR(30) NOT NULL,
    access_valid_from TIMESTAMP WITH TIME ZONE,
    access_valid_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE guests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name VARCHAR(150) NOT NULL,
    cpf VARCHAR(14) NOT NULL,
    email VARCHAR(150),
    face_photo_url TEXT,
    visit_start TIMESTAMP WITH TIME ZONE NOT NULL,
    visit_end TIMESTAMP WITH TIME ZONE NOT NULL,
    status VARCHAR(30) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE areas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    description TEXT,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(120) NOT NULL,
    model VARCHAR(120),
    serial_number VARCHAR(120),
    ip_address VARCHAR(45) NOT NULL,
    location VARCHAR(180),
    operation_type VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    area_id UUID NOT NULL REFERENCES areas(id),
    last_seen_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE access_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    person_type VARCHAR(30) NOT NULL,
    person_id UUID NOT NULL,
    area_id UUID NOT NULL REFERENCES areas(id),
    valid_from TIMESTAMP WITH TIME ZONE,
    valid_until TIMESTAMP WITH TIME ZONE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE access_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    person_type VARCHAR(30) NOT NULL,
    person_id UUID NOT NULL,
    device_id UUID NOT NULL REFERENCES devices(id),
    area_id UUID NOT NULL REFERENCES areas(id),
    event_type VARCHAR(40) NOT NULL,
    access_result VARCHAR(30) NOT NULL,
    event_time TIMESTAMP WITH TIME ZONE NOT NULL,
    origin VARCHAR(60) NOT NULL,
    raw_payload JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id UUID,
    action VARCHAR(120) NOT NULL,
    entity_type VARCHAR(80) NOT NULL,
    entity_id UUID,
    details JSONB,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_devices_area_id ON devices(area_id);
CREATE INDEX idx_access_permissions_person ON access_permissions(person_type, person_id);
CREATE INDEX idx_access_permissions_area_id ON access_permissions(area_id);
CREATE INDEX idx_access_events_event_time ON access_events(event_time);
CREATE INDEX idx_access_events_person ON access_events(person_type, person_id);
