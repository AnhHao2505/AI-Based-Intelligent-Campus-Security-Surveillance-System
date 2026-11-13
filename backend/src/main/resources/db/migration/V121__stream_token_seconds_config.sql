-- V121 (Lucas 13/11, chống hardcode — mentor 6.1/6.4): thời hạn token xem camera vào cấu hình hệ thống, sửa trên màn
-- Cấu hình có hiệu lực ngay (đọc mỗi lần cấp token). Trước đây đặt ở application.yml
-- (icss.camera.recording-stream-token-seconds = 300, icss.ai.annotated-stream-token-seconds = 60), đổi phải restart.
-- Token đã cấp giữ nguyên hạn cũ (exp nằm trong token đã ký). Cùng nhóm SECURITY (display_order 58–67 đã dùng).
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
    ('RECORDING_STREAM_TOKEN_SECONDS', '300', 'INTEGER', 60, 3600, 'giây', 'SECURITY', 68,
     'Validity (seconds) of the signed link used to play or download one recorded video.', true),
    ('ANNOTATED_STREAM_TOKEN_SECONDS', '60', 'INTEGER', 30, 600, 'giây', 'SECURITY', 69,
     'Validity (seconds) of the signed link used to open the annotated live view of one camera.', true)
ON CONFLICT (config_key) DO NOTHING;
