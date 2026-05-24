ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS user_id UUID REFERENCES users(id),
    ADD COLUMN IF NOT EXISTS role VARCHAR(40);

CREATE INDEX IF NOT EXISTS idx_employees_user_id ON employees(user_id);
CREATE INDEX IF NOT EXISTS idx_employees_role ON employees(role);
