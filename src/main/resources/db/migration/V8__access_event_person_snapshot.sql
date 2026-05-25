ALTER TABLE access_events
    ALTER COLUMN person_id DROP NOT NULL,
    ADD COLUMN person_name VARCHAR(150),
    ADD COLUMN person_cpf VARCHAR(14),
    ADD COLUMN external_user_id VARCHAR(120),
    ADD COLUMN raw_card_name VARCHAR(150);
