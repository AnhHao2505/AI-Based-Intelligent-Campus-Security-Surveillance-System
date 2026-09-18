CREATE TABLE system_configurations (
    config_key    VARCHAR(100) PRIMARY KEY,
    config_value  VARCHAR(255) NOT NULL,
    data_type     VARCHAR(20)  NOT NULL,
    min_value     NUMERIC,
    max_value     NUMERIC,
    unit          VARCHAR(30),
    config_group  VARCHAR(50)  NOT NULL,
    display_order INT          NOT NULL,
    description   TEXT         NOT NULL,
    editable      BOOLEAN      NOT NULL DEFAULT true,
    updated_at    TIMESTAMPTZ  DEFAULT CURRENT_TIMESTAMP,
    updated_by    UUID REFERENCES users(id),
    CONSTRAINT chk_system_config_data_type
        CHECK (data_type IN ('INTEGER','DECIMAL','BOOLEAN','STRING'))
);

CREATE TABLE system_configuration_change_logs (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    config_key   VARCHAR(100) NOT NULL,
    old_value    VARCHAR(255),
    new_value    VARCHAR(255) NOT NULL,
    changed_by   UUID REFERENCES users(id),
    changed_at   TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_sys_cfg_log_key_time
    ON system_configuration_change_logs (config_key, changed_at DESC);

-- Seed 7 cấu hình ban đầu cho MF3
INSERT INTO system_configurations (
    config_key, config_value, data_type, min_value, max_value, unit, config_group, display_order, description, editable
) VALUES
(
    'ACCESS_REQUEST_MAX_GROUP_MEMBERS',
    '30',
    'INTEGER',
    1,
    100,
    'người',
    'ACCESS_REQUEST',
    1,
    'Số lượng thành viên tối đa trong một yêu cầu truy cập theo nhóm (không tính người tạo)',
    true
),
(
    'ACCESS_REQUEST_MAX_DURATION_HOURS',
    '12',
    'INTEGER',
    1,
    72,
    'giờ',
    'ACCESS_REQUEST',
    2,
    'Thời lượng tối đa cho mỗi lượt đăng ký truy cập khu vực',
    true
),
(
    'ACCESS_REQUEST_MAX_ADVANCE_DAYS',
    '30',
    'INTEGER',
    1,
    365,
    'ngày',
    'ACCESS_REQUEST',
    3,
    'Số ngày tối đa được phép tạo yêu cầu trước thời điểm bắt đầu',
    true
),
(
    'ACCESS_REQUEST_PAST_START_BUFFER_MINUTES',
    '5',
    'INTEGER',
    0,
    60,
    'phút',
    'ACCESS_REQUEST',
    4,
    'Khoảng đệm thời gian cho phép giờ bắt đầu trễ hơn thời điểm hiện tại khi gửi yêu cầu',
    true
),
(
    'ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE',
    'false',
    'BOOLEAN',
    NULL,
    NULL,
    NULL,
    'ACCESS_REQUEST',
    5,
    'Cho phép tạo yêu cầu truy cập nhóm tại các khu vực riêng tư (PRIVATE)',
    true
),
(
    'NOTIFICATION_PENDING_OVERDUE_HOURS',
    '24',
    'INTEGER',
    1,
    168,
    'giờ',
    'NOTIFICATION',
    1,
    'Thời gian yêu cầu ở trạng thái PENDING trước khi gửi thông báo tồn đọng cho Quản lý cơ sở vật chất',
    true
),
(
    'NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES',
    '30',
    'INTEGER',
    5,
    240,
    'phút',
    'NOTIFICATION',
    2,
    'Khoảng thời gian báo trước thời điểm bắt đầu yêu cầu đã duyệt để nhắc nhở người dùng',
    true
);
