-- ============================================================================
-- V43: Guard Teams and Shift Swap / Leave Requests
-- ============================================================================

-- 1. Bảng Tổ / Đội Bảo Vệ
CREATE TABLE IF NOT EXISTS guard_teams (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    team_name VARCHAR(100) NOT NULL UNIQUE,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 2. Gán Bảo vệ vào Đội (trong bảng users)
ALTER TABLE users ADD COLUMN IF NOT EXISTS team_id UUID REFERENCES guard_teams(id) ON DELETE SET NULL;
CREATE INDEX IF NOT EXISTS idx_users_team_id ON users(team_id);

-- 3. Bổ sung cờ làm thêm giờ OT vào bảng ca trực
ALTER TABLE guard_shifts ADD COLUMN IF NOT EXISTS is_overtime BOOLEAN NOT NULL DEFAULT FALSE;

-- 4. Bảng Đơn Xin Đổi Ca & Xin Nghỉ Phép (Audit Trail)
CREATE TABLE IF NOT EXISTS guard_shift_requests (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    shift_id UUID NOT NULL REFERENCES guard_shifts(id) ON DELETE RESTRICT,
    request_type VARCHAR(20) NOT NULL CHECK (request_type IN ('SWAP_SHIFT', 'LEAVE_REQUEST')),
    substitute_guard_id UUID REFERENCES users(id) ON DELETE SET NULL,
    target_shift_id UUID REFERENCES guard_shifts(id) ON DELETE SET NULL, -- Dự trù cho Phase nâng cao: Đổi ca 2 chiều
    shift_date_snapshot DATE,
    shift_type_snapshot VARCHAR(30),
    start_time_snapshot TIME,
    end_time_snapshot TIME,
    reason TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' 
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at TIMESTAMPTZ,
    review_notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_swap_has_substitute CHECK (request_type != 'SWAP_SHIFT' OR substitute_guard_id IS NOT NULL)
);

-- Indexes tối ưu tra cứu và chống trùng đơn chờ duyệt
CREATE INDEX IF NOT EXISTS idx_shift_requests_requester ON guard_shift_requests(requester_id);
CREATE INDEX IF NOT EXISTS idx_shift_requests_shift ON guard_shift_requests(shift_id);
CREATE INDEX IF NOT EXISTS idx_shift_requests_status ON guard_shift_requests(status);
CREATE UNIQUE INDEX IF NOT EXISTS uq_pending_shift_request ON guard_shift_requests(shift_id) WHERE status = 'PENDING';

-- 5. Seed dữ liệu mặc định để kiểm thử
INSERT INTO guard_teams (id, team_name, description) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Tổ 1 - An ninh Cổng & Vòng ngoài', 'Phụ trách cổng chính, cổng phụ và bãi xe'),
    ('22222222-2222-2222-2222-222222222222', 'Tổ 2 - Giám sát & Tuần tra', 'Phụ trách phòng camera và tuần tra khuôn viên')
ON CONFLICT (team_name) DO NOTHING;

-- Gán tài khoản demo bảo vệ vào Tổ 1 nếu chưa thuộc tổ nào và Tổ 1 đang hoạt động
UPDATE users 
SET team_id = '11111111-1111-1111-1111-111111111111' 
WHERE role = 'GUARD' AND team_id IS NULL
  AND EXISTS (SELECT 1 FROM guard_teams WHERE id = '11111111-1111-1111-1111-111111111111' AND is_active = TRUE);

