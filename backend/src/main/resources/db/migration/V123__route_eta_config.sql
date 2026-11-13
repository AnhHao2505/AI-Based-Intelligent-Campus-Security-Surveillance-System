-- V123 (P-MAP-2, quyết định MM-05): tham số ước tính thời gian di chuyển trên bản đồ (web + app bảo vệ).
-- Trước đây viết cứng: web 1,45 m/s và 14 giây / tầng (campusIndoor3dData.js computeDigitizedRoute), mobile 1,4 m/s
-- (routing_service.dart). Nay đọc từ cấu hình, sửa trên màn Cấu hình có hiệu lực ngay; đọc qua
-- GET /api/system-configurations/route-settings (mọi tài khoản đã đăng nhập).
-- Mặc định giữ đúng giá trị web cũ. Phạt 18 m / tầng của thuật toán tìm đường KHÔNG ở đây (là hằng số thuật toán,
-- đã nằm trong chi phí cạnh của đồ thị). Nhóm SECURITY (display_order 58–69 đã dùng).
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
    ('WALKING_SPEED_MPS', '1.45', 'DECIMAL', 0.5, 3.0, 'm/giây', 'SECURITY', 70,
     'Tốc độ đi bộ dùng để ước tính thời gian di chuyển trên bản đồ (m/giây)', true),
    ('FLOOR_CHANGE_SECONDS', '14', 'INTEGER', 0, 120, 'giây', 'SECURITY', 71,
     'Thời gian cộng thêm cho mỗi lần đổi tầng khi ước tính thời gian di chuyển (giây)', true)
ON CONFLICT (config_key) DO NOTHING;
