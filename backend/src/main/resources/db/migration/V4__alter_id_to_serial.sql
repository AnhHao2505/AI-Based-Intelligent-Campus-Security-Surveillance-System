-- V4__alter_id_to_serial.sql
-- Alter tables users and password_reset_tokens to change id from UUID to auto-incrementing integer (SERIAL)

-- 1. Drop existing primary key constraints
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_pkey;
ALTER TABLE password_reset_tokens DROP CONSTRAINT IF EXISTS password_reset_tokens_pkey;

-- 2. Drop existing UUID columns
ALTER TABLE users DROP COLUMN IF EXISTS id;
ALTER TABLE password_reset_tokens DROP COLUMN IF EXISTS id;

-- 3. Add new SERIAL primary key columns
ALTER TABLE users ADD COLUMN id SERIAL PRIMARY KEY;
ALTER TABLE password_reset_tokens ADD COLUMN id SERIAL PRIMARY KEY;
