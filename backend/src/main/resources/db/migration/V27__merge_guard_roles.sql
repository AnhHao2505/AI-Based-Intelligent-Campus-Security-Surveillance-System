-- Merge internal and outsourced guards into one GUARD role.
-- Drop the old constraint first: it still forbids 'GUARD' at this point,
-- so the UPDATE below would fail while it is in place.
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_role;

UPDATE users
SET role = 'GUARD'
WHERE role IN ('INTERNAL_GUARD', 'OUTSOURCED_GUARD');

ALTER TABLE users ADD CONSTRAINT chk_users_role
    CHECK (role IN ('ADMIN', 'FACILITY_MANAGER', 'GUARD', 'NORMAL_USER'));