-- ============================================================================
-- FLYWAY MIGRATION V5: CREATE FACE_DATA & CAMERA MANAGEMENT TABLES (FA26SE040)
-- PostgreSQL 16 + pgvector Extension
-- ============================================================================

-- 1. Enable Required Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "vector";

-- 2. Table: face_data (Dataset khuôn mặt phục vụ AI nhận diện & đối soát)
CREATE TABLE IF NOT EXISTS face_data (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL, -- MSSV hoặc MSNV
    full_name VARCHAR(100) NOT NULL,
    image_front_url VARCHAR(512) NOT NULL,
    image_left_url VARCHAR(512) NOT NULL,
    image_right_url VARCHAR(512) NOT NULL,
    embedding_front vector(512) NOT NULL,
    embedding_left vector(512) NOT NULL,
    embedding_right vector(512) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_face_data_code ON face_data(code);
CREATE INDEX idx_face_data_full_name ON face_data(full_name);

-- 3. Table: cameras (Quản lý danh mục Camera cốt lõi)
CREATE TABLE IF NOT EXISTS cameras (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_code VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | DECOMMISSIONED
    operational_status VARCHAR(50) NOT NULL DEFAULT 'OFFLINE', -- ONLINE | OFFLINE | ERROR
    installed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

CREATE INDEX idx_cameras_code ON cameras(camera_code);

-- 4. Table: camera_specifications (Thông số kỹ thuật phần cứng - 1:1)
CREATE TABLE IF NOT EXISTS camera_specifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_id UUID NOT NULL UNIQUE REFERENCES cameras(id) ON DELETE CASCADE,
    manufacturer VARCHAR(100),
    model VARCHAR(100),
    serial_number VARCHAR(100),
    resolution VARCHAR(50),
    fps INT,
    lens VARCHAR(100),
    focal_length VARCHAR(50),
    field_of_view DECIMAL(5, 2),
    night_vision BOOLEAN DEFAULT FALSE,
    ptz_supported BOOLEAN DEFAULT FALSE,
    weather_proof BOOLEAN DEFAULT FALSE,
    firmware_version VARCHAR(50)
);

-- 5. Table: camera_locations (Vị trí không gian & Tọa độ bản đồ - 1:1)
CREATE TABLE IF NOT EXISTS camera_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_id UUID NOT NULL UNIQUE REFERENCES cameras(id) ON DELETE CASCADE,
    floor INT NOT NULL,
    zone_id UUID,
    x DECIMAL(8, 2),
    y DECIMAL(8, 2),
    mounting_height DECIMAL(5, 2),
    orientation DECIMAL(5, 2),
    tilt_angle DECIMAL(5, 2)
);

-- 6. Table: camera_stream_configurations (Cấu hình luồng Video RTSP/RTMP - 1:1)
CREATE TABLE IF NOT EXISTS camera_stream_configurations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_id UUID NOT NULL UNIQUE REFERENCES cameras(id) ON DELETE CASCADE,
    protocol VARCHAR(50) NOT NULL DEFAULT 'RTSP',
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL DEFAULT 554,
    username VARCHAR(100),
    credential_ref VARCHAR(255),
    main_stream_url VARCHAR(512) NOT NULL,
    sub_stream_url VARCHAR(512),
    stream_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reconnect_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    timeout_ms INT NOT NULL DEFAULT 5000
);

-- 7. Table: camera_ai_configurations (Cấu hình tham số AI theo từng Camera - 1:1)
CREATE TABLE IF NOT EXISTS camera_ai_configurations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_id UUID NOT NULL UNIQUE REFERENCES cameras(id) ON DELETE CASCADE,
    person_detection_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    face_recognition_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    loitering_detection_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    detection_confidence DECIMAL(4, 3) NOT NULL DEFAULT 0.500,
    face_match_threshold DECIMAL(4, 3) NOT NULL DEFAULT 0.600,
    loitering_threshold_seconds INT NOT NULL DEFAULT 10,
    inference_fps INT NOT NULL DEFAULT 10,
    model_version VARCHAR(50),
    roi_coordinates JSONB
);

-- 8. Table: camera_health_logs (Nhật ký sức khỏe Heartbeat camera - 1:N)
CREATE TABLE IF NOT EXISTS camera_health_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_id UUID NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    latency_ms INT,
    fps DECIMAL(5, 2),
    error_code VARCHAR(100),
    error_message VARCHAR(255)
);

CREATE INDEX idx_camera_health_logs_cam_time ON camera_health_logs (camera_id, checked_at DESC);
