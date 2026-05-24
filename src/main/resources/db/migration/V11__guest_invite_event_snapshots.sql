ALTER TABLE guests
    ADD COLUMN IF NOT EXISTS invited_day DATE,
    ADD COLUMN IF NOT EXISTS invited_lounge VARCHAR(80);

UPDATE guests
SET invited_day = COALESCE(invited_day, (visit_start AT TIME ZONE 'America/Recife')::date)
WHERE invited_day IS NULL
  AND visit_start IS NOT NULL;

ALTER TABLE access_events
    ADD COLUMN IF NOT EXISTS person_email VARCHAR(150),
    ADD COLUMN IF NOT EXISTS person_phone VARCHAR(30),
    ADD COLUMN IF NOT EXISTS invited_day DATE,
    ADD COLUMN IF NOT EXISTS invited_lounge VARCHAR(80);

UPDATE access_events event
SET person_email = COALESCE(event.person_email, guest.email),
    person_phone = COALESCE(event.person_phone, guest.phone),
    invited_day = COALESCE(event.invited_day, guest.invited_day),
    invited_lounge = COALESCE(event.invited_lounge, guest.invited_lounge)
FROM guests guest
WHERE event.person_type = 'GUEST'
  AND event.person_id = guest.id;

CREATE INDEX IF NOT EXISTS idx_guests_invited_day ON guests(invited_day);
CREATE INDEX IF NOT EXISTS idx_guests_invited_lounge ON guests(invited_lounge);
CREATE INDEX IF NOT EXISTS idx_access_events_invited_day ON access_events(invited_day);
CREATE INDEX IF NOT EXISTS idx_access_events_invited_lounge ON access_events(invited_lounge);
