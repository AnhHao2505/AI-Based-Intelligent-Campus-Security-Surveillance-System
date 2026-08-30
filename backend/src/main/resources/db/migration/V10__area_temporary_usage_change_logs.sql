-- ============================================================================
-- V10: Create area_temporary_usage_change_logs table for audit logging
-- ============================================================================

CREATE TABLE area_temporary_usage_change_logs (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    temporary_usage_id UUID         NOT NULL REFERENCES area_temporary_usages(id),
    actor_id           UUID         NOT NULL REFERENCES users(id),
    action             VARCHAR(20)  NOT NULL CHECK (action IN ('EXTEND')),
    old_end_time       TIMESTAMPTZ,
    new_end_time       TIMESTAMPTZ  NOT NULL,
    reason             VARCHAR(255) NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX ix_atux_usage ON area_temporary_usage_change_logs(temporary_usage_id, created_at DESC);
