-- ============================================================================
-- V42: Access Control Audit Logs & Area Level Presets Version
-- ============================================================================

-- 1. Create table access_control_audit_logs
CREATE TABLE access_control_audit_logs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    target_type     VARCHAR(30) NOT NULL,
    action          VARCHAR(30) NOT NULL,
    target_id       VARCHAR(100) NOT NULL,
    area_id         UUID REFERENCES areas(id) ON DELETE RESTRICT,
    subject_user_id UUID REFERENCES users(id) ON DELETE RESTRICT,
    old_value       JSONB,
    new_value       JSONB,
    reason          VARCHAR(500),
    changed_by      UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    changed_at      TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_audit_target_type CHECK (
        target_type IN ('AREA_ASSIGNMENT', 'USER_ACCESS_LEVEL', 'AREA_ACCESS_RULES', 'LEVEL_PRESET')
    ),
    CONSTRAINT chk_audit_action CHECK (
        action IN ('ASSIGN', 'UPDATE_VALIDITY', 'REVOKE', 'UPDATE')
    )
);

-- 2. Create indexes for performance on query filters & sorting
CREATE INDEX idx_audit_logs_area_changed_at ON access_control_audit_logs (area_id, changed_at DESC);
CREATE INDEX idx_audit_logs_subject_changed_at ON access_control_audit_logs (subject_user_id, changed_at DESC);
CREATE INDEX idx_audit_logs_actor_changed_at ON access_control_audit_logs (changed_by, changed_at DESC);
CREATE INDEX idx_audit_logs_type_changed_at ON access_control_audit_logs (target_type, changed_at DESC);

-- 3. BR-AL-02: Enforce append-only with BEFORE UPDATE OR DELETE trigger
CREATE OR REPLACE FUNCTION trg_prevent_access_control_audit_logs_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'access_control_audit_logs is append-only: updates and deletes are prohibited';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_access_control_audit_logs_append_only
BEFORE UPDATE OR DELETE ON access_control_audit_logs
FOR EACH ROW
EXECUTE FUNCTION trg_prevent_access_control_audit_logs_modification();

-- 4. Optimistic locking support on area_level_presets
ALTER TABLE area_level_presets ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
