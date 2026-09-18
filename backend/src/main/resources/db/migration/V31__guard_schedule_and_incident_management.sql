-- ============================================================================
-- V30: Guard Schedules & Security Incident Management
-- ============================================================================

-- 2. Guard Schedule Templates (Weekly Recurring Framework)
CREATE TABLE IF NOT EXISTS guard_schedule_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    guard_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    day_of_week INT NOT NULL CHECK (day_of_week BETWEEN 1 AND 7), -- 1: Sun, 2: Mon, ..., 7: Sat
    shift_type VARCHAR(20) NOT NULL CHECK (shift_type IN ('SHIFT_MORNING', 'SHIFT_AFTERNOON', 'SHIFT_NIGHT', 'SHIFT_OFF')),
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    area_id UUID REFERENCES areas(id) ON DELETE SET NULL,
    radio_channel VARCHAR(50),
    notes TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_guard_templates_guard ON guard_schedule_templates(guard_id);
CREATE INDEX IF NOT EXISTS idx_guard_templates_dow ON guard_schedule_templates(day_of_week);

-- 3. Guard Shifts (Concrete Daily Shifts)
CREATE TABLE IF NOT EXISTS guard_shifts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    guard_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    shift_date DATE NOT NULL,
    shift_type VARCHAR(20) NOT NULL CHECK (shift_type IN ('SHIFT_MORNING', 'SHIFT_AFTERNOON', 'SHIFT_NIGHT', 'SHIFT_OFF')),
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    area_id UUID REFERENCES areas(id) ON DELETE SET NULL,
    radio_channel VARCHAR(50),
    status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED', 'CHECKED_IN', 'COMPLETED', 'ABSENT', 'CANCELLED')),
    check_in_at TIMESTAMPTZ,
    check_out_at TIMESTAMPTZ,
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_guard_shift_slot UNIQUE (guard_id, shift_date, start_time)
);

CREATE INDEX IF NOT EXISTS idx_guard_shifts_date_area ON guard_shifts(shift_date, area_id);
CREATE INDEX IF NOT EXISTS idx_guard_shifts_guard ON guard_shifts(guard_id, shift_date);
CREATE INDEX IF NOT EXISTS idx_guard_shifts_status ON guard_shifts(status);

-- 4. Security Incidents (Full Lifecycle Tracking)
CREATE TABLE IF NOT EXISTS security_incidents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id VARCHAR(100),
    camera_code VARCHAR(50) NOT NULL,
    area_id UUID NOT NULL REFERENCES areas(id) ON DELETE CASCADE,
    building VARCHAR(50) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    image_url VARCHAR(512),
    detected_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status VARCHAR(30) NOT NULL DEFAULT 'NEW' CHECK (status IN ('NEW', 'CLAIMED', 'RESOLVED_VERIFIED', 'RESOLVED_DISMISSED')),
    claimed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    claimed_at TIMESTAMPTZ,
    resolved_by UUID REFERENCES users(id) ON DELETE SET NULL,
    resolved_at TIMESTAMPTZ,
    outcome VARCHAR(20) CHECK (outcome IS NULL OR outcome IN ('VERIFIED', 'DISMISSED')),
    resolution_category VARCHAR(50) CHECK (resolution_category IS NULL OR resolution_category IN (
        'FALSE_ALARM', 'AUTHORIZED_EXCEPTION', 'REMINDED_DISPERSED', 'ESCORTED_OUT', 'REPORT_FILED', 'DETAINED_ESCALATED', 'OTHER'
    )),
    resolution_notes TEXT,
    evidence_image_url VARCHAR(512),
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_incidents_building_status ON security_incidents(building, status);
CREATE INDEX IF NOT EXISTS idx_incidents_detected_at ON security_incidents(detected_at DESC);
CREATE INDEX IF NOT EXISTS idx_incidents_area_id ON security_incidents(area_id);
