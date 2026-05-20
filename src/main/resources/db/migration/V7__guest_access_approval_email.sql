ALTER TABLE guests
    ADD COLUMN access_approved_email_sent_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN access_approved_email_status VARCHAR(30),
    ADD COLUMN access_approved_email_message TEXT;
