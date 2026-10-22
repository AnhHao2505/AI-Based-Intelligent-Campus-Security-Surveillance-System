-- ============================================================================
-- V87: Tên sự kiện cho lịch sự kiện (màn Quản lý sự kiện, 21/10/2026)
--
-- Mỗi lịch sự kiện có tên (title) bắt buộc, 1–150 ký tự sau trim (code kiểm, ERR_AREA_057).
-- Lịch có từ trước được điền bù: title = reason_label (tên loại sự kiện trong danh mục lúc đặt), không có thì "Sự kiện".
-- Bảng: area_event_schedules (entity AreaEventSchedule, @Table(name = "area_event_schedules")).
--
-- Ràng buộc:
--   - Không đổi trạng thái / thời gian / lý do của lịch nào; không ghi audit (chỉ thêm cột hiển thị, audit_logs giữ lịch sử).
--   - reason_label dài tối đa 255, cắt còn 150 ký tự cho vừa cột.
--   - Chạy lại không lỗi: ADD COLUMN IF NOT EXISTS, điền bù chỉ dòng còn NULL.
-- ============================================================================

ALTER TABLE area_event_schedules ADD COLUMN IF NOT EXISTS title VARCHAR(150);

UPDATE area_event_schedules
SET title = COALESCE(NULLIF(LEFT(BTRIM(reason_label), 150), ''), 'Sự kiện')
WHERE title IS NULL;

ALTER TABLE area_event_schedules ALTER COLUMN title SET NOT NULL;


-- ----------------------------------------------------------------------------
-- Kiểm sau khi chạy (chỉ để người chạy tay đối chiếu, Flyway không cần)
-- ----------------------------------------------------------------------------
-- SELECT count(*) FROM area_event_schedules WHERE title IS NULL OR BTRIM(title) = '';            -- -> 0
-- SELECT count(*) FILTER (WHERE title = LEFT(BTRIM(reason_label), 150)) AS from_reason_label,
--        count(*) FILTER (WHERE reason_label IS NULL OR BTRIM(reason_label) = '') AS fallback,
--        count(*) AS total
--   FROM area_event_schedules;                                                                  -- from_reason_label + fallback = total
-- SELECT column_name, is_nullable, character_maximum_length FROM information_schema.columns
--  WHERE table_name = 'area_event_schedules' AND column_name = 'title';                          -- -> title | NO | 150
