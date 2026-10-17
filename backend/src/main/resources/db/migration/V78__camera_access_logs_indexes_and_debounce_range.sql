-- ============================================================================
-- V78: Index cho lịch sử ra vào + thu hẹp khoảng hợp lệ của ENTRY_LOG_DEBOUNCE_SECONDS
-- ============================================================================
-- V77 đã áp trên dev + test nên không sửa; mọi thay đổi đi qua file này.
-- (user_id, logged_at DESC): GET /api/access-history/my lọc theo người, sắp xếp mới nhất trước.
-- (logged_at): lọc khoảng thời gian của API tổng và job dọn nhật ký (DELETE ... WHERE logged_at < cutoff).

CREATE INDEX IF NOT EXISTS idx_access_logs_user_time ON camera_access_logs (user_id, logged_at DESC);
CREATE INDEX IF NOT EXISTS idx_access_logs_logged_at ON camera_access_logs (logged_at);

-- Debounce 0 (ghi mọi lượt) bị loại: khoảng hợp lệ mới 5–300 giây. Giá trị mặc định 30 nằm trong khoảng.
UPDATE system_configurations
SET min_value = 5,
    max_value = 300,
    description = '[Nhật ký ra vào] Cùng một người đi qua cùng một camera trong bao nhiêu giây thì chỉ ghi một lượt'
WHERE config_key = 'ENTRY_LOG_DEBOUNCE_SECONDS';
