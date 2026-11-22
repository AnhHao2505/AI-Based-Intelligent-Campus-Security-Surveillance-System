-- ============================================================================
-- V137: Lưu người huỷ lượt khách (guest_visits.cancelled_by)
--
-- Bối cảnh:
--   - Trước đây guest_visits chỉ có cancelled_at, cancel_reason; người huỷ chỉ nằm trong audit_logs (GUEST_VISIT / CANCEL).
--     Màn "Ảnh khách" cần hiện người huỷ, phân biệt host tự huỷ với hệ thống huỷ.
--   - Hai đường huỷ trong GuestVisitService:
--       * cancel: host tự huỷ -> cancelled_by = host;
--       * closeForAreaDeactivation (ADMIN vô hiệu hoá hoặc đổi loại khu vực) -> cancelled_by = ADMIN, hệ thống huỷ.
--   - Cùng dạng với revoked_by (V60): UUID NULL REFERENCES users(id). Tài khoản chỉ xoá mềm nên FK không chặn xoá.
--
-- Ràng buộc:
--   - Không backfill dòng cũ (để NULL) và không thêm CHECK (status <> 'CANCELLED' OR cancelled_by IS NOT NULL),
--     vì lượt huỷ trước V137 không có giá trị. Chạy lại không lỗi (IF NOT EXISTS).
-- ============================================================================

ALTER TABLE guest_visits
    ADD COLUMN IF NOT EXISTS cancelled_by UUID NULL REFERENCES users(id);
