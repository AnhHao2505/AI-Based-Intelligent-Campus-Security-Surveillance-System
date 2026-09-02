-- ============================================================================
-- V9: Create area_temporary_usages table
-- ============================================================================

CREATE TABLE area_temporary_usages (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    area_id     UUID         NOT NULL REFERENCES areas(id),
    event_name  VARCHAR(150) NOT NULL,
    reason      VARCHAR(255),
    start_time  TIMESTAMPTZ  NOT NULL,
    end_time    TIMESTAMPTZ  NOT NULL,
    created_by  UUID         REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  UUID         REFERENCES users(id),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_atu_time CHECK (start_time < end_time)
);

CREATE INDEX ix_atu_area_time ON area_temporary_usages(area_id, start_time, end_time);
