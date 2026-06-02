-- Armazena o CardNo aleatório único enviado à Intelbras (independente do CPF).
-- O CardNo derivado de CPF (CPF[0:10]) causava 400 nas controladoras quando dois
-- usuários compartilhavam os mesmos 10 primeiros dígitos. Agora é gerado aleatoriamente
-- pelo IntelbrasCardNoGenerator e persistido aqui para garantir unicidade global.
-- O índice é parcial (WHERE NOT NULL) pois NULL não viola UNIQUE no PostgreSQL.

ALTER TABLE guests
    ADD COLUMN IF NOT EXISTS intelbras_card_no VARCHAR(10);

ALTER TABLE employees
    ADD COLUMN IF NOT EXISTS intelbras_card_no VARCHAR(10);

CREATE UNIQUE INDEX IF NOT EXISTS idx_guests_intelbras_card_no
    ON guests (intelbras_card_no)
    WHERE intelbras_card_no IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_employees_intelbras_card_no
    ON employees (intelbras_card_no)
    WHERE intelbras_card_no IS NOT NULL;
