-- V127: thời gian giữ thêm (hold buffer) trước khi AI chốt cảnh báo người lạ.
-- Hết AI_IDENTITY_GRACE_SECONDS mà AI vẫn đang thấy khuôn mặt đủ chất lượng nhưng chưa khớp được ai, AI chờ thêm
-- tối đa AI_IDENTITY_HOLD_SECONDS rồi mới chốt UNKNOWN_PERSON / PERSON_INTRUSION. Không thấy mặt nào thì chốt ngay khi hết grace.
-- Mục đích: tránh 2 cảnh báo cho cùng một lượt vào vùng giám sát (UNKNOWN rồi khoảng 1 giây sau UNAUTHORIZED khi mặt rõ hơn).
-- Giá trị mặc định 1.0 giây (tổng thời gian chờ tối đa 1.5 + 1.0 = 2.5 giây). AI chỉ nhận số dương nên min là 0.1.
INSERT INTO system_configurations (
    config_key,
    config_value,
    data_type,
    min_value,
    max_value,
    unit,
    config_group,
    display_order,
    description,
    editable
) VALUES
    ('AI_IDENTITY_HOLD_SECONDS', '1.0', 'DECIMAL', 0.1, 3.0, 'giây', 'AI_CONFIG', 13,
     'Thời gian chờ thêm khi đã thấy khuôn mặt nhưng chưa nhận ra ai, trước khi phát cảnh báo người lạ (giây)', true)
ON CONFLICT (config_key) DO NOTHING;
