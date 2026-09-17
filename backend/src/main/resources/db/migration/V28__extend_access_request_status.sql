-- ============================================================================
-- V28: Extend access_requests status to include CANCELLED and EXPIRED,
-- and add composite index for overlap checking
-- ============================================================================

ALTER TABLE access_requests DROP CONSTRAINT IF EXISTS chk_access_requests_status;
ALTER TABLE access_requests ADD CONSTRAINT chk_access_requests_status
    CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED', 'EXPIRED'));

CREATE INDEX IF NOT EXISTS idx_access_requests_overlap
    ON access_requests (area_id, status, start_time, end_time);
