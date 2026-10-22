-- ============================================================================
-- V88: Lý do kết thúc đơn truy cập (BR-RQ-46, 21/10/2026)
--
-- Trước V88, AccessRequestService.finishRequest chỉ ghi lý do vào audit (target_type ACCESS_REQUEST, action FINISH);
-- bảng access_requests không lưu lý do hay người kết thúc. Từ V88, câu UPDATE có điều kiện finishIfApprovedStarted
-- ghi luôn finish_reason / finished_by (cùng kiểu cancel_reason / cancelled_by của V56).
--
-- Ràng buộc:
--   - finish_reason VARCHAR(500): bằng cột reason của audit_logs (V42), lý do kết thúc không dài hơn được.
--   - finished_by xoá user thì để NULL (ON DELETE SET NULL); users chỉ xoá mềm nên thực tế giữ nguyên.
--   - Điền bù đơn FINISHED từ bản ghi audit ACCESS_REQUEST / FINISH mới nhất của đơn (target_id = id đơn dạng text).
--     Đơn không có audit FINISH thì để NULL. Không đổi status, updated_at hay audit.
--   - Chạy lại không lỗi: IF NOT EXISTS, chỉ điền đơn còn finish_reason IS NULL AND finished_by IS NULL.
-- ============================================================================

ALTER TABLE access_requests
    ADD COLUMN IF NOT EXISTS finish_reason VARCHAR(500) NULL,
    ADD COLUMN IF NOT EXISTS finished_by UUID NULL REFERENCES users(id) ON DELETE SET NULL;

UPDATE access_requests r
SET finish_reason = a.reason,
    finished_by   = a.changed_by
FROM (
    SELECT DISTINCT ON (target_id) target_id, reason, changed_by
    FROM audit_logs
    WHERE target_type = 'ACCESS_REQUEST' AND action = 'FINISH'
    ORDER BY target_id, changed_at DESC, id DESC
) a
WHERE a.target_id = r.id::text
  AND r.status = 'FINISHED'
  AND r.finish_reason IS NULL
  AND r.finished_by IS NULL;

-- Kiểm sau khi chạy (chỉ đọc):
-- SELECT count(*)                                         AS don_finished,
--        count(*) FILTER (WHERE finish_reason IS NOT NULL) AS da_co_finish_reason,
--        count(*) FILTER (WHERE NOT EXISTS (
--            SELECT 1 FROM audit_logs a
--            WHERE a.target_type = 'ACCESS_REQUEST' AND a.action = 'FINISH' AND a.target_id = r.id::text
--        ))                                               AS finished_khong_co_audit
-- FROM access_requests r
-- WHERE r.status = 'FINISHED';
