-- ============================================================================
-- V66: Guard real-time locations and campus geofence tracking
-- ============================================================================

-- 1. Bảng lưu trữ vị trí GPS ngầm của nhân viên bảo vệ
CREATE TABLE IF NOT EXISTS guard_locations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    guard_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    accuracy DOUBLE PRECISION,
    battery_level DOUBLE PRECISION,
    heading DOUBLE PRECISION,
    speed DOUBLE PRECISION,
    is_inside_geofence BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_guard_location UNIQUE (guard_id)
);

CREATE INDEX IF NOT EXISTS idx_guard_locations_geofence ON guard_locations(is_inside_geofence, updated_at);
CREATE INDEX IF NOT EXISTS idx_guard_locations_updated_at ON guard_locations(updated_at DESC);

-- 2. Bảng lưu trữ ranh giới Geofence duy nhất toàn khuôn viên trường do Admin vẽ/cập nhật
CREATE TABLE IF NOT EXISTS campus_geofences (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    campus_name VARCHAR(150) NOT NULL DEFAULT 'FPT University HCMC Campus',
    description TEXT,
    center_latitude DOUBLE PRECISION NOT NULL DEFAULT 10.84113,
    center_longitude DOUBLE PRECISION NOT NULL DEFAULT 106.80988,
    polygon_json TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by UUID REFERENCES users(id)
);

-- Seed ranh giới Geofence mặc định ban đầu
INSERT INTO campus_geofences (campus_name, description, center_latitude, center_longitude, polygon_json)
SELECT
    'FPT University HCMC Campus',
    'Khuôn viên Đại học FPT TP.HCM (Khu Công nghệ cao, TP. Thủ Đức)',
    10.84113,
    106.80988,
    '[{"latitude":10.84350,"longitude":106.80800},{"latitude":10.84380,"longitude":106.81050},{"latitude":10.84250,"longitude":106.81280},{"latitude":10.83920,"longitude":106.81220},{"latitude":10.83880,"longitude":106.80950},{"latitude":10.84020,"longitude":106.80720}]'
WHERE NOT EXISTS (SELECT 1 FROM campus_geofences LIMIT 1);

-- 3. Seed dữ liệu bảo vệ cho các kịch bản cảnh báo sự cố:
-- Kịch bản 1: Bảo vệ trong trường, GPS tươi mới (nhận cảnh báo)
-- Kịch bản 2: Bảo vệ ngoài trường (Bến Thành, Quận 1 - KHÔNG nhận cảnh báo)
-- Kịch bản 3: Bảo vệ trong trường nhưng GPS hết hạn > 10 phút (KHÔNG nhận cảnh báo)

INSERT INTO users (full_name, user_code, role, email, password, is_active, created_at, updated_at)
VALUES 
    ('Bảo Vệ Trong Ca (Demo)', 'SEC-002', 'GUARD', 'guard.demo@fpt.edu.vn', '$2a$10$9hN/LUMwb.SHa8gRAbRmaOBMkM/qzZ8i4PZMIHig/6QYZEqsuWc5.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Bảo Vệ Ngoài Trường', 'SEC-003', 'GUARD', 'guard.ngoai@fpt.edu.vn', '$2a$10$9hN/LUMwb.SHa8gRAbRmaOBMkM/qzZ8i4PZMIHig/6QYZEqsuWc5.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Bảo Vệ Hết Hạn GPS', 'SEC-004', 'GUARD', 'guard.stale@fpt.edu.vn', '$2a$10$9hN/LUMwb.SHa8gRAbRmaOBMkM/qzZ8i4PZMIHig/6QYZEqsuWc5.', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (LOWER(email)) WHERE deleted_at IS NULL DO UPDATE 
SET full_name = EXCLUDED.full_name,
    user_code = EXCLUDED.user_code,
    role = EXCLUDED.role,
    is_active = true,
    updated_at = CURRENT_TIMESTAMP;

-- Seed vị trí thực tế cho 3 kịch bản kiểm thử:
-- 1. guard.demo@fpt.edu.vn: Trong khuôn viên, GPS tươi mới
INSERT INTO guard_locations (guard_id, latitude, longitude, accuracy, battery_level, is_inside_geofence, updated_at)
SELECT id, 10.84150, 106.80950, 5.0, 92.0, true, CURRENT_TIMESTAMP
FROM users WHERE email = 'guard.demo@fpt.edu.vn'
ON CONFLICT (guard_id) DO UPDATE 
SET latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    is_inside_geofence = true,
    updated_at = CURRENT_TIMESTAMP;

-- 2. guard.ngoai@fpt.edu.vn: Ngoài khuôn viên (Đường D1, ngoài cổng trường FPT)
INSERT INTO guard_locations (guard_id, latitude, longitude, accuracy, battery_level, is_inside_geofence, updated_at)
SELECT id, 10.84190, 106.80800, 15.0, 78.0, false, CURRENT_TIMESTAMP
FROM users WHERE email = 'guard.ngoai@fpt.edu.vn'
ON CONFLICT (guard_id) DO UPDATE 
SET latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    is_inside_geofence = false,
    updated_at = CURRENT_TIMESTAMP;

-- 3. guard.stale@fpt.edu.vn: Tọa độ trong trường nhưng GPS cập nhật từ 2 giờ trước
INSERT INTO guard_locations (guard_id, latitude, longitude, accuracy, battery_level, is_inside_geofence, updated_at)
SELECT id, 10.84120, 106.80960, 8.0, 45.0, true, CURRENT_TIMESTAMP - INTERVAL '2 hours'
FROM users WHERE email = 'guard.stale@fpt.edu.vn'
ON CONFLICT (guard_id) DO UPDATE 
SET latitude = EXCLUDED.latitude,
    longitude = EXCLUDED.longitude,
    is_inside_geofence = true,
    updated_at = CURRENT_TIMESTAMP - INTERVAL '2 hours';
