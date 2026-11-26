-- V149: Add verification_status for timeout-fallback incidents (#2) and composite dedup index (#1)

ALTER TABLE security_incidents
    ADD COLUMN IF NOT EXISTS verification_status VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_security_incidents_cam_type_status_detected
    ON security_incidents (camera_code, event_type, status, detected_at DESC);
