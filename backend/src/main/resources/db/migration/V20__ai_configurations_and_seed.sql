-- ============================================================================
-- FLYWAY MIGRATION V20: AI CONFIGURATIONS, FACE_DATA USER LINK, & SEED DATA
-- ============================================================================

-- 1. Remove obsolete camera_ai_configurations table
DROP TABLE IF EXISTS camera_ai_configurations CASCADE;

-- 2. Drop obsolete columns (protocol from stream config & ptz_supported from specs)
ALTER TABLE camera_stream_configurations DROP COLUMN IF EXISTS protocol;
ALTER TABLE camera_specifications DROP COLUMN IF EXISTS ptz_supported;

-- 3. Create global ai_configurations table (Singleton)
CREATE TABLE IF NOT EXISTS ai_configurations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    face_match_threshold DECIMAL(3, 2) NOT NULL DEFAULT 0.75,
    inference_fps INT NOT NULL DEFAULT 15,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- Seed initial global AI configuration if empty
INSERT INTO ai_configurations (face_match_threshold, inference_fps)
SELECT 0.75, 15
WHERE NOT EXISTS (SELECT 1 FROM ai_configurations);

-- 4. Add 1-1 relationship between face_data and users
ALTER TABLE face_data ADD COLUMN IF NOT EXISTS user_id UUID UNIQUE REFERENCES users(id) ON DELETE SET NULL;

-- 5. Seed 1 NORMAL_USER
INSERT INTO users (full_name, user_code, role, email, password, is_active, created_at, updated_at)
VALUES 
    ('Phạm Minh Tuấn (SV)', 'SV-001', 'NORMAL_USER', 'student.tuan@fpt.edu.vn', '$2a$10$9hN/LUMwb.SHa8gRAbRmaOBMkM/qzZ8i4PZMIHig/6QYZEqsuWc5.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (email) DO UPDATE 
SET 
    full_name = EXCLUDED.full_name,
    user_code = EXCLUDED.user_code,
    role = EXCLUDED.role,
    is_active = EXCLUDED.is_active,
    updated_at = CURRENT_TIMESTAMP;

-- 6. Seed 4 Cameras
INSERT INTO cameras (camera_code, name, status, operational_status, created_at, updated_at)
VALUES
    ('CAM-001', 'Camera Cổng Chính', 'ACTIVE', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CAM-002', 'Camera Sảnh A', 'ACTIVE', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CAM-003', 'Camera Thư Viện', 'ACTIVE', 'OFFLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('CAM-004', 'Camera Bãi Xe', 'ACTIVE', 'ONLINE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (camera_code) DO UPDATE
SET
    name = EXCLUDED.name,
    status = EXCLUDED.status,
    operational_status = EXCLUDED.operational_status,
    updated_at = CURRENT_TIMESTAMP;

-- Insert default stream configurations for seeded cameras if not exists
INSERT INTO camera_stream_configurations (camera_id, host, port, main_stream_path, timeout_ms)
SELECT c.id, '192.168.1.101', 554, 'rtsp://admin:123456@192.168.1.101:554/stream1', 5000
FROM cameras c WHERE c.camera_code = 'CAM-001'
ON CONFLICT (camera_id) DO NOTHING;

INSERT INTO camera_stream_configurations (camera_id, host, port, main_stream_path, timeout_ms)
SELECT c.id, '192.168.1.102', 554, 'rtsp://admin:123456@192.168.1.102:554/stream1', 5000
FROM cameras c WHERE c.camera_code = 'CAM-002'
ON CONFLICT (camera_id) DO NOTHING;

INSERT INTO camera_stream_configurations (camera_id, host, port, main_stream_path, timeout_ms)
SELECT c.id, '192.168.1.103', 554, 'rtsp://admin:123456@192.168.1.103:554/stream1', 5000
FROM cameras c WHERE c.camera_code = 'CAM-003'
ON CONFLICT (camera_id) DO NOTHING;

INSERT INTO camera_stream_configurations (camera_id, host, port, main_stream_path, timeout_ms)
SELECT c.id, '192.168.1.104', 554, 'rtsp://admin:123456@192.168.1.104:554/stream1', 5000
FROM cameras c WHERE c.camera_code = 'CAM-004'
ON CONFLICT (camera_id) DO NOTHING;
