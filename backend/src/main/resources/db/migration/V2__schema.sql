-- ============================================================================
-- V2: Schema — Tạo 13 bảng + 13 CHECK constraints + Partial Unique Index
-- Thứ tự tạo theo dependency (bảng không phụ thuộc ai tạo trước)
-- ============================================================================

-- ============================================================================
-- LUỒNG 1: ADMIN CONFIGURATION
-- ============================================================================

-- 1. areas
CREATE TABLE areas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(100) NOT NULL,
    area_level INT NOT NULL DEFAULT 1,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT chk_areas_area_level CHECK (area_level >= 1)
);

-- 2. area_escalation_policies (1-1 với areas)
CREATE TABLE area_escalation_policies (
    area_id UUID PRIMARY KEY REFERENCES areas(id),
    allow_outsourced_guard BOOLEAN NOT NULL DEFAULT TRUE,
    severity_threshold_for_internal VARCHAR(50) NOT NULL DEFAULT 'HIGH',
    preferred_guard_type VARCHAR(50) NOT NULL DEFAULT 'ANY',
    sla_seconds INT NOT NULL DEFAULT 300,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_aep_severity_threshold
        CHECK (severity_threshold_for_internal IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT chk_aep_preferred_guard_type
        CHECK (preferred_guard_type IN ('INTERNAL_ONLY', 'INTERNAL_PREFERRED', 'OUTSOURCED_PREFERRED', 'ANY'))
);

-- 3. cameras
CREATE TABLE cameras (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_name VARCHAR(100) NOT NULL,
    rtsp_url VARCHAR(255) NOT NULL UNIQUE,
    area_id UUID NOT NULL REFERENCES areas(id),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ
);

-- 4. users
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name VARCHAR(100) NOT NULL,
    user_code VARCHAR(50) NOT NULL UNIQUE,
    role_type VARCHAR(50) NOT NULL DEFAULT 'STUDENT',
    can_login BOOLEAN NOT NULL DEFAULT TRUE,
    phone_number VARCHAR(20),
    email VARCHAR(255),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    deleted_at TIMESTAMPTZ,

    CONSTRAINT chk_users_role_type
        CHECK (role_type IN ('ADMIN', 'FACILITY_MANAGER', 'INTERNAL_GUARD', 'OUTSOURCED_GUARD',
                             'TEACHER', 'STUDENT', 'PRINCIPAL', 'JANITOR', 'STAFF'))
);

-- 5. user_face_data (1-1 với users)
CREATE TABLE user_face_data (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL UNIQUE REFERENCES users(id),
    image_front_url VARCHAR(255) NOT NULL,
    image_left_url VARCHAR(255) NOT NULL,
    image_right_url VARCHAR(255) NOT NULL,
    embedding_front vector(512) NOT NULL,
    embedding_left vector(512) NOT NULL,
    embedding_right vector(512) NOT NULL,
    ocr_card_data JSONB,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 6. access_permissions (Exclusion Constraint chống overlap thời gian)
CREATE TABLE access_permissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    area_id UUID NOT NULL REFERENCES areas(id),
    valid_from TIMESTAMPTZ NOT NULL,
    valid_to TIMESTAMPTZ,
    granted_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,

    -- Chống overlap: cùng user + cùng area không được có 2 khoảng thời gian chồng nhau
    -- COALESCE(valid_to, 'infinity') xử lý trường hợp valid_to = NULL (vô thời hạn)
    CONSTRAINT excl_access_permissions_no_overlap
        EXCLUDE USING gist (
            user_id WITH =,
            area_id WITH =,
            tstzrange(valid_from, COALESCE(valid_to, 'infinity'::timestamptz)) WITH &&
        )
);

-- ============================================================================
-- LUỒNG 3: ROUTING & SCHEDULING
-- ============================================================================

-- 7. shifts
CREATE TABLE shifts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    shift_name VARCHAR(50) NOT NULL,
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

-- 8. guard_shifts
CREATE TABLE guard_shifts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id),
    shift_id UUID NOT NULL REFERENCES shifts(id),
    work_date DATE NOT NULL,
    assigned_area_id UUID REFERENCES areas(id),
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_guard_shift UNIQUE (user_id, shift_id, work_date)
);

-- ============================================================================
-- CONFIG TABLES
-- ============================================================================

-- 9. severity_rules
CREATE TABLE severity_rules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    violation_type VARCHAR(50) NOT NULL,
    area_level INT NOT NULL,
    severity VARCHAR(50) NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT unique_severity_rule UNIQUE (violation_type, area_level),
    CONSTRAINT chk_severity_rules_violation_type
        CHECK (violation_type IN ('UNAUTHORIZED_ACCESS', 'UNKNOWN_PERSON', 'LOITERING')),
    CONSTRAINT chk_severity_rules_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH'))
);

-- 10. system_config
CREATE TABLE system_config (
    config_key VARCHAR(100) PRIMARY KEY,
    config_value VARCHAR(500) NOT NULL,
    description TEXT,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
    -- Không dùng CHECK cho config_key — validate ở tầng Backend (whitelist)
    -- để giữ tính linh hoạt của thiết kế key-value
);

-- ============================================================================
-- LUỒNG 2: AI DETECTION ENGINE
-- ============================================================================

-- 11. detection_events
-- LƯU Ý QUAN TRỌNG: id KHÔNG có DEFAULT gen_random_uuid()
-- ID do AI Engine sinh và gửi qua Kafka payload → đảm bảo Idempotency
-- (ON CONFLICT (id) DO NOTHING khi Kafka redeliver)
CREATE TABLE detection_events (
    id UUID PRIMARY KEY,  -- KHÔNG có DEFAULT — do AI Engine sinh
    camera_id UUID NOT NULL REFERENCES cameras(id),
    matched_identity_id UUID REFERENCES users(id),
    incident_id UUID,  -- FK tới security_incidents sẽ thêm ở V3 (circular dependency)
    track_id VARCHAR(100) NOT NULL,
    face_crop_url VARCHAR(255),
    full_frame_url VARCHAR(255) NOT NULL,
    confidence_score FLOAT,
    status VARCHAR(50) NOT NULL,
    reason VARCHAR(50) NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_detection_events_confidence
        CHECK (confidence_score IS NULL OR (confidence_score >= 0.0 AND confidence_score <= 1.0)),
    CONSTRAINT chk_detection_events_status
        CHECK (status IN ('AUTHORIZED', 'UNAUTHORIZED')),
    CONSTRAINT chk_detection_events_reason
        CHECK (reason IN ('VALID_ACCESS', 'NO_FACE_DETECTED', 'FACE_NOT_MATCHED',
                          'PERMISSION_EXPIRED', 'LOITERING'))
);

-- ============================================================================
-- LUỒNG 3: INCIDENT & ESCALATION
-- ============================================================================

-- 12. security_incidents
CREATE TABLE security_incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    track_id VARCHAR(100) NOT NULL,
    first_detection_event_id UUID NOT NULL REFERENCES detection_events(id),
    last_detection_event_id UUID NOT NULL REFERENCES detection_events(id),
    area_id UUID NOT NULL REFERENCES areas(id),
    violation_type VARCHAR(50) NOT NULL,
    severity VARCHAR(50) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    is_escalated BOOLEAN NOT NULL DEFAULT FALSE,
    escalated_at TIMESTAMPTZ,
    escalated_to_id UUID REFERENCES users(id),
    assigned_guard_id UUID REFERENCES users(id),
    sla_deadline TIMESTAMPTZ NOT NULL,
    acknowledged_at TIMESTAMPTZ,
    dismissal_note TEXT,
    created_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,

    CONSTRAINT chk_incidents_violation_type
        CHECK (violation_type IN ('UNAUTHORIZED_ACCESS', 'UNKNOWN_PERSON', 'LOITERING')),
    CONSTRAINT chk_incidents_severity
        CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT chk_incidents_status
        CHECK (status IN ('PENDING', 'VERIFIED', 'DISMISSED', 'RESOLVED')),
    -- Bắt buộc nhập lý do khi Dismiss (FR3.2) — chặn cả chuỗi rỗng/toàn khoảng trắng
    CONSTRAINT chk_dismissal_note_required
        CHECK (status <> 'DISMISSED' OR (dismissal_note IS NOT NULL AND length(trim(dismissal_note)) > 0))
);

-- Partial Unique Index: chống race-condition khi gộp incident
-- 1 track_id chỉ có tối đa 1 incident đang mở (chưa RESOLVED/DISMISSED)
CREATE UNIQUE INDEX idx_incident_open_per_track
    ON security_incidents(track_id)
    WHERE status NOT IN ('RESOLVED', 'DISMISSED');

-- ============================================================================
-- LUỒNG 4: AUDIT LOGS
-- ============================================================================

-- 13. audit_logs (APPEND-ONLY — trigger chặn UPDATE/DELETE ở V4)
CREATE TABLE audit_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_id UUID REFERENCES users(id),
    action_type VARCHAR(100) NOT NULL,
    old_data JSONB,
    new_data JSONB,
    description TEXT NOT NULL,
    ip_address VARCHAR(45),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
