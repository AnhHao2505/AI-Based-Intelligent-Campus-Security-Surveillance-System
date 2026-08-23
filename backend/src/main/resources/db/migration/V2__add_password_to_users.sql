-- V2__add_password_to_users.sql
-- Thêm cột password, updated_at và đổi tên staff_code thành user_code cho table users để hỗ trợ đăng nhập email + password giống ERD

ALTER TABLE users RENAME COLUMN staff_code TO user_code;
ALTER TABLE users ADD COLUMN password VARCHAR(60);
ALTER TABLE users ADD COLUMN updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP;
