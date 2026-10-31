-- Migration V90: Bảng lưu trữ metadata và vòng đời video ghi hình & bằng chứng sự cố (Cloud-Edge Architecture)
CREATE TABLE IF NOT EXISTS camera_recordings (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_id UUID REFERENCES cameras(id) ON DELETE SET NULL,
    camera_code VARCHAR(50) NOT NULL,
    incident_id UUID REFERENCES security_incidents(id) ON DELETE SET NULL,
    recording_type VARCHAR(30) NOT NULL,
    storage_location VARCHAR(20) NOT NULL,
    storage_bucket VARCHAR(100),
    storage_key VARCHAR(500) NOT NULL UNIQUE,
    filename VARCHAR(255) NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    duration_seconds INTEGER NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    resolution VARCHAR(30) NOT NULL DEFAULT '854x480',
    fps DOUBLE PRECISION NOT NULL DEFAULT 15.0,
    checksum_sha256 VARCHAR(64),
    status VARCHAR(20) NOT NULL DEFAULT 'READY',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_camera_recordings_cam_start
    ON camera_recordings(camera_code, start_time DESC);

CREATE INDEX IF NOT EXISTS idx_camera_recordings_type_start
    ON camera_recordings(recording_type, start_time DESC);

CREATE INDEX IF NOT EXISTS idx_camera_recordings_incident
    ON camera_recordings(incident_id);
