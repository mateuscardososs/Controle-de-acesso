-- Reverte a área secundária por dispositivo (modelagem correta: device tem 1 área física)
DROP INDEX IF EXISTS idx_devices_secondary_area_id;
ALTER TABLE devices DROP COLUMN IF EXISTS secondary_area_id;

-- N:N de áreas permitidas por convidado/visitante
CREATE TABLE IF NOT EXISTS guest_allowed_areas (
    guest_id UUID NOT NULL REFERENCES guests(id) ON DELETE CASCADE,
    area_id  UUID NOT NULL REFERENCES areas(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (guest_id, area_id)
);

CREATE INDEX IF NOT EXISTS idx_guest_allowed_areas_guest ON guest_allowed_areas(guest_id);
CREATE INDEX IF NOT EXISTS idx_guest_allowed_areas_area  ON guest_allowed_areas(area_id);

-- N:N de áreas permitidas por colaborador (regra: colaborador recebe TODAS as áreas)
CREATE TABLE IF NOT EXISTS employee_allowed_areas (
    employee_id UUID NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    area_id     UUID NOT NULL REFERENCES areas(id),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    PRIMARY KEY (employee_id, area_id)
);

CREATE INDEX IF NOT EXISTS idx_employee_allowed_areas_employee ON employee_allowed_areas(employee_id);
CREATE INDEX IF NOT EXISTS idx_employee_allowed_areas_area     ON employee_allowed_areas(area_id);

-- Backfill convidados: a área com o mesmo nome do invited_lounge (case-insensitive, trim)
INSERT INTO guest_allowed_areas (guest_id, area_id)
SELECT g.id, a.id
FROM guests g
JOIN areas a
  ON LOWER(TRIM(a.name)) = LOWER(TRIM(g.invited_lounge))
WHERE g.invited_lounge IS NOT NULL AND TRIM(g.invited_lounge) <> ''
ON CONFLICT DO NOTHING;

-- Backfill convidados: garantir Portaria para todo convidado com lounge atribuído
INSERT INTO guest_allowed_areas (guest_id, area_id)
SELECT g.id, a.id
FROM guests g
CROSS JOIN areas a
WHERE LOWER(TRIM(a.name)) = 'portaria'
  AND g.invited_lounge IS NOT NULL AND TRIM(g.invited_lounge) <> ''
ON CONFLICT DO NOTHING;

-- Backfill colaboradores: TODAS as áreas
INSERT INTO employee_allowed_areas (employee_id, area_id)
SELECT e.id, a.id FROM employees e CROSS JOIN areas a
ON CONFLICT DO NOTHING;
