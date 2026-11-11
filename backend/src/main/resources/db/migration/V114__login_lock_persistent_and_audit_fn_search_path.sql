-- V114 (PR-2)
-- L3: khoá đăng nhập theo tài khoản lưu DB (sống qua restart, dùng chung khi chạy nhiều instance).
--   failed_login_count        : số lần sai trong cửa sổ hiện tại
--   failed_login_window_start : lần sai đầu tiên của cửa sổ (cửa sổ dài LOGIN_FAILED_WINDOW_MINUTES)
--   login_locked_until        : khoá tới thời điểm này; hết khoá khi login_locked_until <= now (không cần job dọn)
-- Chỉ ghi bằng UPDATE nguyên tử có điều kiện (UserLoginLockRepository); entity User chỉ đọc 3 cột này.
-- E-mail không có tài khoản không có dòng ở đây: vẫn đếm trong bộ nhớ như trước (LoginAttemptLimiter).
ALTER TABLE users
    ADD COLUMN failed_login_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN failed_login_window_start TIMESTAMPTZ NULL,
    ADD COLUMN login_locked_until TIMESTAMPTZ NULL;

-- L19: cố định search_path cho hàm trigger append-only của audit_logs (V55), tránh cảnh báo "function search_path mutable".
-- Thân hàm chỉ RAISE EXCEPTION, không tham chiếu bảng / hàm ngoài pg_catalog, public: không đổi thân hàm.
ALTER FUNCTION trg_prevent_audit_logs_modification() SET search_path = pg_catalog, public;
