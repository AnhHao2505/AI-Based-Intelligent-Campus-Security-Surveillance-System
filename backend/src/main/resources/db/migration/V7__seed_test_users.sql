-- ============================================================================
-- V7: Seed Test Users
-- Chỉ ADM001 có email thật (chủ Google Cloud project). Ba tài khoản còn lại
-- can_login = FALSE cho tới khi đồng đội được thêm vào Test users trên Console.
-- Test phân quyền tự động KHÔNG cần các tài khoản này — integration test tự
-- sinh JWT với role tuỳ ý.
-- ============================================================================

INSERT INTO users (user_code, full_name, role_type, can_login, email) VALUES
    ('ADM001',   'Nguyễn Anh Hào',         'ADMIN',            TRUE,  'nguyenanhhao2555@gmail.com'),
    ('FM001',    'Quản lý cơ sở vật chất', 'FACILITY_MANAGER', FALSE, NULL),
    ('GRD001',   'Bảo vệ nội bộ',          'INTERNAL_GUARD',   FALSE, NULL),
    ('OGRD001',  'Bảo vệ thuê ngoài',      'OUTSOURCED_GUARD', FALSE, NULL),
    ('SE193902', 'Sinh viên mẫu',          'STUDENT',          FALSE, NULL);
