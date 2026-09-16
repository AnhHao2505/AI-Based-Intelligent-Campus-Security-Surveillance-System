-- Merge internal and outsourced guards into one GUARD role.
UPDATE users
SET role = 'GUARD'
WHERE role IN ('INTERNAL_GUARD', 'OUTSOURCED_GUARD');

ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_role;
ALTER TABLE users ADD CONSTRAINT chk_users_role
    CHECK (role IN ('ADMIN', 'FACILITY_MANAGER', 'GUARD', 'NORMAL_USER'));