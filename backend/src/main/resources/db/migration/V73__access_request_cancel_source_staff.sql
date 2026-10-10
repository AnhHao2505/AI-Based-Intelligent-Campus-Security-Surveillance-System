-- V73: nguồn huỷ đơn truy cập thêm STAFF (BR-RQ-47).
-- FM huỷ đơn đã duyệt chưa bắt đầu (PATCH /api/access-requests/{id}/cancel-approved) trước đây ghi USER
-- nên không phân biệt được với người tạo tự huỷ đơn PENDING. Giữ USER / SYSTEM, thêm STAFF.
-- Không đổi dữ liệu cũ: đơn FM đã huỷ trước V73 vẫn là USER (phân biệt qua cancelled_by).
-- chk_access_requests_system_cancel (SYSTEM thì cancelled_by NULL) giữ nguyên; STAFF luôn có cancelled_by.

ALTER TABLE access_requests
    DROP CONSTRAINT IF EXISTS chk_access_requests_cancel_source;

ALTER TABLE access_requests
    ADD CONSTRAINT chk_access_requests_cancel_source
        CHECK (cancel_source IS NULL OR cancel_source IN ('USER', 'SYSTEM', 'STAFF'));
