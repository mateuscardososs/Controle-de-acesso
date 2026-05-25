ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS card_no VARCHAR(80);

CREATE INDEX IF NOT EXISTS idx_employees_card_no ON employees(card_no);
