-- ============================================================================
-- V141: dung lượng tối đa ảnh khách (GUEST_PHOTO_MAX_KB) vào cấu hình hệ thống
-- ============================================================================
-- Trước đây là property vận hành icss.guest.photo-max-kb (application.yml, env GUEST_PHOTO_MAX_KB, mặc định 2048): muốn đổi
-- phải sửa file / biến môi trường rồi restart. Nay GuestPhotoService đọc GUEST_PHOTO_MAX_KB mỗi lần dùng qua
-- SystemConfigService.getIntWithinBounds (sửa trên màn System Configuration có hiệu lực ngay), cùng cơ chế V140
-- (USER_FACE_IMAGE_MAX_KB). Property đã bỏ khỏi application.yml và code. Mặc định 2048 để hành vi không đổi.
-- Áp dụng: host gửi ảnh khi tạo lượt (nhiều ảnh trong một request) và host gắn / thay ảnh một khách; /eligibility trả giá
-- trị này cho form.
--
-- Cận dưới 100 KB: như V140, ảnh chân dung JPEG khoảng 640 x 640 px trở lên đã cần cỡ 100 KB; thấp hơn buộc nén mạnh,
--   giảm chất lượng vector khuôn mặt và chặn gần hết ảnh hợp lệ.
-- Cận trên 10240 KB: request tạo lượt khách mang tới GUEST_MAX_PER_VISIT ảnh, khoá này tối đa 50 (max_value, V60).
--   spring.servlet.multipart.max-request-size = 510MB = 522240 KB; max-file-size = 500MB (mỗi ảnh).
--   50 ảnh x X KB phải nhỏ hơn 522240 KB và còn chỗ cho phần JSON + ranh giới multipart: X <= 522240 / 50 = 10444,8 KB.
--   Chọn 10240 KB (10MB): 50 x 10240 KB = 512000 KB = 500MB, còn 10MB cho phần khác; mỗi ảnh 10MB << max-file-size 500MB.
--   Nâng max_value của GUEST_MAX_PER_VISIT hoặc hạ giới hạn multipart thì phải tính lại cận này.
-- Không gộp với USER_FACE_IMAGE_MAX_KB (ảnh khuôn mặt tài khoản). Chỉ thêm khoá cấu hình, không seed dữ liệu khác.
-- Nhóm GUEST đã dùng display_order 70–80 (V60, V71, V120).

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
    editable,
    tooltip
) VALUES (
    'GUEST_PHOTO_MAX_KB',
    '2048',
    'INTEGER',
    100,
    10240,
    'KB',
    'GUEST',
    81,
    'Dung lượng tối đa ảnh khách',
    true,
    'Áp dụng cho mỗi ảnh khách người mời gửi khi tạo lượt và khi gắn hoặc thay ảnh khách. Không áp dụng cho ảnh khuôn mặt tài khoản.'
) ON CONFLICT (config_key) DO NOTHING;
