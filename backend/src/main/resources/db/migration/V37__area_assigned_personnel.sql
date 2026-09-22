-- ============================================================================
-- V37: Area Assigned Personnel
-- Facility Manager gán người dùng vào một khu vực hạn chế; người được gán
-- ra vào khu vực đó mà không cần tạo đơn access_requests.
-- Một bảng phủ hai trường hợp:
--   (1) Ra vào thường ngày: valid_to = NULL (không thời hạn) hoặc ngày xa.
--   (2) Ra vào một lần do FM chủ động cấp: khung valid_from - valid_to ngắn.
-- Thu hồi là xoá mềm (điền revoked_*), không xoá cứng, để giữ vết.
-- Rule chống chồng lấn thời gian được kiểm tra ở tầng service, không ở DB.
-- ============================================================================

CREATE TABLE IF NOT EXISTS area_assigned_personnel (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    area_id       UUID NOT NULL REFERENCES areas(id) ON DELETE CASCADE,
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    valid_from    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    valid_to      TIMESTAMPTZ,                                   -- NULL = không thời hạn
    note          TEXT,                                          -- lý do cấp
    revoked_at    TIMESTAMPTZ,                                   -- NULL = chưa thu hồi
    revoked_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    revoke_reason TEXT,
    created_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_area_assigned_personnel_validity
        CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT chk_area_assigned_personnel_revoke_reason
        CHECK (revoked_at IS NULL OR (revoke_reason IS NOT NULL AND length(trim(revoke_reason)) > 0))
);

CREATE INDEX IF NOT EXISTS idx_area_assigned_personnel_area_user
    ON area_assigned_personnel (area_id, user_id);

CREATE INDEX IF NOT EXISTS idx_area_assigned_personnel_user
    ON area_assigned_personnel (user_id);

-- Bản ghi chưa thu hồi; việc còn trong khung valid_from/valid_to kiểm ở query
CREATE INDEX IF NOT EXISTS idx_area_assigned_personnel_not_revoked
    ON area_assigned_personnel (area_id, user_id)
    WHERE revoked_at IS NULL;
