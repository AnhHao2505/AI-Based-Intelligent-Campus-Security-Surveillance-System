-- ============================================================================
-- V56_1: BR-ES-S4 — backfill trạng thái lịch sự kiện STARTED có từ trước V56.
-- Trước V56 lịch đã kích hoạt ở mãi STARTED; từ V56 lịch chuyển COMPLETED / ENDED_EARLY khi phiên do nó sinh ra đóng.
--
-- Quy tắc (BR-ES-S4):
--   Lịch STARTED mà khu vực KHÔNG còn phiên sự kiện mở -> xét phiên CUỐI của chuỗi phiên sinh từ lịch đó:
--     actual_end >= planned_end -> COMPLETED; ngược lại -> ENDED_EARLY.
--   Khu vực còn phiên mở -> giữ STARTED. Không tìm được phiên nào -> giữ nguyên.
--   Idempotent, chỉ đụng status = 'STARTED'.
--
-- Cách nhận "chuỗi phiên" (phiên ADJUST trước V56 chưa có schedule_id):
--   bắt đầu từ area_event_schedules.session_id (phiên do job kích hoạt lịch tạo ra); phiên kế tiếp là phiên CÙNG khu vực
--   có started_at = actual_end của phiên trước (ADJUST đóng phiên cũ và mở phiên mới tại cùng một thời điểm now).
--   Không dùng cột schedule_id của V56, nên cùng câu này chạy được trên dữ liệu chưa có liên kết đó.
--
-- Giới hạn:
--   - Một phiên bật tay (không thuộc lịch) bắt đầu ĐÚNG thời điểm phiên của lịch kết thúc sẽ bị coi là cùng chuỗi.
--   - Lịch STARTED không có session_id -> không xét, giữ STARTED.
--   - "Khu vực còn phiên mở" xét mọi phiên mở của khu vực, kể cả phiên không thuộc lịch -> lịch giữ STARTED;
--     phiên kéo dài tạo trước V56 không có schedule_id nên khi đóng sau này lịch KHÔNG tự chuyển trạng thái.
--   - Chuỗi dài quá 1000 phiên dừng ở phiên thứ 1000 (chặn vòng lặp khi dữ liệu có mốc giờ trùng bất thường).
--
-- Không đụng đối tượng của V58/V59, chạy được out-of-order sau V58/V59.
-- ============================================================================

WITH RECURSIVE chain AS (
    SELECT sch.id     AS schedule_id,
           s.id       AS session_id,
           s.area_id,
           s.planned_end,
           s.actual_end,
           1          AS depth
    FROM area_event_schedules sch
    JOIN area_event_sessions s ON s.id = sch.session_id
    WHERE sch.status = 'STARTED'
    UNION ALL
    SELECT c.schedule_id,
           n.id,
           n.area_id,
           n.planned_end,
           n.actual_end,
           c.depth + 1
    FROM chain c
    JOIN area_event_sessions n
      ON n.area_id = c.area_id
     AND c.actual_end IS NOT NULL
     AND n.started_at = c.actual_end
     AND n.id <> c.session_id
    WHERE c.depth < 1000
),
last_session AS (
    SELECT DISTINCT ON (schedule_id) schedule_id, planned_end, actual_end
    FROM chain
    ORDER BY schedule_id, depth DESC
)
UPDATE area_event_schedules sch
SET status = CASE WHEN l.actual_end >= l.planned_end THEN 'COMPLETED' ELSE 'ENDED_EARLY' END
FROM last_session l
WHERE sch.id = l.schedule_id
  AND sch.status = 'STARTED'
  AND l.actual_end IS NOT NULL
  AND NOT EXISTS (
        SELECT 1
        FROM area_event_sessions o
        WHERE o.area_id = sch.area_id
          AND o.actual_end IS NULL
  );
