-- ============================================================================
-- V19: Area N-N Camera, Role NORMAL_USER, Access Requests & User Area Schedules
-- ============================================================================

-- 1. Cập nhật check constraint cho cột role của bảng users
ALTER TABLE users DROP CONSTRAINT IF EXISTS chk_users_role;
ALTER TABLE users ADD CONSTRAINT chk_users_role 
    CHECK (role IN ('ADMIN', 'FACILITY_MANAGER', 'INTERNAL_GUARD', 'OUTSOURCED_GUARD', 'NORMAL_USER'));

-- 2. Bảng trung gian Area N-N Camera
CREATE TABLE IF NOT EXISTS area_cameras (
    area_id UUID NOT NULL REFERENCES areas(id) ON DELETE CASCADE,
    camera_id UUID NOT NULL REFERENCES cameras(id) ON DELETE CASCADE,
    PRIMARY KEY (area_id, camera_id)
);

CREATE INDEX IF NOT EXISTS idx_area_cameras_camera_id ON area_cameras(camera_id);

-- 3. Bảng access_requests (Yêu cầu truy cập khu vực)
CREATE TABLE IF NOT EXISTS access_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    area_id UUID NOT NULL REFERENCES areas(id),
    requester_id UUID NOT NULL REFERENCES users(id),
    request_type VARCHAR(20) NOT NULL,
    purpose TEXT NOT NULL,
    start_time TIMESTAMPTZ NOT NULL,
    end_time TIMESTAMPTZ NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    reviewer_id UUID REFERENCES users(id),
    reviewed_at TIMESTAMPTZ,
    rejection_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_access_requests_type CHECK (request_type IN ('INDIVIDUAL', 'GROUP')),
    CONSTRAINT chk_access_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT chk_access_requests_time CHECK (start_time < end_time)
);

CREATE INDEX IF NOT EXISTS idx_access_requests_area_id ON access_requests(area_id);
CREATE INDEX IF NOT EXISTS idx_access_requests_requester_id ON access_requests(requester_id);
CREATE INDEX IF NOT EXISTS idx_access_requests_status ON access_requests(status);

-- 4. Bảng access_request_members (Thành viên nhóm trong access request)
CREATE TABLE IF NOT EXISTS access_request_members (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    access_request_id UUID NOT NULL REFERENCES access_requests(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT uq_access_request_members_req_user UNIQUE (access_request_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_access_request_members_user_id ON access_request_members(user_id);

-- 5. Bảng user_area_schedules (Lịch trình/ca làm việc định kỳ của nhân viên tại khu vực)
CREATE TABLE IF NOT EXISTS user_area_schedules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    area_id UUID NOT NULL REFERENCES areas(id) ON DELETE CASCADE,
    name VARCHAR(150) NOT NULL,
    day_of_week VARCHAR(10) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_area_schedules_day CHECK (day_of_week IN ('MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY')),
    CONSTRAINT chk_user_area_schedules_time CHECK (start_time < end_time)
);

CREATE INDEX IF NOT EXISTS idx_user_area_schedules_user_id ON user_area_schedules(user_id);
CREATE INDEX IF NOT EXISTS idx_user_area_schedules_area_id ON user_area_schedules(area_id);
