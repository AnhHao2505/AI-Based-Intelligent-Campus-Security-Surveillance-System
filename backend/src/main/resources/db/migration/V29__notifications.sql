-- ============================================================================
-- V29: Create notifications table for in-app notifications
-- ============================================================================

CREATE TABLE IF NOT EXISTS notifications (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    recipient_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type           VARCHAR(40) NOT NULL,
    title          VARCHAR(255) NOT NULL,
    message        TEXT NOT NULL,
    reference_id   UUID NULL,
    reference_type VARCHAR(40) NULL,
    is_read        BOOLEAN NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    read_at        TIMESTAMPTZ NULL,
    CONSTRAINT chk_notifications_type CHECK (type IN (
        'REQUEST_APPROVED',
        'REQUEST_REJECTED',
        'EXPIRING_SOON',
        'ACCESS_DENIED',
        'ADDED_TO_GROUP',
        'NEW_REQUEST_PENDING',
        'REQUEST_CANCELLED',
        'PENDING_OVERDUE'
    ))
);

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_created
    ON notifications(recipient_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notifications_recipient_unread
    ON notifications(recipient_id, is_read)
    WHERE is_read = FALSE;

CREATE INDEX IF NOT EXISTS idx_notifications_reference
    ON notifications(reference_id, type);
