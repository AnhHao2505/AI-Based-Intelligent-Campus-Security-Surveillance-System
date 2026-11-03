-- ============================================================================
-- Migration V95: Chuẩn hoá 5 nhãn sự cố / kết quả ra vào (quyết định 03/11/2026)
--
-- Lý do: AI (Edge) và backend dùng nhiều tên khác nhau cho cùng một loại sự kiện
-- (INTRUSION / AFTER_HOURS..., UNAUTHORIZED_ACCESS / ACCESS_OUT_OF_TIME..., SUSPICIOUS_MOTION...).
-- Quyết định 03/11:
--   1. Chỉ còn 5 nhãn: UNKNOWN_PERSON, AUTHORIZED_PERSON, UNAUTHORIZED_PERSON, PERSON_INTRUSION,
--      SUSPICIOUS_MOVING_OBJECT. AUTHORIZED_PERSON chỉ dùng cho lịch sử ra vào, không tạo sự cố.
--   2. Ra vào sai khung giờ tính mức như vào trái phép (UNAUTHORIZED_PERSON): xoá luật severity của
--      ACCESS_OUT_OF_TIME, không đổi tên chúng.
--   3. Đổi tên luôn dữ liệu cũ trong security_incidents.event_type và camera_access_logs.access_result.
-- Sau migration incident_severity_rules có đúng 11 dòng (7 luật cũ đổi tên + 4 luật SUSPICIOUS_MOVING_OBJECT).
-- Viết idempotent: chạy lại trên DB đã migrate không đổi gì.
-- Không đụng audit_logs (lịch sử bất biến) và notifications (văn bản tự do).
-- ============================================================================

-- a) Đổi tên luật severity, không tạo trùng (event_type, area_level_code, time_window)
UPDATE incident_severity_rules r
SET event_type = 'PERSON_INTRUSION', updated_at = CURRENT_TIMESTAMP
WHERE r.event_type = 'INTRUSION'
  AND NOT EXISTS (
      SELECT 1 FROM incident_severity_rules n
      WHERE n.event_type = 'PERSON_INTRUSION'
        AND n.area_level_code = r.area_level_code
        AND n.time_window = r.time_window
  );

UPDATE incident_severity_rules r
SET event_type = 'UNAUTHORIZED_PERSON', updated_at = CURRENT_TIMESTAMP
WHERE r.event_type = 'UNAUTHORIZED_ACCESS'
  AND NOT EXISTS (
      SELECT 1 FROM incident_severity_rules n
      WHERE n.event_type = 'UNAUTHORIZED_PERSON'
        AND n.area_level_code = r.area_level_code
        AND n.time_window = r.time_window
  );

-- Dòng tên cũ còn sót (đã có dòng tên mới cùng ô) thì bỏ, giữ dòng tên mới
DELETE FROM incident_severity_rules WHERE event_type IN ('INTRUSION', 'UNAUTHORIZED_ACCESS');

-- b) Sai khung giờ = vào trái phép: bỏ luật riêng của ACCESS_OUT_OF_TIME
DELETE FROM incident_severity_rules WHERE event_type = 'ACCESS_OUT_OF_TIME';

-- c) Luật cho SUSPICIOUS_MOVING_OBJECT
INSERT INTO incident_severity_rules (event_type, area_level_code, time_window, severity) VALUES
    ('SUSPICIOUS_MOVING_OBJECT', 'PUBLIC',              'AFTER_HOURS', 'MEDIUM'),
    ('SUSPICIOUS_MOVING_OBJECT', 'CONFIDENTIAL',        'IN_HOURS',    'HIGH'),
    ('SUSPICIOUS_MOVING_OBJECT', 'CONFIDENTIAL',        'AFTER_HOURS', 'CRITICAL'),
    ('SUSPICIOUS_MOVING_OBJECT', 'HIGHLY_CONFIDENTIAL', 'ANY',         'CRITICAL')
ON CONFLICT (event_type, area_level_code, time_window) DO NOTHING;

-- d) Đổi tên loại sự cố đã lưu
UPDATE security_incidents SET event_type = 'PERSON_INTRUSION'
WHERE event_type IN ('INTRUSION', 'AFTER_HOURS', 'AFTER_HOURS_PRESENCE', 'AFTER_HOURS_ACCESS');

UPDATE security_incidents SET event_type = 'UNAUTHORIZED_PERSON'
WHERE event_type IN ('UNAUTHORIZED_ACCESS', 'UNAUTHORIZED', 'ACCESS_OUT_OF_TIME', 'OUT_OF_TIME');

UPDATE security_incidents SET event_type = 'UNKNOWN_PERSON'
WHERE event_type = 'UNKNOWN';

UPDATE security_incidents SET event_type = 'SUSPICIOUS_MOVING_OBJECT'
WHERE event_type IN ('SUSPICIOUS_MOTION', 'CAMOUFLAGE', 'MOVING_OBJECT');

UPDATE security_incidents SET event_type = 'AUTHORIZED_PERSON'
WHERE event_type = 'AUTHORIZED_ACCESS';

-- e) Đổi tên kết quả ra vào đã lưu
UPDATE camera_access_logs SET access_result = 'AUTHORIZED_PERSON'   WHERE access_result = 'AUTHORIZED_ACCESS';
UPDATE camera_access_logs SET access_result = 'UNAUTHORIZED_PERSON' WHERE access_result IN ('UNAUTHORIZED_ACCESS', 'ACCESS_OUT_OF_TIME');
UPDATE camera_access_logs SET access_result = 'PERSON_INTRUSION'    WHERE access_result = 'INTRUSION';
