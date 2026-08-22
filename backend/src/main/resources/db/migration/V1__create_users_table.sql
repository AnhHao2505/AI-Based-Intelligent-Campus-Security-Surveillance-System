-- V1__create_users_table.sql
-- Chuyển đổi syntax bảng User sang syntax postgres

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name VARCHAR(100) NOT NULL,
    staff_code VARCHAR(50) NOT NULL UNIQUE,
    role VARCHAR(50) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

-- Index cho tìm kiếm nhanh bằng email
CREATE INDEX idx_users_email ON users(email);

-- Ràng buộc giá trị hợp lệ cho cột role
ALTER TABLE users ADD CONSTRAINT chk_users_role 
    CHECK (role IN ('ADMIN', 'FACILITY_MANAGER', 'INTERNAL_GUARD', 'OUTSOURCED_GUARD'));
