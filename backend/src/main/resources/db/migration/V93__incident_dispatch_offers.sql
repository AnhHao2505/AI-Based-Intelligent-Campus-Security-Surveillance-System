-- ============================================================================
-- V93: phân phối sự cố theo đợt PR-2 (K3, K4, K6)
--   1. security_incidents.dispatch_stage (STEP1 / BROADCAST), mặc định BROADCAST: sự cố cũ và mọi sự cố
--      tạo khi DISPATCH_ENABLED = false giữ nguyên hành vi phát cảnh báo toàn bộ (BR-DSP-01)
--   2. security_incidents.broadcast_at: lúc phát cảnh báo toàn bộ; sự cố cũ để NULL
--   3. security_incidents.subject_user_code: mã người bị camera nhận diện (lấy từ details khi ingest), dùng để
--      loại người đó khỏi danh sách mời (BR-DSP-02)
--   4. incident_dispatch_offers: lời mời bước 1 (BR-DSP-04..08, BR-DSP-10)
-- Chỉ thêm: không đổi dữ liệu hay hành vi đang có.
-- ============================================================================

-- 1–3. Cột mới trên sự cố
ALTER TABLE security_incidents ADD COLUMN IF NOT EXISTS dispatch_stage VARCHAR(12) NOT NULL DEFAULT 'BROADCAST';
ALTER TABLE security_incidents ADD CONSTRAINT chk_security_incidents_dispatch_stage
    CHECK (dispatch_stage IN ('STEP1', 'BROADCAST'));
ALTER TABLE security_incidents ADD COLUMN IF NOT EXISTS broadcast_at TIMESTAMPTZ;
ALTER TABLE security_incidents ADD COLUMN IF NOT EXISTS subject_user_code VARCHAR(50);

-- Job hết hạn chỉ quét sự cố đang ở bước 1
CREATE INDEX IF NOT EXISTS idx_security_incidents_step1
    ON security_incidents (id) WHERE dispatch_stage = 'STEP1';

-- 4. Lời mời: mỗi bảo vệ tối đa một lời mời cho một sự cố
CREATE TABLE incident_dispatch_offers (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id   UUID        NOT NULL REFERENCES security_incidents(id),
    guard_id      UUID        NOT NULL REFERENCES users(id),
    offered_at    TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL,
    response      VARCHAR(10),
    responded_at  TIMESTAMPTZ,
    cancel_reason VARCHAR(30),
    CONSTRAINT uq_incident_dispatch_offers UNIQUE (incident_id, guard_id),
    CONSTRAINT chk_incident_dispatch_offers_response
        CHECK (response IS NULL OR response IN ('ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT chk_incident_dispatch_offers_cancel_reason
        CHECK (cancel_reason IS NULL OR cancel_reason IN ('CLAIMED_BY_OTHER', 'SHIFT_ENDED', 'DISCONNECTED', 'INCIDENT_CLOSED')),
    CONSTRAINT chk_incident_dispatch_offers_expiry CHECK (expires_at > offered_at)
);

-- Lời mời còn chờ (response IS NULL): job quét theo hạn, lọc ứng viên theo bảo vệ
CREATE INDEX idx_incident_dispatch_offers_pending_expiry
    ON incident_dispatch_offers (expires_at) WHERE response IS NULL;
CREATE INDEX idx_incident_dispatch_offers_pending_guard
    ON incident_dispatch_offers (guard_id) WHERE response IS NULL;
CREATE INDEX idx_incident_dispatch_offers_incident
    ON incident_dispatch_offers (incident_id);
