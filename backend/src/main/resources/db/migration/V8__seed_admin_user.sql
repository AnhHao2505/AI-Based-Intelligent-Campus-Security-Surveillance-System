-- V5__seed_admin_user.sql
-- Seed a default administrator account for local development/testing.
-- Login: admin@campus.edu.vn
-- Password: Admin@123

INSERT INTO users (full_name, user_code, role, email, password, is_active, created_at, updated_at)
SELECT 'System Administrator', 'ADM-001', 'ADMIN', 'admin@campus.edu.vn', '$2b$12$3.8832isJeGhS5HMP.5DzuRFaSaqUwi/ng2V9dV9Cog.jIqxHHpye', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
    SELECT 1
    FROM users
    WHERE email = 'admin@campus.edu.vn' OR user_code = 'ADM-001'
);
