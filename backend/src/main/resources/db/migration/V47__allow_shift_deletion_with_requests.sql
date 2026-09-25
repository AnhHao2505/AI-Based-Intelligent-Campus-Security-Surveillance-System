-- V47: Allow guard shifts to be deleted even if there is an associated shift request
-- (shift_id becomes NULL on delete, preserving audit trail with snapshot columns)

ALTER TABLE guard_shift_requests ALTER COLUMN shift_id DROP NOT NULL;

DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN (
        SELECT constraint_name
        FROM information_schema.table_constraints
        WHERE table_name = 'guard_shift_requests'
          AND constraint_type = 'FOREIGN KEY'
          AND constraint_name LIKE '%shift_id%'
    ) LOOP
        EXECUTE 'ALTER TABLE guard_shift_requests DROP CONSTRAINT IF EXISTS ' || quote_ident(r.constraint_name);
    END LOOP;
END $$;

ALTER TABLE guard_shift_requests
    ADD CONSTRAINT fk_guard_shift_requests_shift
    FOREIGN KEY (shift_id) REFERENCES guard_shifts(id) ON DELETE SET NULL;
