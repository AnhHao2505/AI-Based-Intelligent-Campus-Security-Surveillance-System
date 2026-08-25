-- V6: Thêm cột OAuth cho bảng users
-- google_sub: Google Subject ID (unique per Google account)
-- last_login_at: Thời điểm đăng nhập gần nhất

ALTER TABLE users ADD COLUMN google_sub    VARCHAR(255);
ALTER TABLE users ADD COLUMN last_login_at TIMESTAMPTZ;

-- Index unique trên email (lowercase, chỉ user có thể login và chưa bị xóa)
CREATE UNIQUE INDEX ux_users_email_login ON users(lower(email))
  WHERE can_login = TRUE AND deleted_at IS NULL;

-- Index unique trên google_sub (chỉ khi không NULL)
CREATE UNIQUE INDEX ux_users_google_sub ON users(google_sub)
  WHERE google_sub IS NOT NULL;

-- Ràng buộc: nếu can_login = TRUE thì email phải có giá trị
ALTER TABLE users ADD CONSTRAINT ck_users_login_needs_email
  CHECK (can_login = FALSE OR email IS NOT NULL);
