-- V5__create_camera_tables.sql
-- Create camera-related tables: cameras, camera_specifications, camera_stream_configurations, camera_ai_configurations, camera_health_logs with UUID primary keys

CREATE TABLE IF NOT EXISTS cameras (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    floor INT,
    zone_name VARCHAR(255),
    x DECIMAL(15, 6),
    y DECIMAL(15, 6),
    mounting_height DECIMAL(5, 2),
    orientation DECIMAL(5, 2),
    tilt_angle DECIMAL(5, 2),
    status VARCHAR(50) NOT NULL,
    operational_status VARCHAR(50) NOT NULL,
    installed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT chk_camera_status CHECK (status IN ('ACTIVE', 'DECOMMISSIONED')),
    CONSTRAINT chk_camera_operational_status CHECK (operational_status IN ('ONLINE', 'OFFLINE', 'ERROR'))
);

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
    night_vision BOOLEAN,
    ptz_supported BOOLEAN,
    weather_proof BOOLEAN,
    firmware_version VARCHAR(50)
);

CREATE TABLE IF NOT EXISTS camera_stream_configurations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_id UUID NOT NULL UNIQUE REFERENCES cameras(id) ON DELETE CASCADE,
    protocol VARCHAR(50) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL,
    username VARCHAR(100),
    credential_ref VARCHAR(255),
    main_stream_url VARCHAR(512) NOT NULL,
    sub_stream_url VARCHAR(512),
    stream_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    reconnect_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    timeout_ms INT NOT NULL DEFAULT 5000,
    CONSTRAINT chk_camera_stream_protocol CHECK (protocol IN ('RTSP', 'RTMP', 'HTTP', 'HTTPS'))
);

CREATE TABLE IF NOT EXISTS camera_ai_configurations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_id UUID NOT NULL UNIQUE REFERENCES cameras(id) ON DELETE CASCADE,
    person_detection_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    face_recognition_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    loitering_detection_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    face_match_threshold DECIMAL(3, 2) NOT NULL,
    loitering_threshold_seconds INT NOT NULL,
    inference_fps INT NOT NULL,
    model_version VARCHAR(50)
);

CREATE TABLE IF NOT EXISTS camera_health_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_id UUID NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
    status VARCHAR(50) NOT NULL,
    checked_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    latency_ms INT,
    fps DECIMAL(5, 2),
    error_code VARCHAR(100),
    error_message VARCHAR(255),
    CONSTRAINT chk_camera_health_status CHECK (status IN ('ONLINE', 'OFFLINE', 'ERROR'))
);

-- Indexes for performance
CREATE INDEX idx_cameras_code ON cameras(camera_code);
CREATE INDEX idx_camera_health_logs_camera_checked ON camera_health_logs(camera_id, checked_at);
