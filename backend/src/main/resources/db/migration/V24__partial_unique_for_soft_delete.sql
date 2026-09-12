-- Migration V22: Drop hard unique constraints and replace with partial unique indexes for soft delete support (BR-MF1-03)

ALTER TABLE users DROP CONSTRAINT IF EXISTS users_staff_code_key;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;

-- Partial unique index on UPPER(user_code) for active users
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_active_user_code_upper
ON users (UPPER(user_code))
WHERE deleted_at IS NULL;

-- Partial unique index on LOWER(email) for active users
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_active_email_lower
ON users (LOWER(email))
WHERE deleted_at IS NULL;
