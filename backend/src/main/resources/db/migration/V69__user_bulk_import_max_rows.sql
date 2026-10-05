-- ============================================================================
-- V69: UI-19 — giới hạn số dòng nạp tài khoản theo lô vào cấu hình hệ thống
-- ============================================================================
-- Trước đây UserBulkImportService hardcode 200 dòng. Nay đọc USER_BULK_IMPORT_MAX_ROWS lúc chạy
-- (sửa trên màn System Configuration có hiệu lực ngay).
-- max_value = 500: mỗi dòng kèm 1 ảnh tối đa 350 KB (UserBulkImportHelper) và toàn bộ ảnh của file ZIP được nạp
-- vào bộ nhớ cùng lúc (extractImagesFromZip) -> 500 dòng ~ 175 MB; mỗi dòng xử lý đồng bộ trong cùng request
-- (transaction riêng + upload MinIO). Trần kỹ thuật cứng là multipart 500 MB / 350 KB ~ 1462 dòng.
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
    editable
) VALUES (
    'USER_BULK_IMPORT_MAX_ROWS',
    '200',
    'INTEGER',
    1,
    500,
    'dòng',
    'ACCOUNT',
    1,
    'Số dòng (tài khoản) tối đa trong một file nạp tài khoản theo lô, áp dụng cho cả nạp người dùng thường và nạp cán bộ / bảo vệ.',
    true
) ON CONFLICT (config_key) DO NOTHING;
