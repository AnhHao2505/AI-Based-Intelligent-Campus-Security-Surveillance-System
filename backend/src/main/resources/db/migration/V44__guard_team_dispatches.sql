-- ============================================================================
-- V44: Guard Team Dispatches (Điều Động Bảo Vệ Tăng Cường Theo Thời Gian)
-- ============================================================================

CREATE TABLE IF NOT EXISTS guard_team_dispatches (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    guard_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    from_team_id UUID REFERENCES guard_teams(id) ON DELETE SET NULL,
    to_team_id UUID NOT NULL REFERENCES guard_teams(id) ON DELETE CASCADE,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    shift_type VARCHAR(20) DEFAULT NULL CHECK (shift_type IS NULL OR shift_type IN ('SHIFT_MORNING', 'SHIFT_AFTERNOON', 'SHIFT_NIGHT')),
    reason VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'COMPLETED', 'CANCELLED')),
    created_by UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_dispatch_dates CHECK (end_date >= start_date)
);

CREATE INDEX IF NOT EXISTS idx_guard_dispatches_guard ON guard_team_dispatches(guard_id);
CREATE INDEX IF NOT EXISTS idx_guard_dispatches_to_team ON guard_team_dispatches(to_team_id);
CREATE INDEX IF NOT EXISTS idx_guard_dispatches_dates ON guard_team_dispatches(start_date, end_date, status);
