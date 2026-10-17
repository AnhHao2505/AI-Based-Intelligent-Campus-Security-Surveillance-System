-- ============================================================================
-- V74: Tạo bảng camera_access_logs để lưu vết nhật ký ra vào tại camera
-- ============================================================================

CREATE TABLE camera_access_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_code VARCHAR(50) NOT NULL,
    area_id UUID NOT NULL REFERENCES areas(id),
    user_id UUID REFERENCES users(id),
    user_code VARCHAR(50),
    full_name VARCHAR(150),
    user_access_level INT,
    access_result VARCHAR(30) NOT NULL,
    access_source VARCHAR(30),
    source_reference_id UUID,
    reason_details TEXT,
    logged_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_access_logs_camera_time ON camera_access_logs (camera_code, logged_at DESC);
CREATE INDEX idx_access_logs_result ON camera_access_logs (access_result);
CREATE INDEX idx_access_logs_user ON camera_access_logs (user_code);
