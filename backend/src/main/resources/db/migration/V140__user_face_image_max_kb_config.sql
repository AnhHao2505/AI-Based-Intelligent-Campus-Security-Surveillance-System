-- ============================================================================
-- V140: dung lượng tối đa ảnh khuôn mặt tài khoản (USER_FACE_IMAGE_MAX_KB) vào cấu hình hệ thống
-- ============================================================================
-- Trước đây FaceDataService.registerFace và UserBulkImportHelper.processSingleRow viết cứng 350 KB.
-- Nay đọc USER_FACE_IMAGE_MAX_KB lúc chạy (sửa trên màn System Configuration có hiệu lực ngay, không cần restart).
-- Mặc định 350 để hành vi không đổi.
-- Cận dưới 100 KB: ảnh chân dung JPEG khoảng 640 x 640 px trở lên đã cần cỡ 100 KB; thấp hơn buộc nén mạnh hoặc thu nhỏ,
--   giảm chất lượng vector khuôn mặt và chặn gần hết ảnh hợp lệ.
-- Cận trên 1024 KB: ZIP nạp tài khoản theo lô được nạp toàn bộ ảnh vào bộ nhớ (V69: USER_BULK_IMPORT_MAX_ROWS tối đa 500
--   dòng); 500 dòng x 1024 KB = 500 MB, bằng giới hạn multipart max-file-size 500MB trong application.yml.
-- Không gộp với icss.guest.photo-max-kb (ảnh khách, property vận hành, mặc định 2048 KB).
-- Chỉ thêm khoá cấu hình, không seed dữ liệu khác.

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
    'USER_FACE_IMAGE_MAX_KB',
    '350',
    'INTEGER',
    100,
    1024,
    'KB',
    'ACCOUNT',
    2,
    'Dung lượng tối đa ảnh khuôn mặt tài khoản',
    true,
    'Áp dụng khi đăng ký hoặc thay ảnh khuôn mặt của tài khoản, kể cả ảnh trong tệp nhập tài khoản hàng loạt. Không áp dụng cho ảnh khách.'
) ON CONFLICT (config_key) DO NOTHING;
