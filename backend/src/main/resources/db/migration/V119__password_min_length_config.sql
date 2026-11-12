-- V119 (BE-06): độ dài tối thiểu của mật khẩu mới khi đặt lại mật khẩu (PUT /api/auth/reset-password).
-- Trước đây DTO chỉ @NotBlank. Lucas chốt 12/11: mặc định 8, cho sửa 6–64; quy tắc độ mạnh cố định "có ít nhất 1 chữ cái
-- và 1 chữ số" (kiểm ở AuthService). Chỉ áp cho lần đặt lại sau khi sửa, không đụng mật khẩu đang có.
-- Cùng nhóm SECURITY (display_order 58–66 đã dùng).
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
    ('PASSWORD_MIN_LENGTH', '8', 'INTEGER', 6, 64, 'ký tự', 'SECURITY', 67,
     '[Đặt lại mật khẩu] Số ký tự tối thiểu của mật khẩu mới. Mật khẩu mới còn phải có ít nhất 1 chữ cái và 1 chữ số. Chỉ áp dụng cho lần đặt lại sau khi sửa', true)
ON CONFLICT (config_key) DO NOTHING;
