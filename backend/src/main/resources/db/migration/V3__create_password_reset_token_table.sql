-- V3__create_password_reset_token_table.sql
-- Tạo bảng lưu trữ token khôi phục mật khẩu qua link xác nhận

CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    token VARCHAR(255) NOT NULL UNIQUE,
    email VARCHAR(255) NOT NULL,
    expired_at TIMESTAMPTZ NOT NULL,
    is_used BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- Index tối ưu hóa việc tìm kiếm và xác thực token theo email và trạng thái
CREATE INDEX idx_password_reset_tokens_token ON password_reset_tokens(token);
CREATE INDEX idx_password_reset_tokens_email_used ON password_reset_tokens(email, is_used);
