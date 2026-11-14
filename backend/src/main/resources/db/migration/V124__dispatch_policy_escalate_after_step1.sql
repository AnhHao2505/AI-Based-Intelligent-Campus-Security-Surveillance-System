-- ============================================================================
-- V124: ràng buộc chéo của chính sách phân phối sự cố (Lucas chốt 14/11/2026)
--
-- Mức có đợt 1 (step1_size > 0) chạy theo thứ tự: mời đợt 1 → hết thời gian chờ đợt 1 / tất cả từ chối → phát cho
-- mọi bảo vệ đang trực → chưa ai nhận thì báo Quản lý CSVC. Hạn báo Quản lý CSVC tính TỪ LÚC TẠO SỰ CỐ
-- (IncidentEscalationJob), nên escalate_fm_minutes * 60 phải LỚN HƠN step1_timeout_seconds; không thì Quản lý CSVC bị
-- báo trước khi phát toàn bộ. step1_size = 0 (vd CRITICAL, không có đợt 1) không áp luật này.
-- Service kiểm cùng luật trước khi lưu (IncidentPolicyService.validateEscalationAfterStep1, ERR_INCPOL_009).
--
-- Seed V90 đều thoả: MEDIUM 1 / 60 s / 15 phút (900 > 60), HIGH 2 / 30 s / 5 phút (300 > 30), CRITICAL 0 / 10 s / 2 phút.
--
-- Dòng vi phạm do ADMIN đã sửa trước V124 (nếu có): KHÔNG để CHECK làm hỏng migration và KHÔNG sửa im lặng —
-- nâng escalate_fm_minutes lên giá trị nhỏ nhất thoả luật = step1_timeout_seconds / 60 + 1 (chia nguyên; tối đa 11,
-- vẫn trong khoảng 1–60 của CHECK V90), tăng version để màn admin đang mở phải tải lại, và ghi từng dòng đã sửa ra
-- log Flyway bằng RAISE NOTICE. Không đổi step1_size, step1_timeout_seconds, notify_fm_immediately. Không sửa V90.
-- ============================================================================

DO $$
DECLARE
    r RECORD;
    fixed_minutes INTEGER;
BEGIN
    FOR r IN
        SELECT severity, step1_size, step1_timeout_seconds, escalate_fm_minutes
        FROM incident_dispatch_policies
        WHERE step1_size > 0 AND escalate_fm_minutes * 60 <= step1_timeout_seconds
        ORDER BY severity
    LOOP
        fixed_minutes := r.step1_timeout_seconds / 60 + 1;
        UPDATE incident_dispatch_policies
        SET escalate_fm_minutes = fixed_minutes,
            version = version + 1,
            updated_at = CURRENT_TIMESTAMP
        WHERE severity = r.severity;
        RAISE NOTICE 'V124: chinh sach % vi pham (step1_size=%, step1_timeout_seconds=%, escalate_fm_minutes=%) -> escalate_fm_minutes=%',
            r.severity, r.step1_size, r.step1_timeout_seconds, r.escalate_fm_minutes, fixed_minutes;
    END LOOP;
END $$;

ALTER TABLE incident_dispatch_policies ADD CONSTRAINT chk_incident_dispatch_policies_escalate_after_step1
    CHECK (step1_size = 0 OR escalate_fm_minutes * 60 > step1_timeout_seconds);
