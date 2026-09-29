-- V54_2: Event Mode Extra Configurations, Expiry Reminder, and Notifications

-- 1. Extend notifications type check constraint
ALTER TABLE notifications
    DROP CONSTRAINT IF EXISTS chk_notifications_type,
    ADD CONSTRAINT chk_notifications_type CHECK (type IN (
        'REQUEST_APPROVED',
        'REQUEST_REJECTED',
        'EXPIRING_SOON',
        'ACCESS_DENIED',
        'ADDED_TO_GROUP',
        'NEW_REQUEST_PENDING',
        'REQUEST_CANCELLED',
        'PENDING_OVERDUE',
        'EVENT_MODE_LIMIT_CHANGED',
        'EVENT_MODE_CHANGED',
        'EVENT_MODE_EXPIRING'
    ));

-- 2. Add expiry_reminded_at column to area_event_sessions
ALTER TABLE area_event_sessions
    ADD COLUMN IF NOT EXISTS expiry_reminded_at TIMESTAMPTZ NULL;

-- 3. Seed system configurations for extra event mode configs
INSERT INTO system_configurations (config_key, config_value, data_type, min_value, max_value, unit, config_group, display_order, description, editable)
VALUES
    ('EVENT_MODE_MIN_MINUTES', '15', 'INTEGER', 1, 240, 'phút', 'SECURITY', 43, 'Thời lượng tối thiểu cho một phiên mở chế độ sự kiện (phút)', true),
    ('EVENT_MODE_GRACE_MINUTES', '15', 'INTEGER', 0, 240, 'phút', 'SECURITY', 44, 'Thời gian ân hạn sau sự kiện trước khi cảnh báo quá giờ (phút)', true),
    ('EVENT_MODE_EXPIRY_REMINDER_MINUTES', '30', 'INTEGER', 0, 240, 'phút', 'SECURITY', 45, 'Thời gian nhắc trước khi sự kiện hết hạn (phút, 0 = tắt nhắc)', true)
ON CONFLICT (config_key) DO NOTHING;
