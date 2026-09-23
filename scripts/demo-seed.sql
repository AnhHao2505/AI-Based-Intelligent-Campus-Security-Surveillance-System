-- ============================================================================
-- SCRIPT DỮ LIỆU DEMO HỆ THỐNG AN NINH KHUÔN VIÊN (CAMPUS SECURITY)
-- CHỈ DÙNG CHO DB DEV/DEMO — mật khẩu tài khoản demo là 123456, tuyệt đối không chạy trên môi trường thật
-- ============================================================================

-- 0. Chốt chặn an toàn: Bắt buộc chỉ chạy trên database campus_security
DO $$
BEGIN
    IF current_database() != 'campus_security' THEN
        RAISE EXCEPTION 'CRITICAL SAFETY VIOLATION: demo-seed.sql chỉ được phép chạy trên database campus_security! Database hiện tại là: %', current_database();
    END IF;
END $$;

-- ----------------------------------------------------------------------------
-- 1. NGƯỜI DÙNG (USERS)
-- Mật khẩu hash: BCrypt của 123456 (Spring Security BCryptPasswordEncoder)
-- ----------------------------------------------------------------------------

-- 1.1. Cập nhật mật khẩu cho các tài khoản seed sẵn trong migration
UPDATE users
SET password = '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq',
    updated_at = CURRENT_TIMESTAMP
WHERE email IN (
    'admin@fpt.edu.vn',
    'manager.binh@fpt.edu.vn',
    'guard.an@fpt.edu.vn',
    'guard.demo@fpt.edu.vn',
    'student.tuan@fpt.edu.vn'
);

-- 1.2. Thêm người dùng demo bổ sung: FM, GUARD, NORMAL_USER (SV/GV/IT), và tài khoản vô hiệu hóa
INSERT INTO users (id, user_code, full_name, email, password, role, access_level, is_active, created_at, updated_at)
VALUES
    -- Quản lý cơ sở thứ 2
    ('e1000000-0000-0000-0000-000000000002', 'FM-002', 'Lê Thị Chi (Quản Lý CSVC)', 'manager.chi@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'FACILITY_MANAGER', 2, true, now(), now()),
    -- Nhân viên bảo vệ thứ 3
    ('e1000000-0000-0000-0000-000000000003', 'SEC-003', 'Hoàng Văn Dũng (Bảo Vệ)', 'guard.dung@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'GUARD', 2, true, now(), now()),

    -- Sinh viên (Level 1)
    ('e1000000-0000-0000-0000-000000000010', 'SV-002', 'Nguyễn Thị Hoa (Sinh Viên)', 'student.hoa@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'NORMAL_USER', 1, true, now(), now()),
    ('e1000000-0000-0000-0000-000000000011', 'SV-003', 'Trần Văn Nam (Sinh Viên)', 'student.nam@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'NORMAL_USER', 1, true, now(), now()),
    ('e1000000-0000-0000-0000-000000000012', 'SV-004', 'Vũ Thùy Linh (Sinh Viên)', 'student.linh@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'NORMAL_USER', 1, true, now(), now()),

    -- Giảng viên (Level 2)
    ('e1000000-0000-0000-0000-000000000013', 'GV-001', 'TS. Lê Hùng (Giảng Viên)', 'lecturer.hung@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'NORMAL_USER', 2, true, now(), now()),
    ('e1000000-0000-0000-0000-000000000014', 'GV-002', 'ThS. Đỗ Tuyết Mai (Giảng Viên)', 'lecturer.mai@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'NORMAL_USER', 2, true, now(), now()),
    ('e1000000-0000-0000-0000-000000000015', 'GV-003', 'TS. Bùi Đăng Khoa (Giảng Viên)', 'lecturer.khoa@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'NORMAL_USER', 2, true, now(), now()),

    -- Nhân viên IT / Kỹ thuật (Level 3)
    ('e1000000-0000-0000-0000-000000000016', 'IT-001', 'Đặng Quốc Cường (Kỹ Sư IT)', 'it.cuong@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'NORMAL_USER', 3, true, now(), now()),
    ('e1000000-0000-0000-0000-000000000017', 'IT-002', 'Nguyễn Hoàng Phúc (Kỹ Sư Mạng)', 'it.phuc@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'NORMAL_USER', 3, true, now(), now()),
    ('e1000000-0000-0000-0000-000000000018', 'IT-003', 'Võ Anh Tuấn (Admin Hệ Thống)', 'it.tuan@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'NORMAL_USER', 3, true, now(), now()),

    -- Tài khoản đã bị vô hiệu hóa
    ('e1000000-0000-0000-0000-000000000019', 'DIS-001', 'Tài Khoản Vô Hiệu Hóa', 'user.disabled@fpt.edu.vn', '$2a$10$OwSAkB9wdxffm7ifBjfvfuFRLIP7GIj8FUuh0Gw8qbJNTycAjRbgq', 'NORMAL_USER', 1, false, now(), now())
ON CONFLICT (id) DO UPDATE
SET
    user_code = EXCLUDED.user_code,
    full_name = EXCLUDED.full_name,
    password = EXCLUDED.password,
    role = EXCLUDED.role,
    access_level = EXCLUDED.access_level,
    is_active = EXCLUDED.is_active,
    updated_at = CURRENT_TIMESTAMP;


-- ----------------------------------------------------------------------------
-- 2. KHU VỰC & HÌNH HỌC (AREAS & GEOMETRY)
-- Toà FPT_AROUND, tầng G, 1, 2. Bounding box không chồng lấn.
-- ----------------------------------------------------------------------------

-- 2.1. Cập nhật phân loại và toạ độ Geometry cho 6 khu vực seed sẵn trong migration (Tầng G)
UPDATE areas
SET area_level = 'PUBLIC',
    area_access_level = 1,
    explicit_authorization_required = false,
    geometry = '{"type":"polygon","version":1,"vertices":[{"x":0.05,"y":0.10},{"x":0.25,"y":0.10},{"x":0.25,"y":0.35},{"x":0.05,"y":0.35}]}'::jsonb,
    updated_at = CURRENT_TIMESTAMP
WHERE code = 'FPTA-G-GATE';

UPDATE areas
SET area_level = 'PUBLIC',
    area_access_level = 1,
    explicit_authorization_required = false,
    geometry = '{"type":"polygon","version":1,"vertices":[{"x":0.30,"y":0.10},{"x":0.50,"y":0.10},{"x":0.50,"y":0.35},{"x":0.30,"y":0.35}]}'::jsonb,
    updated_at = CURRENT_TIMESTAMP
WHERE code = 'FPTA-G-LOTUS';

UPDATE areas
SET area_level = 'PUBLIC',
    area_access_level = 1,
    explicit_authorization_required = false,
    geometry = '{"type":"polygon","version":1,"vertices":[{"x":0.80,"y":0.10},{"x":0.95,"y":0.10},{"x":0.95,"y":0.35},{"x":0.80,"y":0.35}]}'::jsonb,
    updated_at = CURRENT_TIMESTAMP
WHERE code = 'FPTA-G-LIB';

UPDATE areas
SET area_level = 'CONFIDENTIAL_CONTACT_REQUIRED',
    area_access_level = 3,
    explicit_authorization_required = false,
    geometry = '{"type":"polygon","version":1,"vertices":[{"x":0.05,"y":0.55},{"x":0.30,"y":0.55},{"x":0.30,"y":0.85},{"x":0.05,"y":0.85}]}'::jsonb,
    updated_at = CURRENT_TIMESTAMP
WHERE code = 'FPTA-G-LB01';

UPDATE areas
SET area_level = 'CONFIDENTIAL_CONTACT_REQUIRED',
    area_access_level = 3,
    explicit_authorization_required = false,
    geometry = '{"type":"polygon","version":1,"vertices":[{"x":0.35,"y":0.55},{"x":0.60,"y":0.55},{"x":0.60,"y":0.85},{"x":0.35,"y":0.85}]}'::jsonb,
    updated_at = CURRENT_TIMESTAMP
WHERE code = 'FPTA-G-LB02';

UPDATE areas
SET area_level = 'PUBLIC',
    area_access_level = 1,
    explicit_authorization_required = false,
    geometry = '{"type":"polygon","version":1,"vertices":[{"x":0.65,"y":0.55},{"x":0.90,"y":0.55},{"x":0.90,"y":0.85},{"x":0.65,"y":0.85}]}'::jsonb,
    updated_at = CURRENT_TIMESTAMP
WHERE code = 'FPTA-G-MED';

-- 2.2. Thêm 8 khu vực mới để đủ 4 phân loại và các tầng G, 1, 2
INSERT INTO areas (id, code, name, area_level, area_access_level, explicit_authorization_required, building, floor, description, is_active, geometry, created_at, updated_at)
VALUES
    -- TẦNG G: Căng tin (PUBLIC)
    ('a1000000-0000-0000-0000-000000000001', 'FPTA-G-CAN', 'Căng tin tầng G', 'PUBLIC', 1, false, 'FPT_AROUND', 'G', 'Khu ẩm thực và giải khát cho cán bộ sinh viên', true,
     '{"type":"polygon","version":1,"vertices":[{"x":0.55,"y":0.10},{"x":0.75,"y":0.10},{"x":0.75,"y":0.35},{"x":0.55,"y":0.35}]}'::jsonb, now(), now()),

    -- TẦNG 1: Phòng giảng viên (INTERNAL_CONFIDENTIAL)
    ('a1000000-0000-0000-0000-000000000002', 'FPTA-1-LEC', 'Phòng giảng viên', 'INTERNAL_CONFIDENTIAL', 2, false, 'FPT_AROUND', '1', 'Khu làm việc chung của giảng viên các khoa', true,
     '{"type":"polygon","version":1,"vertices":[{"x":0.10,"y":0.10},{"x":0.45,"y":0.10},{"x":0.45,"y":0.40},{"x":0.10,"y":0.40}]}'::jsonb, now(), now()),

    -- TẦNG 1: Phòng họp tầng 1 (INTERNAL_CONFIDENTIAL)
    ('a1000000-0000-0000-0000-000000000003', 'FPTA-1-MR1', 'Phòng họp tầng 1', 'INTERNAL_CONFIDENTIAL', 2, false, 'FPT_AROUND', '1', 'Phòng họp đa năng phục vụ hội thảo và họp khoa', true,
     '{"type":"polygon","version":1,"vertices":[{"x":0.55,"y":0.10},{"x":0.90,"y":0.10},{"x":0.90,"y":0.40},{"x":0.55,"y":0.40}]}'::jsonb, now(), now()),

    -- TẦNG 1: Lab AI (CONFIDENTIAL_CONTACT_REQUIRED)
    ('a1000000-0000-0000-0000-000000000004', 'FPTA-1-AI', 'Lab AI', 'CONFIDENTIAL_CONTACT_REQUIRED', 3, false, 'FPT_AROUND', '1', 'Phòng thí nghiệm trí tuệ nhân tạo và học sâu', true,
     '{"type":"polygon","version":1,"vertices":[{"x":0.10,"y":0.55},{"x":0.45,"y":0.55},{"x":0.45,"y":0.85},{"x":0.10,"y":0.85}]}'::jsonb, now(), now()),

    -- TẦNG 1: Lab IoT (CONFIDENTIAL_CONTACT_REQUIRED) - RIÊNG KHU VỰC NÀY KHÁC PRESET (explicit = true) để hiện badge "Khác mặc định"
    ('a1000000-0000-0000-0000-000000000005', 'FPTA-1-IOT', 'Lab IoT', 'CONFIDENTIAL_CONTACT_REQUIRED', 3, true, 'FPT_AROUND', '1', 'Phòng nghiên cứu vi mạch và vạn vật kết nối', true,
     '{"type":"polygon","version":1,"vertices":[{"x":0.55,"y":0.55},{"x":0.90,"y":0.55},{"x":0.90,"y":0.85},{"x":0.55,"y":0.85}]}'::jsonb, now(), now()),

    -- TẦNG 2: Phòng Server (HIGHLY_CONFIDENTIAL)
    ('a1000000-0000-0000-0000-000000000006', 'FPTA-2-SRV', 'Phòng Server', 'HIGHLY_CONFIDENTIAL', 3, true, 'FPT_AROUND', '2', 'Trung tâm máy chủ và lưu trữ dữ liệu trường học', true,
     '{"type":"polygon","version":1,"vertices":[{"x":0.05,"y":0.20},{"x":0.30,"y":0.20},{"x":0.30,"y":0.70},{"x":0.05,"y":0.70}]}'::jsonb, now(), now()),

    -- TẦNG 2: Phòng điện (HIGHLY_CONFIDENTIAL)
    ('a1000000-0000-0000-0000-000000000007', 'FPTA-2-ELE', 'Phòng điện', 'HIGHLY_CONFIDENTIAL', 3, true, 'FPT_AROUND', '2', 'Trạm biến áp nội bộ và hệ thống lưu điện UPS', true,
     '{"type":"polygon","version":1,"vertices":[{"x":0.35,"y":0.20},{"x":0.60,"y":0.20},{"x":0.60,"y":0.70},{"x":0.35,"y":0.70}]}'::jsonb, now(), now()),

    -- TẦNG 2: Phòng khảo thí (HIGHLY_CONFIDENTIAL)
    ('a1000000-0000-0000-0000-000000000008', 'FPTA-2-EXM', 'Phòng khảo thí', 'HIGHLY_CONFIDENTIAL', 3, true, 'FPT_AROUND', '2', 'Khu vực in sao và lưu trữ đề thi bảo mật tuyệt đối', true,
     '{"type":"polygon","version":1,"vertices":[{"x":0.65,"y":0.20},{"x":0.90,"y":0.20},{"x":0.90,"y":0.70},{"x":0.65,"y":0.70}]}'::jsonb, now(), now())
ON CONFLICT (id) DO UPDATE
SET
    name = EXCLUDED.name,
    area_level = EXCLUDED.area_level,
    area_access_level = EXCLUDED.area_access_level,
    explicit_authorization_required = EXCLUDED.explicit_authorization_required,
    building = EXCLUDED.building,
    floor = EXCLUDED.floor,
    description = EXCLUDED.description,
    is_active = EXCLUDED.is_active,
    geometry = EXCLUDED.geometry,
    updated_at = CURRENT_TIMESTAMP;


-- ----------------------------------------------------------------------------
-- 3. CAMERA & GÁN CAMERA KHU VỰC (CAMERAS & AREA_CAMERAS)
-- 4 camera có sẵn gắn vào khu vực; 1 camera chưa gán
-- ----------------------------------------------------------------------------

-- 3.1. Thêm camera thứ 5: Camera Dự Phòng (chưa gán khu vực)
INSERT INTO cameras (id, camera_code, name, status, operational_status, installed_at, created_at, updated_at)
VALUES
    ('c1000000-0000-0000-0000-000000000005', 'CAM-005', 'Camera Dự Phòng Tầng 1', 'ACTIVE', 'OFFLINE', now(), now(), now())
ON CONFLICT (camera_code) DO NOTHING;

-- 3.2. Gán 4 camera vào khu vực tương ứng
INSERT INTO area_cameras (area_id, camera_id)
VALUES
    ((SELECT id FROM areas WHERE code = 'FPTA-G-GATE'), (SELECT id FROM cameras WHERE camera_code = 'CAM-001')),
    ((SELECT id FROM areas WHERE code = 'FPTA-G-LOTUS'), (SELECT id FROM cameras WHERE camera_code = 'CAM-002')),
    ((SELECT id FROM areas WHERE code = 'FPTA-G-LIB'), (SELECT id FROM cameras WHERE camera_code = 'CAM-003')),
    ((SELECT id FROM areas WHERE code = 'FPTA-G-CAN'), (SELECT id FROM cameras WHERE camera_code = 'CAM-004'))
ON CONFLICT (area_id, camera_id) DO NOTHING;


-- ----------------------------------------------------------------------------
-- 4. NHÂN VIÊN CHỈ ĐỊNH (AREA_ASSIGNED_PERSONNEL)
-- Đủ 5 trạng thái theo yêu cầu
-- ----------------------------------------------------------------------------

INSERT INTO area_assigned_personnel (id, area_id, user_id, valid_from, valid_to, note, revoked_at, revoked_by, revoke_reason, created_by, created_at, updated_at)
VALUES
    -- 1. Đang hiệu lực không thời hạn (IT-001 -> Phòng Server FPTA-2-SRV)
    ('f1000000-0000-0000-0000-000000000001',
     (SELECT id FROM areas WHERE code = 'FPTA-2-SRV'),
     (SELECT id FROM users WHERE email = 'it.cuong@fpt.edu.vn'),
     now() - interval '10 days', NULL, 'Quản trị viên máy chủ chính', NULL, NULL, NULL,
     (SELECT id FROM users WHERE email = 'manager.binh@fpt.edu.vn'), now() - interval '10 days', now() - interval '10 days'),

    -- 2. Sắp hết hạn trong vài giờ (IT-002 -> Phòng điện FPTA-2-ELE)
    ('f1000000-0000-0000-0000-000000000002',
     (SELECT id FROM areas WHERE code = 'FPTA-2-ELE'),
     (SELECT id FROM users WHERE email = 'it.phuc@fpt.edu.vn'),
     now() - interval '20 hours', now() + interval '3 hours', 'Bảo trì hệ thống điện UPS tầng 2', NULL, NULL, NULL,
     (SELECT id FROM users WHERE email = 'manager.binh@fpt.edu.vn'), now() - interval '20 hours', now() - interval '20 hours'),

    -- 3. Sắp hiệu lực (GV-001 -> Lab AI FPTA-1-AI)
    ('f1000000-0000-0000-0000-000000000003',
     (SELECT id FROM areas WHERE code = 'FPTA-1-AI'),
     (SELECT id FROM users WHERE email = 'lecturer.hung@fpt.edu.vn'),
     now() + interval '2 days', now() + interval '30 days', 'Dự án nghiên cứu thị giác máy tính Fall 2026', NULL, NULL, NULL,
     (SELECT id FROM users WHERE email = 'manager.chi@fpt.edu.vn'), now() - interval '1 day', now() - interval '1 day'),

    -- 4. Đã hết hạn (SV-002 -> Lab IoT FPTA-1-IOT)
    ('f1000000-0000-0000-0000-000000000004',
     (SELECT id FROM areas WHERE code = 'FPTA-1-IOT'),
     (SELECT id FROM users WHERE email = 'student.hoa@fpt.edu.vn'),
     now() - interval '15 days', now() - interval '2 days', 'Thực hành đề tài tốt nghiệp IoT', NULL, NULL, NULL,
     (SELECT id FROM users WHERE email = 'manager.binh@fpt.edu.vn'), now() - interval '15 days', now() - interval '15 days'),

    -- 5. Đã thu hồi có lý do (SV-003 -> Phòng khảo thí FPTA-2-EXM)
    ('f1000000-0000-0000-0000-000000000005',
     (SELECT id FROM areas WHERE code = 'FPTA-2-EXM'),
     (SELECT id FROM users WHERE email = 'student.nam@fpt.edu.vn'),
     now() - interval '5 days', now() + interval '10 days', 'Hỗ trợ công tác tổ chức thi',
     now() - interval '1 day', (SELECT id FROM users WHERE email = 'manager.binh@fpt.edu.vn'), 'Hoàn thành công tác hỗ trợ coi thi trước hạn',
     (SELECT id FROM users WHERE email = 'manager.binh@fpt.edu.vn'), now() - interval '5 days', now() - interval '1 day')
ON CONFLICT (id) DO UPDATE
SET
    valid_from = EXCLUDED.valid_from,
    valid_to = EXCLUDED.valid_to,
    note = EXCLUDED.note,
    revoked_at = EXCLUDED.revoked_at,
    revoked_by = EXCLUDED.revoked_by,
    revoke_reason = EXCLUDED.revoke_reason,
    updated_at = CURRENT_TIMESTAMP;


-- ----------------------------------------------------------------------------
-- 5. ĐƠN ĐĂNG KÝ TRUY CẬP (ACCESS_REQUESTS & MEMBERS)
-- Đủ các trạng thái PENDING, APPROVED, REJECTED, FINISHED và đơn nhóm
-- ----------------------------------------------------------------------------

INSERT INTO access_requests (id, area_id, requester_id, request_type, purpose, start_time, end_time, status, reviewer_id, reviewed_at, rejection_reason, created_at, updated_at)
VALUES
    -- 1. Chờ duyệt (INDIVIDUAL)
    ('b1000000-0000-0000-0000-000000000001',
     (SELECT id FROM areas WHERE code = 'FPTA-1-AI'),
     (SELECT id FROM users WHERE email = 'student.tuan@fpt.edu.vn'),
     'INDIVIDUAL', 'Nghiên cứu mô hình thị giác AI phục vụ đồ án môn học',
     now() + interval '1 day', now() + interval '1 day 4 hours',
     'PENDING', NULL, NULL, NULL, now() - interval '2 hours', now() - interval '2 hours'),

    -- 2. Đã duyệt đang trong khung giờ (INDIVIDUAL) - Giảng viên Level 2 cần đơn để vào Lab AI Level 3
    ('b1000000-0000-0000-0000-000000000002',
     (SELECT id FROM areas WHERE code = 'FPTA-1-AI'),
     (SELECT id FROM users WHERE email = 'lecturer.mai@fpt.edu.vn'),
     'INDIVIDUAL', 'Thực nghiệm mô hình AI phục vụ nghiên cứu đề tài khoa học',
     now() - interval '1 hour', now() + interval '2 hours',
     'APPROVED', (SELECT id FROM users WHERE email = 'manager.binh@fpt.edu.vn'), now() - interval '1 day', NULL,
     now() - interval '1 day 2 hours', now() - interval '1 day'),

    -- 3. Đã duyệt tương lai (GROUP) - Đơn nhóm
    ('b1000000-0000-0000-0000-000000000003',
     (SELECT id FROM areas WHERE code = 'FPTA-1-IOT'),
     (SELECT id FROM users WHERE email = 'student.nam@fpt.edu.vn'),
     'GROUP', 'Thực hành kết nối mạng cảm biến IoT cho nhóm 4 sinh viên',
     now() + interval '3 days', now() + interval '3 days 3 hours',
     'APPROVED', (SELECT id FROM users WHERE email = 'manager.chi@fpt.edu.vn'), now() - interval '5 hours', NULL,
     now() - interval '8 hours', now() - interval '5 hours'),

    -- 4. Bị từ chối (INDIVIDUAL)
    ('b1000000-0000-0000-0000-000000000004',
     (SELECT id FROM areas WHERE code = 'FPTA-2-SRV'),
     (SELECT id FROM users WHERE email = 'student.linh@fpt.edu.vn'),
     'INDIVIDUAL', 'Tìm hiểu kiến trúc mạng máy chủ trung tâm trường học',
     now() + interval '1 day', now() + interval '1 day 2 hours',
     'REJECTED', (SELECT id FROM users WHERE email = 'manager.binh@fpt.edu.vn'), now() - interval '3 hours',
     'Khu vực bảo mật cấp độ cao, sinh viên không được phép truy cập theo quy định',
     now() - interval '5 hours', now() - interval '3 hours'),

    -- 5. Đã kết thúc (FINISHED, INDIVIDUAL) - Giảng viên Level 2 cần đơn để vào Phòng LB01 Level 3
    ('b1000000-0000-0000-0000-000000000005',
     (SELECT id FROM areas WHERE code = 'FPTA-G-LB01'),
     (SELECT id FROM users WHERE email = 'lecturer.khoa@fpt.edu.vn'),
     'INDIVIDUAL', 'Sử dụng trang thiết bị thí nghiệm phòng LB01 phục vụ nghiên cứu',
     now() - interval '2 days 3 hours', now() - interval '2 days',
     'FINISHED', (SELECT id FROM users WHERE email = 'manager.chi@fpt.edu.vn'), now() - interval '3 days', NULL,
     now() - interval '3 days 4 hours', now() - interval '2 days')
ON CONFLICT (id) DO UPDATE
SET
    area_id = EXCLUDED.area_id,
    requester_id = EXCLUDED.requester_id,
    request_type = EXCLUDED.request_type,
    purpose = EXCLUDED.purpose,
    start_time = EXCLUDED.start_time,
    end_time = EXCLUDED.end_time,
    status = EXCLUDED.status,
    reviewer_id = EXCLUDED.reviewer_id,
    reviewed_at = EXCLUDED.reviewed_at,
    rejection_reason = EXCLUDED.rejection_reason,
    updated_at = CURRENT_TIMESTAMP;

-- Thành viên đơn nhóm
INSERT INTO access_request_members (id, access_request_id, user_id)
VALUES
    ('b2000000-0000-0000-0000-000000000001', 'b1000000-0000-0000-0000-000000000003', (SELECT id FROM users WHERE email = 'student.nam@fpt.edu.vn')),
    ('b2000000-0000-0000-0000-000000000002', 'b1000000-0000-0000-0000-000000000003', (SELECT id FROM users WHERE email = 'student.tuan@fpt.edu.vn')),
    ('b2000000-0000-0000-0000-000000000003', 'b1000000-0000-0000-0000-000000000003', (SELECT id FROM users WHERE email = 'student.hoa@fpt.edu.vn')),
    ('b2000000-0000-0000-0000-000000000004', 'b1000000-0000-0000-0000-000000000003', (SELECT id FROM users WHERE email = 'student.linh@fpt.edu.vn'))
ON CONFLICT (access_request_id, user_id) DO NOTHING;


-- ----------------------------------------------------------------------------
-- 6. SỰ CỐ AN NINH (SECURITY_INCIDENTS)
-- Trải đều 30 ngày, đủ trạng thái NEW, CLAIMED, RESOLVED_VERIFIED, RESOLVED_DISMISSED
-- ----------------------------------------------------------------------------

INSERT INTO security_incidents (id, event_id, camera_code, area_id, building, event_type, image_url, detected_at, status, claimed_by, claimed_at, resolved_by, resolved_at, outcome, resolution_category, resolution_notes, evidence_image_url, version, created_at, updated_at)
VALUES
    -- 1. NEW - vừa phát hiện 20 phút trước
    ('91000000-0000-0000-0000-000000000001', 'EVT-2026-001', 'CAM-001',
     (SELECT id FROM areas WHERE code = 'FPTA-G-GATE'), 'FPT_AROUND', 'UNAUTHORIZED_ACCESS', NULL,
     now() - interval '20 minutes', 'NEW', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, now() - interval '20 minutes', now() - interval '20 minutes'),

    -- 2. NEW - chờ xử lý đã lâu (3 giờ trước)
    ('91000000-0000-0000-0000-000000000002', 'EVT-2026-002', 'CAM-002',
     (SELECT id FROM areas WHERE code = 'FPTA-G-LOTUS'), 'FPT_AROUND', 'UNAUTHORIZED_ACCESS', NULL,
     now() - interval '3 hours', 'NEW', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, 0, now() - interval '3 hours', now() - interval '3 hours'),

    -- 3. CLAIMED - đang xử lý (45 phút trước)
    ('91000000-0000-0000-0000-000000000003', 'EVT-2026-003', 'CAM-003',
     (SELECT id FROM areas WHERE code = 'FPTA-G-LIB'), 'FPT_AROUND', 'SUSPICIOUS_BEHAVIOR', NULL,
     now() - interval '45 minutes', 'CLAIMED', (SELECT id FROM users WHERE email = 'guard.an@fpt.edu.vn'), now() - interval '30 minutes',
     NULL, NULL, NULL, NULL, NULL, NULL, 1, now() - interval '45 minutes', now() - interval '30 minutes'),

    -- 4. RESOLVED_VERIFIED - nhắc nhở giải tán (2 ngày trước)
    ('91000000-0000-0000-0000-000000000004', 'EVT-2026-004', 'CAM-004',
     (SELECT id FROM areas WHERE code = 'FPTA-G-CAN'), 'FPT_AROUND', 'INTRUSION', NULL,
     now() - interval '2 days', 'RESOLVED_VERIFIED',
     (SELECT id FROM users WHERE email = 'guard.demo@fpt.edu.vn'), now() - interval '2 days' + interval '10 minutes',
     (SELECT id FROM users WHERE email = 'guard.demo@fpt.edu.vn'), now() - interval '2 days' + interval '25 minutes',
     'VERIFIED', 'REMINDED_DISPERSED', 'Đã nhắc nhở nhóm sinh viên rời khu vực căng tin sau giờ đóng cửa', NULL, 2, now() - interval '2 days', now() - interval '2 days'),

    -- 5. RESOLVED_VERIFIED - hộ tống ra ngoài (7 ngày trước)
    ('91000000-0000-0000-0000-000000000005', 'EVT-2026-005', 'CAM-001',
     (SELECT id FROM areas WHERE code = 'FPTA-G-GATE'), 'FPT_AROUND', 'UNAUTHORIZED_ACCESS', NULL,
     now() - interval '7 days', 'RESOLVED_VERIFIED',
     (SELECT id FROM users WHERE email = 'guard.dung@fpt.edu.vn'), now() - interval '7 days' + interval '5 minutes',
     (SELECT id FROM users WHERE email = 'guard.dung@fpt.edu.vn'), now() - interval '7 days' + interval '20 minutes',
     'VERIFIED', 'ESCORTED_OUT', 'Hộ tống khách ngoài chưa đăng ký thẻ tham quan ra cổng bảo vệ', NULL, 2, now() - interval '7 days', now() - interval '7 days'),

    -- 6. RESOLVED_VERIFIED - lập biên bản (15 ngày trước)
    ('91000000-0000-0000-0000-000000000006', 'EVT-2026-006', 'CAM-003',
     (SELECT id FROM areas WHERE code = 'FPTA-G-LIB'), 'FPT_AROUND', 'UNAUTHORIZED_ACCESS', NULL,
     now() - interval '15 days', 'RESOLVED_VERIFIED',
     (SELECT id FROM users WHERE email = 'guard.an@fpt.edu.vn'), now() - interval '15 days' + interval '8 minutes',
     (SELECT id FROM users WHERE email = 'guard.an@fpt.edu.vn'), now() - interval '15 days' + interval '40 minutes',
     'VERIFIED', 'REPORT_FILED', 'Lập biên bản vi phạm quy định truy cập khu vực lưu trữ tài liệu đặc biệt', NULL, 2, now() - interval '15 days', now() - interval '15 days'),

    -- 7. RESOLVED_VERIFIED - ngoại lệ hợp lệ (25 ngày trước)
    ('91000000-0000-0000-0000-000000000007', 'EVT-2026-007', 'CAM-002',
     (SELECT id FROM areas WHERE code = 'FPTA-G-LOTUS'), 'FPT_AROUND', 'UNAUTHORIZED_ACCESS', NULL,
     now() - interval '25 days', 'RESOLVED_VERIFIED',
     (SELECT id FROM users WHERE email = 'guard.demo@fpt.edu.vn'), now() - interval '25 days' + interval '12 minutes',
     (SELECT id FROM users WHERE email = 'guard.demo@fpt.edu.vn'), now() - interval '25 days' + interval '30 minutes',
     'VERIFIED', 'AUTHORIZED_EXCEPTION', 'Cán bộ kỹ thuật vào xử lý sự cố rò rỉ nước khẩn cấp ban đêm', NULL, 2, now() - interval '25 days', now() - interval '25 days'),

    -- 8. RESOLVED_DISMISSED - báo nhầm của AI (10 ngày trước)
    ('91000000-0000-0000-0000-000000000008', 'EVT-2026-008', 'CAM-001',
     (SELECT id FROM areas WHERE code = 'FPTA-G-GATE'), 'FPT_AROUND', 'SUSPICIOUS_BEHAVIOR', NULL,
     now() - interval '10 days', 'RESOLVED_DISMISSED',
     (SELECT id FROM users WHERE email = 'guard.an@fpt.edu.vn'), now() - interval '10 days' + interval '5 minutes',
     (SELECT id FROM users WHERE email = 'guard.an@fpt.edu.vn'), now() - interval '10 days' + interval '15 minutes',
     'DISMISSED', 'FALSE_ALARM', 'AI nhận diện nhầm bóng đổ của nhân viên vệ sinh mang đồng phục', NULL, 2, now() - interval '10 days', now() - interval '10 days')
ON CONFLICT (id) DO UPDATE
SET
    status = EXCLUDED.status,
    claimed_by = EXCLUDED.claimed_by,
    claimed_at = EXCLUDED.claimed_at,
    resolved_by = EXCLUDED.resolved_by,
    resolved_at = EXCLUDED.resolved_at,
    outcome = EXCLUDED.outcome,
    resolution_category = EXCLUDED.resolution_category,
    resolution_notes = EXCLUDED.resolution_notes,
    updated_at = CURRENT_TIMESTAMP;


-- ----------------------------------------------------------------------------
-- 7. LỊCH TRỰC TUẦN HIỆN TẠI (GUARD_SHIFTS)
-- Lịch trực tuần hiện tại cho 3 nhân viên bảo vệ (SEC-001, SEC-002, SEC-003)
-- ----------------------------------------------------------------------------

DO $$
DECLARE
    mon date := date_trunc('week', current_date)::date;
    g_an uuid;
    g_demo uuid;
    g_dung uuid;
    a_gate uuid;
    a_lib uuid;
    a_srv uuid;
    d date;
    i int;
BEGIN
    SELECT id INTO g_an FROM users WHERE email = 'guard.an@fpt.edu.vn';
    SELECT id INTO g_demo FROM users WHERE email = 'guard.demo@fpt.edu.vn';
    SELECT id INTO g_dung FROM users WHERE email = 'guard.dung@fpt.edu.vn';

    SELECT id INTO a_gate FROM areas WHERE code = 'FPTA-G-GATE';
    SELECT id INTO a_lib FROM areas WHERE code = 'FPTA-G-LIB';
    SELECT id INTO a_srv FROM areas WHERE code = 'FPTA-2-SRV';

    -- Xếp lịch cho 7 ngày của tuần hiện tại (Thứ 2 đến Chủ nhật)
    FOR i IN 0..6 LOOP
        d := mon + i;

        -- Guard An: Ca sáng (06:00 - 14:00) tại Cổng chính
        INSERT INTO guard_shifts (guard_id, shift_date, shift_type, start_time, end_time, area_id, radio_channel, status, check_in_at, check_out_at, notes, created_at, updated_at)
        VALUES (
            g_an, d, 'SHIFT_MORNING', '06:00:00'::time, '14:00:00'::time, a_gate, 'CH-01',
            CASE WHEN d < current_date THEN 'COMPLETED' WHEN d = current_date THEN 'CHECKED_IN' ELSE 'SCHEDULED' END,
            CASE WHEN d <= current_date THEN (d + '05:55:00'::time)::timestamptz ELSE NULL END,
            CASE WHEN d < current_date THEN (d + '14:05:00'::time)::timestamptz ELSE NULL END,
            'Trực cổng chính kiểm soát người và phương tiện', now(), now()
        )
        ON CONFLICT (guard_id, shift_date, start_time) DO UPDATE
        SET status = EXCLUDED.status, check_in_at = EXCLUDED.check_in_at, check_out_at = EXCLUDED.check_out_at, updated_at = CURRENT_TIMESTAMP;

        -- Guard Demo: Ca chiều (14:00 - 22:00) tại Thư viện
        INSERT INTO guard_shifts (guard_id, shift_date, shift_type, start_time, end_time, area_id, radio_channel, status, check_in_at, check_out_at, notes, created_at, updated_at)
        VALUES (
            g_demo, d, 'SHIFT_AFTERNOON', '14:00:00'::time, '22:00:00'::time, a_lib, 'CH-02',
            CASE WHEN d < current_date THEN 'COMPLETED' ELSE 'SCHEDULED' END,
            CASE WHEN d < current_date THEN (d + '13:58:00'::time)::timestamptz ELSE NULL END,
            CASE WHEN d < current_date THEN (d + '22:02:00'::time)::timestamptz ELSE NULL END,
            'Tuần tra khu vực học tập và thư viện tầng G', now(), now()
        )
        ON CONFLICT (guard_id, shift_date, start_time) DO UPDATE
        SET status = EXCLUDED.status, check_in_at = EXCLUDED.check_in_at, check_out_at = EXCLUDED.check_out_at, updated_at = CURRENT_TIMESTAMP;

        -- Guard Dung: Ca đêm (22:00 - 06:00) tại Phòng Server / Tầng 2
        INSERT INTO guard_shifts (guard_id, shift_date, shift_type, start_time, end_time, area_id, radio_channel, status, check_in_at, check_out_at, notes, created_at, updated_at)
        VALUES (
            g_dung, d, 'SHIFT_NIGHT', '22:00:00'::time, '06:00:00'::time, a_srv, 'CH-03',
            CASE WHEN d < current_date THEN 'COMPLETED' ELSE 'SCHEDULED' END,
            CASE WHEN d < current_date THEN (d + '21:55:00'::time)::timestamptz ELSE NULL END,
            CASE WHEN d < current_date THEN ((d + interval '1 day') + '06:05:00'::time)::timestamptz ELSE NULL END,
            'Bảo vệ an ninh trung tâm dữ liệu và kiểm tra khóa cửa tầng 2', now(), now()
        )
        ON CONFLICT (guard_id, shift_date, start_time) DO UPDATE
        SET status = EXCLUDED.status, check_in_at = EXCLUDED.check_in_at, check_out_at = EXCLUDED.check_out_at, updated_at = CURRENT_TIMESTAMP;
    END LOOP;
END $$;


-- ----------------------------------------------------------------------------
-- 8. NHẬT KÝ THAY ĐỔI QUYỀN (ACCESS_CONTROL_AUDIT_LOGS)
-- Trải trong 7 ngày, do 2 FM thực hiện, đúng định dạng JSON snapshot
-- (Bảng có trigger append-only: Dùng ON CONFLICT DO NOTHING)
-- ----------------------------------------------------------------------------

INSERT INTO access_control_audit_logs (id, target_type, action, target_id, area_id, subject_user_id, old_value, new_value, reason, changed_by, changed_at)
VALUES
    -- 1. FM-001 (manager.binh) phân quyền IT-001 vào Phòng Server (5 ngày trước)
    ('d1000000-0000-0000-0000-000000000001', 'AREA_ASSIGNMENT', 'ASSIGN',
     'f1000000-0000-0000-0000-000000000001',
     (SELECT id FROM areas WHERE code = 'FPTA-2-SRV'),
     (SELECT id FROM users WHERE email = 'it.cuong@fpt.edu.vn'),
     NULL,
     '{"note":"Quản trị viên máy chủ chính","validTo":null,"validFrom":"2026-09-19T08:00:00Z","revokedAt":null,"revokeReason":null}'::jsonb,
     'Phân quyền quản trị hệ thống máy chủ FPTA-2-SRV',
     (SELECT id FROM users WHERE email = 'manager.binh@fpt.edu.vn'),
     now() - interval '5 days'),

    -- 2. FM-001 (manager.binh) nâng cấp user access level cho IT-001 (4 ngày trước)
    ('d1000000-0000-0000-0000-000000000002', 'USER_ACCESS_LEVEL', 'UPDATE',
     (SELECT id::text FROM users WHERE email = 'it.cuong@fpt.edu.vn'),
     NULL,
     (SELECT id FROM users WHERE email = 'it.cuong@fpt.edu.vn'),
     '{"accessLevel":2}'::jsonb,
     '{"accessLevel":3}'::jsonb,
     'Nâng cấp cấp độ an ninh lên Level 3 cho kỹ sư phụ trách hạ tầng mạng máy chủ',
     (SELECT id FROM users WHERE email = 'manager.binh@fpt.edu.vn'),
     now() - interval '4 days'),

    -- 3. FM-002 (manager.chi) đổi quy tắc truy cập Lab IoT FPTA-1-IOT thành yêu cầu chỉ định (2 ngày trước)
    ('d1000000-0000-0000-0000-000000000003', 'AREA_ACCESS_RULES', 'UPDATE',
     (SELECT id::text FROM areas WHERE code = 'FPTA-1-IOT'),
     (SELECT id FROM areas WHERE code = 'FPTA-1-IOT'),
     NULL,
     '{"areaAccessLevel":3,"explicitAuthorizationRequired":false}'::jsonb,
     '{"areaAccessLevel":3,"explicitAuthorizationRequired":true}'::jsonb,
     'Yêu cầu chỉ định đích danh khi vào phòng Lab IoT nhằm bảo vệ thiết bị vi mạch mới',
     (SELECT id FROM users WHERE email = 'manager.chi@fpt.edu.vn'),
     now() - interval '2 days'),

    -- 4. FM-002 (manager.chi) thu hồi quyền vào phòng khảo thí trước hạn (1 ngày trước)
    ('d1000000-0000-0000-0000-000000000004', 'AREA_ASSIGNMENT', 'REVOKE',
     'f1000000-0000-0000-0000-000000000005',
     (SELECT id FROM areas WHERE code = 'FPTA-2-EXM'),
     (SELECT id FROM users WHERE email = 'student.nam@fpt.edu.vn'),
     '{"note":"Hỗ trợ công tác tổ chức thi","validTo":null,"validFrom":"2026-09-19T08:00:00Z","revokedAt":null,"revokeReason":null}'::jsonb,
     '{"note":"Hỗ trợ công tác tổ chức thi","validTo":null,"validFrom":"2026-09-19T08:00:00Z","revokedAt":"2026-09-23T10:00:00Z","revokeReason":"Hoàn thành công tác hỗ trợ coi thi trước hạn"}'::jsonb,
     'Hoàn thành công tác hỗ trợ coi thi trước hạn',
     (SELECT id FROM users WHERE email = 'manager.chi@fpt.edu.vn'),
     now() - interval '1 day')
ON CONFLICT (id) DO NOTHING;
