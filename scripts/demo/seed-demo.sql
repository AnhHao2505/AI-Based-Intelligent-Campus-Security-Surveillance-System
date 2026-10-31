-- =====================================================================================================
-- ICSS — dữ liệu DEMO (KHÔNG phải migration Flyway). Cách chạy: scripts/demo/README.md
--
-- * Chạy lại được: dữ liệu demo nhận diện bằng UUID cố định dạng d00000TT-0000-4000-a000-NNNNNNNNNNNN
--   (TT = mã bảng, xem pg_temp.did) và mã / e-mail có tiền tố DEMO- / demo.
--   - Danh mục (toà, tầng, khu, camera, đội bảo vệ, người dùng): UPSERT theo id, không xoá
--     (audit_logs append-only có thể đang trỏ tới họ sau khi demo thao tác trên giao diện).
--   - Dữ liệu nghiệp vụ (đơn, AP, lịch sự kiện, phiên, lượt khách, log ra vào, sự cố, thông báo, ca trực):
--     xoá theo tiền tố id rồi chèn lại.
-- * Mọi mốc thời gian tương đối với now(), ngày tính theo giờ Asia/Ho_Chi_Minh.
-- * Toàn bộ là dữ liệu BỊA. Mật khẩu chung của tài khoản demo: Demo@123456 (BCrypt cost 10).
-- * Không chèn audit_logs (bảng append-only); nhật ký thao tác sẽ có khi demo thao tác trên giao diện.
-- =====================================================================================================

BEGIN;

SET LOCAL TIME ZONE 'Asia/Ho_Chi_Minh';

-- UUID demo: did(mã bảng, số thứ tự)
CREATE OR REPLACE FUNCTION pg_temp.did(tbl int, n int) RETURNS uuid
    LANGUAGE sql IMMUTABLE AS
$$ SELECT ('d' || lpad(to_hex(tbl), 7, '0') || '-0000-4000-a000-' || lpad(n::text, 12, '0'))::uuid $$;

-- Mốc giờ VN: hôm nay + d ngày, lúc h:m
CREATE OR REPLACE FUNCTION pg_temp.vn(d int, h int, m int) RETURNS timestamptz
    LANGUAGE sql STABLE AS
$$ SELECT (date_trunc('day', now() AT TIME ZONE 'Asia/Ho_Chi_Minh') + make_interval(days => d, hours => h, mins => m))
          AT TIME ZONE 'Asia/Ho_Chi_Minh' $$;

-- Mã bảng dùng trong did():
--   1 users · 2 buildings/floors · 3 areas · 4 cameras · 5 access_requests · 6 access_request_members
--   7 area_assigned_personnel · 8 area_event_schedules · 9 area_event_sessions · 10 guest_visits · 11 guests
--   12 camera_access_logs · 13 security_incidents · 14 notifications · 15 guard_teams · 16 guard_shifts

-- -----------------------------------------------------------------------------------------------------
-- 0. Xoá dữ liệu nghiệp vụ demo cũ (theo thứ tự khoá ngoại)
-- -----------------------------------------------------------------------------------------------------
DELETE FROM notifications       WHERE id::text LIKE 'd000000e-%';
DELETE FROM security_incidents  WHERE id::text LIKE 'd000000d-%';   -- incident_escalations: ON DELETE CASCADE
DELETE FROM camera_access_logs  WHERE id::text LIKE 'd000000c-%';
DELETE FROM guest_visits        WHERE id::text LIKE 'd000000a-%';   -- guests, guest_visit_areas, guest_face_embeddings: CASCADE

-- Lịch ↔ phiên trỏ vòng: gỡ session_id trước, xoá phiên (kể cả phiên app sinh từ lịch demo), rồi xoá lịch
UPDATE area_event_schedules SET session_id = NULL WHERE id::text LIKE 'd0000008-%';
DELETE FROM area_event_sessions
WHERE id::text LIKE 'd0000009-%'
   OR schedule_id IN (SELECT id FROM area_event_schedules WHERE id::text LIKE 'd0000008-%');
DELETE FROM area_event_schedules WHERE id::text LIKE 'd0000008-%';

DELETE FROM area_assigned_personnel WHERE id::text LIKE 'd0000007-%';
DELETE FROM access_requests         WHERE id::text LIKE 'd0000005-%'; -- access_request_members: CASCADE
DELETE FROM guard_shifts            WHERE id::text LIKE 'd0000010-%'; -- guard_shift_requests.shift_id: SET NULL

-- -----------------------------------------------------------------------------------------------------
-- 1. Toà, tầng (toà riêng "Tòa Demo" để không đụng Alpha / Beta có sẵn)
-- -----------------------------------------------------------------------------------------------------
INSERT INTO buildings (id, name, description, total_floors, is_active)
VALUES (pg_temp.did(2, 1), 'Tòa Demo', 'Toà nhà dữ liệu demo (bịa)', 3, true)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description,
    total_floors = EXCLUDED.total_floors, is_active = true, updated_at = now();

INSERT INTO floors (id, building_id, name, floor_order, is_active)
VALUES (pg_temp.did(2, 101), pg_temp.did(2, 1), 'Tầng 1', 1, true),
       (pg_temp.did(2, 102), pg_temp.did(2, 1), 'Tầng 2', 2, true),
       (pg_temp.did(2, 103), pg_temp.did(2, 1), 'Tầng 3', 3, true)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, floor_order = EXCLUDED.floor_order, is_active = true, updated_at = now();

-- -----------------------------------------------------------------------------------------------------
-- 2. Đội bảo vệ (trước users vì users.team_id)
-- -----------------------------------------------------------------------------------------------------
INSERT INTO guard_teams (id, team_name, description, is_active)
VALUES (pg_temp.did(15, 1), 'Đội Demo', 'Đội bảo vệ dữ liệu demo (bịa)', true)
ON CONFLICT (id) DO UPDATE SET team_name = EXCLUDED.team_name, description = EXCLUDED.description,
    is_active = true, updated_at = now();

-- -----------------------------------------------------------------------------------------------------
-- 3. Người dùng — mật khẩu chung Demo@123456. ADMIN dùng tài khoản có sẵn từ migration (admin@fpt.edu.vn).
--    Cấp: FM 3, GUARD 2 (mặc định cấu hình); NORMAL_USER 1 / 2 / 3.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO users (id, full_name, user_code, role, email, password, access_level, is_active, deleted_at, team_id)
VALUES
    (pg_temp.did(1,  1), 'Trần Quốc Khánh',   'DEMO-FM-01',  'FACILITY_MANAGER', 'demo.fm1@icss.local',    '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 3, true, NULL, NULL),
    (pg_temp.did(1,  2), 'Lê Thị Hồng Nhung', 'DEMO-FM-02',  'FACILITY_MANAGER', 'demo.fm2@icss.local',    '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 3, true, NULL, NULL),
    (pg_temp.did(1, 11), 'Phạm Văn Lộc',      'DEMO-SEC-01', 'GUARD',            'demo.guard1@icss.local', '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 2, true, NULL, pg_temp.did(15, 1)),
    (pg_temp.did(1, 12), 'Đỗ Minh Tâm',       'DEMO-SEC-02', 'GUARD',            'demo.guard2@icss.local', '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 2, true, NULL, pg_temp.did(15, 1)),
    (pg_temp.did(1, 13), 'Vũ Thanh Sơn',      'DEMO-SEC-03', 'GUARD',            'demo.guard3@icss.local', '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 2, true, NULL, pg_temp.did(15, 1)),
    (pg_temp.did(1, 21), 'Nguyễn Hoàng An',   'DEMO-SV-01',  'NORMAL_USER',      'demo.user1@icss.local',  '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 1, true, NULL, NULL),
    (pg_temp.did(1, 22), 'Bùi Ngọc Bích',     'DEMO-SV-02',  'NORMAL_USER',      'demo.user2@icss.local',  '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 1, true, NULL, NULL),
    (pg_temp.did(1, 23), 'Hồ Gia Huy',        'DEMO-SV-03',  'NORMAL_USER',      'demo.user3@icss.local',  '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 1, true, NULL, NULL),
    (pg_temp.did(1, 24), 'Đặng Thu Trang',    'DEMO-GV-01',  'NORMAL_USER',      'demo.user4@icss.local',  '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 2, true, NULL, NULL),
    (pg_temp.did(1, 25), 'Lý Đức Thịnh',      'DEMO-GV-02',  'NORMAL_USER',      'demo.user5@icss.local',  '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 2, true, NULL, NULL),
    (pg_temp.did(1, 26), 'Mai Phương Thảo',   'DEMO-CB-01',  'NORMAL_USER',      'demo.user6@icss.local',  '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 3, true, NULL, NULL),
    (pg_temp.did(1, 27), 'Châu Tấn Phát',     'DEMO-SV-04',  'NORMAL_USER',      'demo.user7@icss.local',  '$2a$10$yndFPzx/peVqwiwvtufjMeKHe6WIJGbVCrfUDfG3ivQ8RbUo1TbHK', 1, true, NULL, NULL)
ON CONFLICT (id) DO UPDATE SET full_name = EXCLUDED.full_name, user_code = EXCLUDED.user_code, role = EXCLUDED.role,
    email = EXCLUDED.email, password = EXCLUDED.password, access_level = EXCLUDED.access_level,
    is_active = true, deleted_at = NULL, team_id = EXCLUDED.team_id, updated_at = now();

-- -----------------------------------------------------------------------------------------------------
-- 4. Khu vực: đủ 3 loại, 3 khu Bảo mật đang hoạt động, 1 khu đã vô hiệu hoá.
--    A4 đang mở chế độ sự kiện theo lịch S2 (open_to_members / open_until đặt ở mục 8).
-- -----------------------------------------------------------------------------------------------------
INSERT INTO areas (id, name, area_level, area_access_level, building, floor, floor_id, is_active, deleted_at,
                   open_to_members, open_until, center_latitude, center_longitude)
VALUES
    (pg_temp.did(3, 1), 'Sảnh chính Demo',        'PUBLIC',              1, 'Tòa Demo', 'Tầng 1', pg_temp.did(2, 101), true,  NULL, false, NULL, 10.84125, 106.80990),
    (pg_temp.did(3, 2), 'Thư viện Demo',          'PUBLIC',              1, 'Tòa Demo', 'Tầng 2', pg_temp.did(2, 102), true,  NULL, false, NULL, 10.84130, 106.81002),
    (pg_temp.did(3, 3), 'Phòng Lab AI Demo',      'CONFIDENTIAL',        2, 'Tòa Demo', 'Tầng 2', pg_temp.did(2, 102), true,  NULL, false, NULL, 10.84118, 106.81010),
    (pg_temp.did(3, 4), 'Phòng họp Hội đồng Demo','CONFIDENTIAL',        2, 'Tòa Demo', 'Tầng 3', pg_temp.did(2, 103), true,  NULL, false, NULL, 10.84122, 106.81018),
    (pg_temp.did(3, 5), 'Kho thiết bị Demo',      'CONFIDENTIAL',        2, 'Tòa Demo', 'Tầng 1', pg_temp.did(2, 101), true,  NULL, false, NULL, 10.84110, 106.80985),
    (pg_temp.did(3, 6), 'Phòng Server Demo',      'HIGHLY_CONFIDENTIAL', 3, 'Tòa Demo', 'Tầng 3', pg_temp.did(2, 103), true,  NULL, false, NULL, 10.84115, 106.81025),
    (pg_temp.did(3, 7), 'Phòng Lab cũ Demo',      'CONFIDENTIAL',        2, 'Tòa Demo', 'Tầng 1', pg_temp.did(2, 101), false, pg_temp.vn(-10, 9, 0), false, NULL, 10.84105, 106.80995)
ON CONFLICT (id) DO UPDATE SET name = EXCLUDED.name, area_level = EXCLUDED.area_level,
    area_access_level = EXCLUDED.area_access_level, building = EXCLUDED.building, floor = EXCLUDED.floor,
    floor_id = EXCLUDED.floor_id, is_active = EXCLUDED.is_active, deleted_at = EXCLUDED.deleted_at,
    open_to_members = false, open_until = NULL, center_latitude = EXCLUDED.center_latitude,
    center_longitude = EXCLUDED.center_longitude, version = areas.version + 1, updated_at = now();

-- -----------------------------------------------------------------------------------------------------
-- 5. Camera: 1 camera / khu đang hoạt động; ACTIVE nhưng OFFLINE (môi trường demo không có luồng hình).
--    Khu đã vô hiệu hoá không có camera (BR vô hiệu hoá khu yêu cầu gỡ camera).
-- -----------------------------------------------------------------------------------------------------
INSERT INTO cameras (id, camera_code, name, status, operational_status, area_id, deleted_at)
VALUES
    (pg_temp.did(4, 1), 'DEMO-CAM-01', 'Camera Sảnh chính Demo',     'ACTIVE', 'OFFLINE', pg_temp.did(3, 1), NULL),
    (pg_temp.did(4, 2), 'DEMO-CAM-02', 'Camera Thư viện Demo',       'ACTIVE', 'OFFLINE', pg_temp.did(3, 2), NULL),
    (pg_temp.did(4, 3), 'DEMO-CAM-03', 'Camera Lab AI Demo',         'ACTIVE', 'OFFLINE', pg_temp.did(3, 3), NULL),
    (pg_temp.did(4, 4), 'DEMO-CAM-04', 'Camera Phòng họp Demo',      'ACTIVE', 'OFFLINE', pg_temp.did(3, 4), NULL),
    (pg_temp.did(4, 5), 'DEMO-CAM-05', 'Camera Kho thiết bị Demo',   'ACTIVE', 'OFFLINE', pg_temp.did(3, 5), NULL),
    (pg_temp.did(4, 6), 'DEMO-CAM-06', 'Camera Phòng Server Demo',   'ACTIVE', 'OFFLINE', pg_temp.did(3, 6), NULL)
ON CONFLICT (id) DO UPDATE SET camera_code = EXCLUDED.camera_code, name = EXCLUDED.name, status = EXCLUDED.status,
    operational_status = EXCLUDED.operational_status, area_id = EXCLUDED.area_id, deleted_at = NULL, updated_at = now();

-- -----------------------------------------------------------------------------------------------------
-- 6. Đơn truy cập — mọi trạng thái. Quy tắc (BR-RQ-01, V83):
--    INDIVIDUAL: người tạo thấp hơn khu đúng 1 cấp (ACCESS_REQUEST_MAX_LEVEL_GAP = 1).
--    GROUP: chỉ khu Bảo mật; người tạo đủ cấp khu; thành viên cấp thấp hơn = sponsored.
--    Không có cặp (người, khu) nào trùng giờ ở PENDING / APPROVED. Duyệt luôn trước giờ bắt đầu.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO access_requests (id, area_id, requester_id, request_type, purpose, start_time, end_time, status,
                             reviewer_id, reviewed_at, rejection_reason, cancelled_by, cancel_source, cancel_reason,
                             finish_reason, finished_by, created_at, updated_at)
VALUES
    -- R01 PENDING tương lai (SV-01 cấp 1 → Lab AI cấp 2)
    (pg_temp.did(5, 1), pg_temp.did(3, 3), pg_temp.did(1, 21), 'INDIVIDUAL',
     'Thực hành đồ án môn Trí tuệ nhân tạo tại phòng Lab', pg_temp.vn(2, 9, 0), pg_temp.vn(2, 11, 0), 'PENDING',
     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '3 hours', now() - interval '3 hours'),
    -- R02 PENDING đã quá giờ bắt đầu, job chưa chạy (job 15 phút sẽ chuyển EXPIRED)
    (pg_temp.did(5, 2), pg_temp.did(3, 3), pg_temp.did(1, 22), 'INDIVIDUAL',
     'Lấy dữ liệu thực nghiệm còn lưu trên máy Lab', now() - interval '10 minutes', now() + interval '110 minutes', 'PENDING',
     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '1 day', now() - interval '1 day'),
    -- R03 APPROVED chưa bắt đầu
    (pg_temp.did(5, 3), pg_temp.did(3, 4), pg_temp.did(1, 21), 'INDIVIDUAL',
     'Dự buổi góp ý đề cương khoá luận cùng giảng viên', pg_temp.vn(1, 14, 0), pg_temp.vn(1, 16, 0), 'APPROVED',
     pg_temp.did(1, 1), now() - interval '20 hours', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '2 days', now() - interval '20 hours'),
    -- R04 APPROVED đang diễn ra
    (pg_temp.did(5, 4), pg_temp.did(3, 5), pg_temp.did(1, 22), 'INDIVIDUAL',
     'Mượn thiết bị đo cho bài thực hành Vật lý', now() - interval '1 hour', now() + interval '2 hours', 'APPROVED',
     pg_temp.did(1, 1), now() - interval '1 day', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '2 days', now() - interval '1 day'),
    -- R05 REJECTED
    (pg_temp.did(5, 5), pg_temp.did(3, 3), pg_temp.did(1, 23), 'INDIVIDUAL',
     'Tự học buổi tối tại phòng Lab', pg_temp.vn(3, 18, 0), pg_temp.vn(3, 21, 0), 'REJECTED',
     pg_temp.did(1, 2), now() - interval '5 hours', 'Phòng Lab không mở cho tự học buổi tối, vui lòng dùng thư viện',
     NULL, NULL, NULL, NULL, NULL, now() - interval '1 day', now() - interval '5 hours'),
    -- R06 CANCELLED do người tạo huỷ khi còn PENDING
    (pg_temp.did(5, 6), pg_temp.did(3, 4), pg_temp.did(1, 23), 'INDIVIDUAL',
     'Chuẩn bị phòng cho buổi sinh hoạt câu lạc bộ', pg_temp.vn(4, 8, 0), pg_temp.vn(4, 10, 0), 'CANCELLED',
     NULL, NULL, NULL, pg_temp.did(1, 23), 'USER', 'Câu lạc bộ đã đổi sang địa điểm khác',
     NULL, NULL, now() - interval '2 days', now() - interval '1 day'),
    -- R07 CANCELLED do FM huỷ đơn APPROVED chưa bắt đầu (STAFF)
    (pg_temp.did(5, 7), pg_temp.did(3, 5), pg_temp.did(1, 27), 'INDIVIDUAL',
     'Kiểm kê thiết bị phòng thí nghiệm theo phân công', pg_temp.vn(2, 13, 0), pg_temp.vn(2, 15, 0), 'CANCELLED',
     pg_temp.did(1, 1), now() - interval '2 days', NULL, pg_temp.did(1, 1), 'STAFF', 'Kho thiết bị tạm đóng để bảo trì hệ thống điện',
     NULL, NULL, now() - interval '3 days', now() - interval '6 hours'),
    -- R08 EXPIRED (PENDING quá giờ, job đã chuyển)
    (pg_temp.did(5, 8), pg_temp.did(3, 3), pg_temp.did(1, 27), 'INDIVIDUAL',
     'Cài đặt môi trường lập trình cho nhóm', pg_temp.vn(-3, 9, 0), pg_temp.vn(-3, 11, 0), 'EXPIRED',
     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, pg_temp.vn(-5, 10, 0), pg_temp.vn(-3, 9, 15)),
    -- R09 FINISHED do FM kết thúc sau khi đã bắt đầu
    (pg_temp.did(5, 9), pg_temp.did(3, 5), pg_temp.did(1, 21), 'INDIVIDUAL',
     'Hỗ trợ kiểm kê cuối kỳ tại kho thiết bị', pg_temp.vn(-2, 8, 0), pg_temp.vn(-2, 12, 0), 'FINISHED',
     pg_temp.did(1, 2), pg_temp.vn(-3, 15, 0), NULL, NULL, NULL, NULL,
     'Đã hoàn tất kiểm kê sớm hơn dự kiến', pg_temp.did(1, 2), pg_temp.vn(-4, 9, 0), pg_temp.vn(-2, 10, 30)),
    -- R10 FINISHED do hệ thống (hết giờ; finish_reason = AccessRequestService.FINISH_REASON_ELAPSED, finished_by NULL)
    (pg_temp.did(5, 10), pg_temp.did(3, 4), pg_temp.did(1, 22), 'INDIVIDUAL',
     'Tham dự buổi seminar chuyên đề an toàn thông tin', pg_temp.vn(-5, 13, 0), pg_temp.vn(-5, 15, 0), 'FINISHED',
     pg_temp.did(1, 1), pg_temp.vn(-6, 10, 0), NULL, NULL, NULL, NULL,
     'Hết thời gian hiệu lực', NULL, pg_temp.vn(-7, 9, 0), pg_temp.vn(-5, 15, 0)),
    -- R11 GROUP PENDING tương lai: GV-01 (cấp 2) bảo lãnh 2 SV cấp 1, kèm GV-02 cấp 2 (không cần bảo lãnh)
    (pg_temp.did(5, 11), pg_temp.did(3, 3), pg_temp.did(1, 24), 'GROUP',
     'Hướng dẫn nhóm sinh viên chạy thử mô hình nhận diện', pg_temp.vn(5, 8, 0), pg_temp.vn(5, 11, 0), 'PENDING',
     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '4 hours', now() - interval '4 hours'),
    -- R12 GROUP APPROVED đang diễn ra: CB-01 (cấp 3) bảo lãnh SV-04, kèm GV-02
    (pg_temp.did(5, 12), pg_temp.did(3, 3), pg_temp.did(1, 26), 'GROUP',
     'Buổi demo hệ thống giám sát cho nhóm nghiên cứu', now() - interval '30 minutes', now() + interval '3 hours', 'APPROVED',
     pg_temp.did(1, 2), now() - interval '1 day', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '2 days', now() - interval '1 day'),
    -- R13 PENDING khu Tuyệt mật (GV-01 cấp 2 → Phòng Server cấp 3)
    (pg_temp.did(5, 13), pg_temp.did(3, 6), pg_temp.did(1, 24), 'INDIVIDUAL',
     'Kiểm tra máy chủ lưu trữ dữ liệu nghiên cứu', pg_temp.vn(1, 10, 0), pg_temp.vn(1, 12, 0), 'PENDING',
     NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '90 minutes', now() - interval '90 minutes');

INSERT INTO access_request_members (id, access_request_id, user_id, sponsored)
VALUES
    (pg_temp.did(6, 1), pg_temp.did(5, 11), pg_temp.did(1, 21), true),
    (pg_temp.did(6, 2), pg_temp.did(5, 11), pg_temp.did(1, 23), true),
    (pg_temp.did(6, 3), pg_temp.did(5, 11), pg_temp.did(1, 25), false),
    (pg_temp.did(6, 4), pg_temp.did(5, 12), pg_temp.did(1, 27), true),
    (pg_temp.did(6, 5), pg_temp.did(5, 12), pg_temp.did(1, 25), false);

-- -----------------------------------------------------------------------------------------------------
-- 7. Nhân sự chỉ định (AP): UPCOMING / ACTIVE / EXPIRED / REVOKED. Không khu Công khai, không chồng giờ.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO area_assigned_personnel (id, area_id, user_id, valid_from, valid_to, note, revoked_at, revoked_by, revoke_reason,
                                     created_by, created_at, updated_at)
VALUES
    -- UPCOMING
    (pg_temp.did(7, 1), pg_temp.did(3, 6), pg_temp.did(1, 23), pg_temp.vn(1, 8, 0), pg_temp.vn(8, 18, 0),
     'Hỗ trợ di dời máy chủ tuần tới', NULL, NULL, NULL, pg_temp.did(1, 1), now() - interval '1 day', now() - interval '1 day'),
    -- ACTIVE có hạn
    (pg_temp.did(7, 2), pg_temp.did(3, 6), pg_temp.did(1, 25), pg_temp.vn(-3, 8, 0), pg_temp.vn(4, 18, 0),
     'Phụ trách sao lưu dữ liệu nghiên cứu', NULL, NULL, NULL, pg_temp.did(1, 1), pg_temp.vn(-4, 9, 0), pg_temp.vn(-4, 9, 0)),
    -- ACTIVE không thời hạn (bảo vệ trực phòng Server)
    (pg_temp.did(7, 3), pg_temp.did(3, 6), pg_temp.did(1, 11), pg_temp.vn(-10, 0, 0), NULL,
     'Bảo vệ tuần tra khu Tuyệt mật', NULL, NULL, NULL, pg_temp.did(1, 2), pg_temp.vn(-11, 9, 0), pg_temp.vn(-11, 9, 0)),
    -- EXPIRED
    (pg_temp.did(7, 4), pg_temp.did(3, 4), pg_temp.did(1, 22), pg_temp.vn(-20, 8, 0), pg_temp.vn(-10, 18, 0),
     'Trợ giảng hội thảo khoa học', NULL, NULL, NULL, pg_temp.did(1, 2), pg_temp.vn(-21, 9, 0), pg_temp.vn(-21, 9, 0)),
    -- REVOKED (người dùng)
    (pg_temp.did(7, 5), pg_temp.did(3, 5), pg_temp.did(1, 27), pg_temp.vn(-5, 8, 0), pg_temp.vn(5, 18, 0),
     'Hỗ trợ kiểm kê', pg_temp.vn(-1, 16, 0), pg_temp.did(1, 2), 'Đã hoàn thành công việc kiểm kê, không cần quyền nữa',
     pg_temp.did(1, 1), pg_temp.vn(-6, 9, 0), pg_temp.vn(-1, 16, 0)),
    -- REVOKED (bảo vệ)
    (pg_temp.did(7, 6), pg_temp.did(3, 5), pg_temp.did(1, 12), pg_temp.vn(-7, 8, 0), pg_temp.vn(7, 18, 0),
     'Tăng cường tuần tra kho', pg_temp.vn(-2, 10, 0), pg_temp.did(1, 1), 'Điều chuyển sang tuần tra khu Thư viện',
     pg_temp.did(1, 2), pg_temp.vn(-8, 9, 0), pg_temp.vn(-2, 10, 0));

-- -----------------------------------------------------------------------------------------------------
-- 8. Lịch sự kiện (chỉ khu Bảo mật; mỗi lịch ≤ 12 giờ; ngân sách 48 giờ / 7 ngày / khu không vượt)
--    S1 SCHEDULED · S2 STARTED (phiên đang mở ở A4) · S3 COMPLETED · S4 ENDED_EARLY · S5 CANCELLED · S6 FAILED
-- -----------------------------------------------------------------------------------------------------
INSERT INTO area_event_schedules (id, area_id, title, start_at, end_at, status, reason_code, reason_label, note,
                                  created_by, created_at, updated_by, updated_at, cancelled_by, cancelled_at,
                                  cancel_reason_code, cancel_reason_label, cancel_note, failed_at, fail_reason, session_id)
VALUES
    (pg_temp.did(8, 1), pg_temp.did(3, 3), 'Hội thảo AI ứng dụng trong giám sát', pg_temp.vn(2, 14, 0), pg_temp.vn(2, 17, 0),
     'SCHEDULED', 'SEMINAR', 'Hội thảo / seminar', 'Mở cửa cho sinh viên đăng ký tham dự',
     pg_temp.did(1, 1), now() - interval '1 day', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (pg_temp.did(8, 2), pg_temp.did(3, 4), 'Buổi bảo vệ đồ án tốt nghiệp', now() - interval '1 hour', now() + interval '3 hours',
     'STARTED', 'PLANNED_EVENT', 'Sự kiện theo kế hoạch', 'Hội đồng chấm đồ án đợt 1',
     pg_temp.did(1, 2), now() - interval '2 days', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (pg_temp.did(8, 3), pg_temp.did(3, 5), 'Tham quan kho thiết bị cho tân sinh viên', pg_temp.vn(-2, 9, 0), pg_temp.vn(-2, 11, 0),
     'COMPLETED', 'VISIT', 'Tham quan', NULL,
     pg_temp.did(1, 1), pg_temp.vn(-6, 10, 0), NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (pg_temp.did(8, 4), pg_temp.did(3, 3), 'Workshop thị giác máy tính', pg_temp.vn(-1, 13, 0), pg_temp.vn(-1, 17, 0),
     'ENDED_EARLY', 'SEMINAR', 'Hội thảo / seminar', NULL,
     pg_temp.did(1, 1), pg_temp.vn(-5, 10, 0), NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
    (pg_temp.did(8, 5), pg_temp.did(3, 5), 'Ngày hội kiểm kê thiết bị', pg_temp.vn(3, 8, 0), pg_temp.vn(3, 10, 0),
     'CANCELLED', 'PLANNED_EVENT', 'Sự kiện theo kế hoạch', NULL,
     pg_temp.did(1, 2), now() - interval '3 days', NULL, NULL, pg_temp.did(1, 2), now() - interval '2 hours',
     'EVENT_CANCELLED', 'Sự kiện bị huỷ', 'Ban tổ chức hoãn sang học kỳ sau', NULL, NULL, NULL),
    (pg_temp.did(8, 6), pg_temp.did(3, 3), 'Buổi giới thiệu phòng Lab', pg_temp.vn(-4, 9, 0), pg_temp.vn(-4, 10, 0),
     'FAILED', 'VISIT', 'Tham quan', NULL,
     pg_temp.did(1, 1), pg_temp.vn(-8, 10, 0), NULL, NULL, NULL, NULL, NULL, NULL, NULL,
     pg_temp.vn(-4, 9, 0), 'Khu vực đang có phiên sự kiện khác tại thời điểm bắt đầu', NULL);

INSERT INTO area_event_sessions (id, area_id, started_at, planned_end, actual_end, started_by, ended_by, created_at, schedule_id)
VALUES
    -- S2: phiên đang mở
    (pg_temp.did(9, 1), pg_temp.did(3, 4), now() - interval '1 hour', now() + interval '3 hours', NULL,
     pg_temp.did(1, 2), NULL, now() - interval '1 hour', pg_temp.did(8, 2)),
    -- S3: đóng do hết giờ
    (pg_temp.did(9, 2), pg_temp.did(3, 5), pg_temp.vn(-2, 9, 0), pg_temp.vn(-2, 11, 0), pg_temp.vn(-2, 11, 0),
     pg_temp.did(1, 1), NULL, pg_temp.vn(-2, 9, 0), pg_temp.did(8, 3)),
    -- S4: FM tắt sớm
    (pg_temp.did(9, 3), pg_temp.did(3, 3), pg_temp.vn(-1, 13, 0), pg_temp.vn(-1, 17, 0), pg_temp.vn(-1, 15, 30),
     pg_temp.did(1, 1), pg_temp.did(1, 1), pg_temp.vn(-1, 13, 0), pg_temp.did(8, 4));

UPDATE area_event_schedules SET session_id = pg_temp.did(9, 1) WHERE id = pg_temp.did(8, 2);
UPDATE area_event_schedules SET session_id = pg_temp.did(9, 2) WHERE id = pg_temp.did(8, 3);
UPDATE area_event_schedules SET session_id = pg_temp.did(9, 3) WHERE id = pg_temp.did(8, 4);

-- A4 đang mở chế độ sự kiện tới hết lịch S2 (như AreaService khi lịch bắt đầu)
UPDATE areas SET open_to_members = true, open_until = now() + interval '3 hours' WHERE id = pg_temp.did(3, 4);

-- -----------------------------------------------------------------------------------------------------
-- 9. Lượt khách — mọi trạng thái. Host cấp ≥ GUEST_HOST_MIN_LEVEL (2), khu Bảo mật, ≤ 8 giờ, ≤ 14 ngày tới.
--    Không ảnh thật: khách sắp tới / đang diễn ra NO_PHOTO (thiếu ảnh), khách lượt đã xong DELETED.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO guest_visits (id, host_id, purpose, start_time, end_time, status, reviewed_by, reviewed_at, review_reason,
                          revoked_by, revoked_at, revoke_reason, cancelled_at, cancel_reason, closed_at, created_at, updated_at)
VALUES
    (pg_temp.did(10, 1), pg_temp.did(1, 24), 'Đối tác doanh nghiệp tham quan phòng Lab AI', pg_temp.vn(1, 9, 0), pg_temp.vn(1, 12, 0),
     'PENDING', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '2 hours', now() - interval '2 hours'),
    (pg_temp.did(10, 2), pg_temp.did(1, 26), 'Giảng viên thỉnh giảng dự buổi chấm đồ án', pg_temp.vn(2, 13, 0), pg_temp.vn(2, 16, 0),
     'APPROVED', pg_temp.did(1, 1), now() - interval '5 hours', NULL, NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '1 day', now() - interval '5 hours'),
    (pg_temp.did(10, 3), pg_temp.did(1, 25), 'Kỹ thuật viên nhà cung cấp bảo trì thiết bị', now() - interval '1 hour', now() + interval '2 hours',
     'APPROVED', pg_temp.did(1, 2), now() - interval '1 day', NULL, NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '2 days', now() - interval '1 day'),
    (pg_temp.did(10, 4), pg_temp.did(1, 24), 'Phụ huynh tham quan phòng thí nghiệm', pg_temp.vn(3, 9, 0), pg_temp.vn(3, 11, 0),
     'REJECTED', pg_temp.did(1, 1), now() - interval '3 hours', 'Phòng Lab đang chạy thực nghiệm, mời đăng ký lại tuần sau',
     NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '1 day', now() - interval '3 hours'),
    (pg_temp.did(10, 5), pg_temp.did(1, 26), 'Cựu sinh viên về chia sẻ kinh nghiệm', pg_temp.vn(4, 14, 0), pg_temp.vn(4, 16, 0),
     'CANCELLED', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '3 hours', 'Khách bận đột xuất, xin dời lịch',
     NULL, now() - interval '2 days', now() - interval '3 hours'),
    (pg_temp.did(10, 6), pg_temp.did(1, 25), 'Nhóm sinh viên trường bạn giao lưu học thuật', pg_temp.vn(1, 14, 0), pg_temp.vn(1, 16, 0),
     'REVOKED', pg_temp.did(1, 1), now() - interval '1 day', NULL,
     pg_temp.did(1, 2), now() - interval '1 hour', 'Khu vực tạm đóng để kiểm tra an ninh', NULL, NULL, NULL,
     now() - interval '2 days', now() - interval '1 hour'),
    (pg_temp.did(10, 7), pg_temp.did(1, 24), 'Khách mời dự buổi trao đổi chuyên môn', pg_temp.vn(-2, 9, 0), pg_temp.vn(-2, 11, 0),
     'EXPIRED', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, pg_temp.vn(-2, 9, 1), pg_temp.vn(-4, 10, 0), pg_temp.vn(-2, 9, 1)),
    (pg_temp.did(10, 8), pg_temp.did(1, 26), 'Chuyên gia đánh giá thiết bị phòng Lab', pg_temp.vn(-3, 13, 0), pg_temp.vn(-3, 15, 0),
     'COMPLETED', pg_temp.did(1, 1), pg_temp.vn(-5, 10, 0), NULL, NULL, NULL, NULL, NULL, NULL, pg_temp.vn(-3, 15, 1),
     pg_temp.vn(-6, 10, 0), pg_temp.vn(-3, 15, 1));

INSERT INTO guest_visit_areas (visit_id, area_id)
VALUES (pg_temp.did(10, 1), pg_temp.did(3, 3)), (pg_temp.did(10, 1), pg_temp.did(3, 4)),
       (pg_temp.did(10, 2), pg_temp.did(3, 4)),
       (pg_temp.did(10, 3), pg_temp.did(3, 5)),
       (pg_temp.did(10, 4), pg_temp.did(3, 3)),
       (pg_temp.did(10, 5), pg_temp.did(3, 4)),
       (pg_temp.did(10, 6), pg_temp.did(3, 3)),
       (pg_temp.did(10, 7), pg_temp.did(3, 5)),
       (pg_temp.did(10, 8), pg_temp.did(3, 3));

INSERT INTO guests (id, visit_id, full_name, organization, biometric_status, photo_object_key,
                    consent_confirmed_by, consent_confirmed_at, consent_notice_version, photo_attached_at,
                    biometric_deleted_at, created_at, updated_at)
VALUES
    (pg_temp.did(11, 1),  pg_temp.did(10, 1), 'Ngô Bảo Long',      'Công ty TNHH Giải pháp Số Demo', 'NO_PHOTO', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '2 hours', now() - interval '2 hours'),
    (pg_temp.did(11, 2),  pg_temp.did(10, 1), 'Tạ Thị Mỹ Duyên',   'Công ty TNHH Giải pháp Số Demo', 'NO_PHOTO', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '2 hours', now() - interval '2 hours'),
    (pg_temp.did(11, 3),  pg_temp.did(10, 2), 'Lâm Quang Vinh',    'Trường Đại học Demo',             'NO_PHOTO', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '1 day', now() - interval '1 day'),
    (pg_temp.did(11, 4),  pg_temp.did(10, 3), 'Kiều Văn Hậu',      'Nhà cung cấp Thiết bị Demo',      'NO_PHOTO', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '2 days', now() - interval '2 days'),
    (pg_temp.did(11, 5),  pg_temp.did(10, 4), 'Huỳnh Kim Oanh',    NULL,                              'NO_PHOTO', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '1 day', now() - interval '1 day'),
    (pg_temp.did(11, 6),  pg_temp.did(10, 5), 'Trịnh Công Danh',   'Cựu sinh viên khoá Demo',         'NO_PHOTO', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '2 days', now() - interval '2 days'),
    (pg_temp.did(11, 7),  pg_temp.did(10, 6), 'Âu Dương Khải',     'Trường Đại học Bạn Demo',         'NO_PHOTO', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '2 days', now() - interval '2 days'),
    (pg_temp.did(11, 8),  pg_temp.did(10, 6), 'Quách Ngọc Hân',    'Trường Đại học Bạn Demo',         'NO_PHOTO', NULL, NULL, NULL, NULL, NULL, NULL, now() - interval '2 days', now() - interval '2 days'),
    (pg_temp.did(11, 9),  pg_temp.did(10, 7), 'Doãn Thế Bảo',      'Viện Nghiên cứu Demo',            'NO_PHOTO', NULL, NULL, NULL, NULL, NULL, NULL, pg_temp.vn(-4, 10, 0), pg_temp.vn(-4, 10, 0)),
    -- Lượt đã xong: ảnh đã chụp có đồng ý rồi bị xoá sinh trắc sau lượt (không có ảnh thật)
    (pg_temp.did(11, 10), pg_temp.did(10, 8), 'Văn Thị Ngọc Ánh',  'Công ty Kiểm định Demo',          'DELETED',  NULL, pg_temp.did(1, 1), pg_temp.vn(-3, 12, 30), 'v1', pg_temp.vn(-3, 12, 30), pg_temp.vn(-2, 15, 5), pg_temp.vn(-6, 10, 0), pg_temp.vn(-2, 15, 5));

-- -----------------------------------------------------------------------------------------------------
-- 10. Log ra vào 30 ngày, đủ 5 kết quả. Người được ghi nhận đúng cấp với kết quả:
--     AUTHORIZED: cấp ≥ cấp khu · UNAUTHORIZED: cấp < cấp khu (khu không Công khai) · OUT_OF_TIME: cấp 1 ở khu Bảo mật
--     ngoài khung giờ đơn · UNKNOWN_PERSON: không xác định · INTRUSION (sau giờ hoạt động): 22:00–23:59.
-- -----------------------------------------------------------------------------------------------------
CREATE TEMP TABLE demo_cam ON COMMIT DROP AS
SELECT * FROM (VALUES
    (1, 'DEMO-CAM-01', pg_temp.did(3, 1), 1),
    (2, 'DEMO-CAM-02', pg_temp.did(3, 2), 1),
    (3, 'DEMO-CAM-03', pg_temp.did(3, 3), 2),
    (4, 'DEMO-CAM-04', pg_temp.did(3, 4), 2),
    (5, 'DEMO-CAM-05', pg_temp.did(3, 5), 2),
    (6, 'DEMO-CAM-06', pg_temp.did(3, 6), 3)
) AS t(idx, code, area_id, lvl);

CREATE TEMP TABLE demo_usr ON COMMIT DROP AS
SELECT u.id, u.user_code, u.full_name, u.access_level AS lvl,
       row_number() OVER (ORDER BY u.user_code) AS idx
FROM users u
WHERE u.id IN (pg_temp.did(1, 21), pg_temp.did(1, 22), pg_temp.did(1, 23), pg_temp.did(1, 24),
               pg_temp.did(1, 25), pg_temp.did(1, 26), pg_temp.did(1, 27));

CREATE TEMP TABLE demo_log_plan ON COMMIT DROP AS
SELECT n,
       CASE WHEN n % 20 < 13 THEN 'AUTHORIZED_ACCESS'
            WHEN n % 20 < 15 THEN 'UNAUTHORIZED_ACCESS'
            WHEN n % 20 = 15 THEN 'ACCESS_OUT_OF_TIME'
            WHEN n % 20 < 18 THEN 'UNKNOWN_PERSON'
            ELSE 'INTRUSION' END AS result,
       1 + (n % 29) AS days_ago,
       n % 60 AS minute
FROM generate_series(1, 360) AS n;

INSERT INTO camera_access_logs (id, camera_code, area_id, user_id, user_code, full_name, user_access_level,
                                access_result, access_source, reason_details, logged_at)
SELECT pg_temp.did(12, p.n),
       c.code, c.area_id,
       u.id, COALESCE(u.user_code, 'UNKNOWN'), u.full_name, u.lvl,
       p.result,
       CASE WHEN p.result = 'AUTHORIZED_ACCESS' THEN 'ACCESS_LEVEL' ELSE 'NONE' END,
       CASE p.result
           WHEN 'AUTHORIZED_ACCESS'   THEN 'Cấp truy cập đáp ứng yêu cầu khu vực'
           WHEN 'UNAUTHORIZED_ACCESS' THEN 'Cấp truy cập thấp hơn yêu cầu khu vực, không có đơn hay quyền chỉ định'
           WHEN 'ACCESS_OUT_OF_TIME'  THEN 'Ngoài khung giờ của yêu cầu truy cập đã duyệt'
           WHEN 'UNKNOWN_PERSON'      THEN 'Không nhận diện được khuôn mặt'
           ELSE 'Phát hiện người trong khu vực ngoài giờ hoạt động' END,
       pg_temp.vn(-p.days_ago,
                  CASE p.result WHEN 'INTRUSION' THEN 22 + (p.n % 2)
                                WHEN 'ACCESS_OUT_OF_TIME' THEN 18 + (p.n % 3)
                                ELSE 7 + (p.n % 11) END,
                  p.minute)
FROM demo_log_plan p
JOIN demo_cam c ON c.idx = CASE
        WHEN p.result IN ('AUTHORIZED_ACCESS', 'UNKNOWN_PERSON') THEN 1 + (p.n % 6)
        WHEN p.result = 'ACCESS_OUT_OF_TIME' THEN 3 + (p.n % 3)
        ELSE 3 + (p.n % 4) END
LEFT JOIN LATERAL (
    -- Chọn xoay vòng một người trong nhóm hợp lệ với kết quả (UNKNOWN_PERSON: không ai)
    SELECT q.id, q.user_code, q.full_name, q.lvl
    FROM (
        SELECT du.*, row_number() OVER (ORDER BY du.idx) AS rn, count(*) OVER () AS cnt
        FROM demo_usr du
        WHERE p.result <> 'UNKNOWN_PERSON'
          AND ((p.result = 'AUTHORIZED_ACCESS' AND du.lvl >= c.lvl)
            OR (p.result IN ('UNAUTHORIZED_ACCESS', 'INTRUSION') AND du.lvl < c.lvl)
            OR (p.result = 'ACCESS_OUT_OF_TIME' AND du.lvl = 1))
    ) q
    WHERE q.rn = 1 + (p.n % q.cnt)
) u ON true;

-- Hôm nay: 12 lượt trong vài giờ gần đây (để Dashboard "Hôm nay" có số)
INSERT INTO camera_access_logs (id, camera_code, area_id, user_id, user_code, full_name, user_access_level,
                                access_result, access_source, reason_details, logged_at)
SELECT pg_temp.did(12, 1000 + k),
       c.code, c.area_id, u.id, COALESCE(u.user_code, 'UNKNOWN'), u.full_name, u.access_level,
       CASE WHEN k = 4 THEN 'UNKNOWN_PERSON' WHEN k = 9 THEN 'UNAUTHORIZED_ACCESS' ELSE 'AUTHORIZED_ACCESS' END,
       CASE WHEN k IN (4, 9) THEN 'NONE' ELSE 'ACCESS_LEVEL' END,
       CASE WHEN k = 4 THEN 'Không nhận diện được khuôn mặt'
            WHEN k = 9 THEN 'Cấp truy cập thấp hơn yêu cầu khu vực, không có đơn hay quyền chỉ định'
            ELSE 'Cấp truy cập đáp ứng yêu cầu khu vực' END,
       now() - make_interval(mins => 17 * k)
FROM generate_series(1, 12) AS k
JOIN demo_cam c ON c.idx = CASE WHEN k = 9 THEN 6 WHEN k % 3 = 0 THEN 3 ELSE 1 + (k % 2) END
LEFT JOIN users u ON k <> 4 AND u.id = CASE WHEN k = 9 THEN pg_temp.did(1, 21)
                                             WHEN k % 3 = 0 THEN pg_temp.did(1, 24)
                                             ELSE pg_temp.did(1, 20 + (k % 7) + 1) END;

-- -----------------------------------------------------------------------------------------------------
-- 11. Sự cố an ninh: đủ 4 trạng thái ở nhiều khu (Dashboard FM: Phòng Server nhiều nhất).
--     Không ảnh (môi trường demo không có MinIO). Sự cố NEW gần đây sẽ được job leo thang xử lý như thật.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO security_incidents (id, event_id, camera_code, area_id, building, event_type, detected_at, status,
                                claimed_by, claimed_at, resolved_by, resolved_at, outcome, resolution_category,
                                resolution_notes, created_at, updated_at)
SELECT pg_temp.did(13, v.n), 'DEMO-EVT-' || lpad(v.n::text, 3, '0'), c.code, c.area_id, 'Tòa Demo', v.event_type,
       v.detected_at, v.status,
       v.claimed_by, CASE WHEN v.claimed_by IS NOT NULL THEN v.detected_at + interval '6 minutes' END,
       v.resolved_by, CASE WHEN v.resolved_by IS NOT NULL THEN v.detected_at + interval '25 minutes' END,
       v.outcome, v.category, v.notes, v.detected_at, COALESCE(v.detected_at + interval '25 minutes', v.detected_at)
FROM (VALUES
    ( 1, 6, 'INTRUSION',           now() - interval '8 minutes',  'NEW',               NULL::uuid,          NULL::uuid,          NULL,       NULL,                  NULL),
    ( 2, 3, 'UNKNOWN_PERSON',      now() - interval '25 minutes', 'NEW',               NULL,                NULL,                NULL,       NULL,                  NULL),
    ( 3, 5, 'UNAUTHORIZED_ACCESS', now() - interval '2 hours',    'NEW',               NULL,                NULL,                NULL,       NULL,                  NULL),
    ( 4, 4, 'AFTER_HOURS',         now() - interval '50 minutes', 'CLAIMED',           pg_temp.did(1, 11),  NULL,                NULL,       NULL,                  NULL),
    ( 5, 6, 'UNAUTHORIZED_ACCESS', now() - interval '3 hours',    'CLAIMED',           pg_temp.did(1, 12),  NULL,                NULL,       NULL,                  NULL),
    ( 6, 6, 'INTRUSION',           pg_temp.vn(-1, 22, 40),        'RESOLVED_VERIFIED', pg_temp.did(1, 13),  pg_temp.did(1, 13),  'VERIFIED', 'ESCORTED_OUT',        'Mời người ở lại sau giờ ra khỏi khu vực, lập biên bản nhắc nhở'),
    ( 7, 6, 'UNAUTHORIZED_ACCESS', pg_temp.vn(-3, 10, 15),        'RESOLVED_VERIFIED', pg_temp.did(1, 11),  pg_temp.did(1, 11),  'VERIFIED', 'REPORT_FILED',        'Sinh viên đi theo người có quyền vào phòng Server, đã lập biên bản'),
    ( 8, 3, 'UNKNOWN_PERSON',      pg_temp.vn(-5, 15, 5),         'RESOLVED_VERIFIED', pg_temp.did(1, 12),  pg_temp.did(1, 12),  'VERIFIED', 'REMINDED_DISPERSED',  'Khách chưa đăng ký đi lạc vào Lab, đã hướng dẫn ra sảnh'),
    ( 9, 5, 'AFTER_HOURS',         pg_temp.vn(-8, 23, 10),        'RESOLVED_VERIFIED', pg_temp.did(1, 13),  pg_temp.did(1, 13),  'VERIFIED', 'DETAINED_ESCALATED',  'Phát hiện người lạ trong kho sau giờ, đã báo cấp trên'),
    (10, 4, 'UNAUTHORIZED_ACCESS', pg_temp.vn(-12, 9, 40),        'RESOLVED_VERIFIED', pg_temp.did(1, 11),  pg_temp.did(1, 11),  'VERIFIED', 'REMINDED_DISPERSED',  'Nhắc nhở sinh viên vào phòng họp khi chưa có đơn'),
    (11, 6, 'INTRUSION',           pg_temp.vn(-20, 22, 5),        'RESOLVED_VERIFIED', pg_temp.did(1, 12),  pg_temp.did(1, 12),  'VERIFIED', 'ESCORTED_OUT',        'Đưa người ra khỏi khu Tuyệt mật'),
    (12, 1, 'UNKNOWN_PERSON',      pg_temp.vn(-2, 8, 20),         'RESOLVED_DISMISSED',pg_temp.did(1, 11),  pg_temp.did(1, 11),  'DISMISSED','FALSE_ALARM',         'Camera nhận nhầm do ngược sáng, là cán bộ của trường'),
    (13, 3, 'UNAUTHORIZED_ACCESS', pg_temp.vn(-6, 14, 0),         'RESOLVED_DISMISSED',pg_temp.did(1, 13),  pg_temp.did(1, 13),  'DISMISSED','AUTHORIZED_EXCEPTION','Người vào có quyền chỉ định, hệ thống chưa cập nhật kịp'),
    (14, 6, 'INTRUSION',           pg_temp.vn(-15, 22, 30),       'RESOLVED_DISMISSED',pg_temp.did(1, 12),  pg_temp.did(1, 12),  'DISMISSED','FALSE_ALARM',         'Nhân viên kỹ thuật trực ca đêm theo lịch')
) AS v(n, cam_idx, event_type, detected_at, status, claimed_by, resolved_by, outcome, category, notes)
JOIN demo_cam c ON c.idx = v.cam_idx;

-- -----------------------------------------------------------------------------------------------------
-- 12. Thông báo chưa đọc cho mỗi vai (kèm vài thông báo đã đọc). Tham chiếu tới bản ghi demo ở trên.
-- -----------------------------------------------------------------------------------------------------
INSERT INTO notifications (id, recipient_id, type, title, message, reference_id, reference_type, is_read, created_at, read_at)
SELECT pg_temp.did(14, v.n), v.recipient, v.type, v.title, v.message, v.ref_id, v.ref_type, v.is_read,
       now() - v.ago, CASE WHEN v.is_read THEN now() - v.ago + interval '10 minutes' END
FROM (VALUES
    -- FACILITY_MANAGER
    ( 1, pg_temp.did(1, 1),  'NEW_REQUEST_PENDING', 'Yêu cầu truy cập mới chờ duyệt', 'Nguyễn Hoàng An (DEMO-SV-01) xin vào Phòng Lab AI Demo.', pg_temp.did(5, 1),  'ACCESS_REQUEST',    false, interval '3 hours'),
    ( 2, pg_temp.did(1, 1),  'GUEST_VISIT_PENDING', 'Lượt khách mới chờ duyệt',       'Đặng Thu Trang mời 2 khách tới Phòng Lab AI Demo, Phòng họp Hội đồng Demo.', pg_temp.did(10, 1), 'GUEST_VISIT', false, interval '2 hours'),
    ( 3, pg_temp.did(1, 1),  'INCIDENT_ESCALATED',  'Sự cố chưa được bảo vệ tiếp nhận', 'Sự cố truy cập trái phép tại Kho thiết bị Demo chưa có bảo vệ tiếp nhận.', pg_temp.did(13, 3), 'SECURITY_INCIDENT', false, interval '100 minutes'),
    ( 4, pg_temp.did(1, 1),  'NEW_REQUEST_PENDING', 'Yêu cầu truy cập mới chờ duyệt', 'Đặng Thu Trang (DEMO-GV-01) gửi yêu cầu nhóm vào Phòng Lab AI Demo.', pg_temp.did(5, 11), 'ACCESS_REQUEST',  true,  interval '4 hours'),
    ( 5, pg_temp.did(1, 2),  'NEW_REQUEST_PENDING', 'Yêu cầu truy cập mới chờ duyệt', 'Đặng Thu Trang (DEMO-GV-01) xin vào Phòng Server Demo.', pg_temp.did(5, 13), 'ACCESS_REQUEST',           false, interval '90 minutes'),
    ( 6, pg_temp.did(1, 2),  'PENDING_OVERDUE',     'Yêu cầu sắp quá hạn duyệt',      'Yêu cầu của Bùi Ngọc Bích (DEMO-SV-02) đã tới giờ bắt đầu mà chưa được duyệt.', pg_temp.did(5, 2), 'ACCESS_REQUEST', false, interval '5 minutes'),
    -- ADMIN (tài khoản có sẵn) — chỉ loại ADMIN nhận được: ảnh khách bắt buộc
    ( 7, (SELECT id FROM users WHERE email = 'admin@fpt.edu.vn'), 'GUEST_PHOTO_REQUIRED', 'Khách cần chụp ảnh', 'Lượt khách ngày mai của Mai Phương Thảo còn 1 khách chưa có ảnh.', pg_temp.did(10, 2), 'GUEST_VISIT', false, interval '5 hours'),
    ( 8, (SELECT id FROM users WHERE email = 'admin@fpt.edu.vn'), 'GUEST_PHOTO_REQUIRED', 'Khách cần chụp ảnh', 'Lượt khách đang diễn ra của Lý Đức Thịnh còn 1 khách chưa có ảnh.', pg_temp.did(10, 3), 'GUEST_VISIT', false, interval '50 minutes'),
    -- GUARD
    ( 9, pg_temp.did(1, 12), 'ACCESS_PERMISSION_REVOKED', 'Quyền chỉ định đã bị thu hồi', 'Quyền vào Kho thiết bị Demo của bạn đã bị thu hồi: Điều chuyển sang tuần tra khu Thư viện.', pg_temp.did(7, 6), 'AREA_ASSIGNMENT', false, interval '2 days'),
    -- NORMAL_USER
    (10, pg_temp.did(1, 21), 'REQUEST_APPROVED',  'Yêu cầu truy cập đã được duyệt', 'Yêu cầu vào Phòng họp Hội đồng Demo đã được duyệt.', pg_temp.did(5, 3),  'ACCESS_REQUEST', false, interval '20 hours'),
    (11, pg_temp.did(1, 21), 'ADDED_TO_GROUP',    'Bạn được thêm vào yêu cầu nhóm', 'Đặng Thu Trang đã thêm bạn vào yêu cầu nhóm vào Phòng Lab AI Demo.', pg_temp.did(5, 11), 'ACCESS_REQUEST', false, interval '4 hours'),
    (12, pg_temp.did(1, 21), 'REQUEST_FINISHED',  'Yêu cầu truy cập đã kết thúc', 'Yêu cầu vào Kho thiết bị Demo đã được kết thúc: Đã hoàn tất kiểm kê sớm hơn dự kiến.', pg_temp.did(5, 9), 'ACCESS_REQUEST', true, interval '2 days'),
    (13, pg_temp.did(1, 22), 'REQUEST_APPROVED',  'Yêu cầu truy cập đã được duyệt', 'Yêu cầu vào Kho thiết bị Demo đã được duyệt.', pg_temp.did(5, 4),  'ACCESS_REQUEST', false, interval '1 day'),
    (14, pg_temp.did(1, 23), 'REQUEST_REJECTED',  'Yêu cầu truy cập bị từ chối', 'Yêu cầu vào Phòng Lab AI Demo bị từ chối: Phòng Lab không mở cho tự học buổi tối, vui lòng dùng thư viện.', pg_temp.did(5, 5), 'ACCESS_REQUEST', false, interval '5 hours'),
    (15, pg_temp.did(1, 24), 'GUEST_VISIT_REJECTED', 'Lượt khách bị từ chối', 'Lượt khách tới Phòng Lab AI Demo bị từ chối: Phòng Lab đang chạy thực nghiệm, mời đăng ký lại tuần sau.', pg_temp.did(10, 4), 'GUEST_VISIT', false, interval '3 hours'),
    (16, pg_temp.did(1, 25), 'GUEST_VISIT_REVOKED',  'Lượt khách bị thu hồi', 'Lượt khách tới Phòng Lab AI Demo đã bị thu hồi: Khu vực tạm đóng để kiểm tra an ninh.', pg_temp.did(10, 6), 'GUEST_VISIT', false, interval '1 hour'),
    (17, pg_temp.did(1, 27), 'REQUEST_CANCELLED',    'Yêu cầu truy cập đã bị huỷ', 'Quản lý đã huỷ yêu cầu vào Kho thiết bị Demo: Kho thiết bị tạm đóng để bảo trì hệ thống điện.', pg_temp.did(5, 7), 'ACCESS_REQUEST', false, interval '6 hours'),
    (18, pg_temp.did(1, 27), 'ACCESS_PERMISSION_REVOKED', 'Quyền chỉ định đã bị thu hồi', 'Quyền vào Kho thiết bị Demo của bạn đã bị thu hồi: Đã hoàn thành công việc kiểm kê, không cần quyền nữa.', pg_temp.did(7, 5), 'AREA_ASSIGNMENT', false, interval '1 day')
) AS v(n, recipient, type, title, message, ref_id, ref_type, is_read, ago);

-- -----------------------------------------------------------------------------------------------------
-- 13. Ca trực hôm nay và ngày mai cho 3 bảo vệ demo (giờ ca theo GuardScheduleService: 06–14, 14–22, 22–06)
-- -----------------------------------------------------------------------------------------------------
INSERT INTO guard_shifts (id, guard_id, shift_date, shift_type, start_time, end_time, area_id, status, notes)
SELECT pg_temp.did(16, v.n), v.guard_id,
       (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date + v.day_offset,
       v.shift_type, v.start_time, v.end_time, v.area_id, 'SCHEDULED', 'Ca trực dữ liệu demo'
FROM (VALUES
    (1, pg_temp.did(1, 11), 0, 'SHIFT_MORNING',   time '06:00', time '14:00', pg_temp.did(3, 6)),
    (2, pg_temp.did(1, 12), 0, 'SHIFT_AFTERNOON', time '14:00', time '22:00', pg_temp.did(3, 5)),
    (3, pg_temp.did(1, 13), 0, 'SHIFT_NIGHT',     time '22:00', time '06:00', pg_temp.did(3, 1)),
    (4, pg_temp.did(1, 12), 1, 'SHIFT_MORNING',   time '06:00', time '14:00', pg_temp.did(3, 6)),
    (5, pg_temp.did(1, 13), 1, 'SHIFT_AFTERNOON', time '14:00', time '22:00', pg_temp.did(3, 5)),
    (6, pg_temp.did(1, 11), 1, 'SHIFT_NIGHT',     time '22:00', time '06:00', pg_temp.did(3, 1))
) AS v(n, guard_id, day_offset, shift_type, start_time, end_time, area_id);

COMMIT;

-- Kiểm nhanh sau khi chạy (tuỳ chọn):
-- SELECT 'users' t, count(*) FROM users WHERE id::text LIKE 'd0000001-%'
-- UNION ALL SELECT 'access_requests', count(*) FROM access_requests WHERE id::text LIKE 'd0000005-%'
-- UNION ALL SELECT 'camera_access_logs', count(*) FROM camera_access_logs WHERE id::text LIKE 'd000000c-%';
