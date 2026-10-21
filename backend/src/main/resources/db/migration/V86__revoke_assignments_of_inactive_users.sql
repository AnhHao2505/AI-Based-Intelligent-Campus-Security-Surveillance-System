-- ============================================================================
-- V86: Thu hồi Assigned Personnel của tài khoản đang bị vô hiệu hoá hoặc đã xoá (BR-AP-24/25, 20/10/2026)
--
-- Từ V86, vô hiệu hoá / xoá tài khoản (lẻ hoặc theo lô import) thu hồi luôn AP ACTIVE + UPCOMING của người đó
-- (AreaAssignedPersonnelService.revokeAllOfUser). Migration này dọn dữ liệu có từ trước: AP chưa thu hồi, chưa hết hạn
-- của user is_active = false hoặc deleted_at IS NOT NULL.
--
-- Ràng buộc:
--   - revoked_by = NULL (không có ADMIN thao tác); audit actor SYSTEM, changed_by NULL, actor_source
--     FLYWAY_MIGRATION_V86 (chk_audit_actor, V55). Mọi dòng dùng chung một correlation_id (md5 cố định, mẫu V83).
--   - Audit ghi TRƯỚC câu UPDATE để còn đọc được trạng thái cũ.
--   - old_value / new_value cùng dạng JSON với AreaAssignmentAuditSnapshot do ứng dụng ghi: validFrom / validTo là
--     chuỗi ISO-8601 giờ Việt Nam (+07:00), bỏ số 0 cuối phần lẻ giây; validTo NULL ghi null; status như
--     computeStatus (UPCOMING nếu valid_from > now, ngược lại ACTIVE). Đối chiếu bằng
--     V86RevokeAssignmentsOfInactiveUsersMigrationTest.
--   - Không gửi thông báo (BR-AP-26). Không khôi phục AP nào. Không đổi users.
--   - Chạy lại không lỗi: lần sau không còn dòng thoả điều kiện.
-- ============================================================================


-- ----------------------------------------------------------------------------
-- Bước 1. Audit AREA_ASSIGNMENT / REVOKE cho từng AP sắp bị thu hồi
-- ----------------------------------------------------------------------------
INSERT INTO audit_logs (id, target_type, action, target_id, area_id, subject_user_id,
                        old_value, new_value, reason, changed_by, changed_at,
                        actor_type, actor_source, correlation_id)
SELECT gen_random_uuid(), 'AREA_ASSIGNMENT', 'REVOKE', t.id::text, t.area_id, t.user_id,
       jsonb_build_object('validFrom', t.valid_from_iso,
                          'validTo', t.valid_to_iso,
                          'status', CASE WHEN t.valid_from > CURRENT_TIMESTAMP THEN 'UPCOMING' ELSE 'ACTIVE' END),
       jsonb_build_object('validFrom', t.valid_from_iso,
                          'validTo', t.valid_to_iso,
                          'status', 'REVOKED'),
       t.reason, NULL, CURRENT_TIMESTAMP, 'SYSTEM', 'FLYWAY_MIGRATION_V86', md5('FLYWAY_MIGRATION_V86')::uuid
FROM (
    SELECT ap.id, ap.area_id, ap.user_id, ap.valid_from,
           to_char(ap.valid_from AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS')
               || regexp_replace(to_char(ap.valid_from AT TIME ZONE 'Asia/Ho_Chi_Minh', '.US'), '\.?0+$', '')
               || '+07:00' AS valid_from_iso,
           CASE WHEN ap.valid_to IS NULL THEN NULL
                ELSE to_char(ap.valid_to AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD"T"HH24:MI:SS')
                     || regexp_replace(to_char(ap.valid_to AT TIME ZONE 'Asia/Ho_Chi_Minh', '.US'), '\.?0+$', '')
                     || '+07:00'
           END AS valid_to_iso,
           CASE WHEN u.deleted_at IS NOT NULL THEN 'Tài khoản người được gán đã bị xoá'
                ELSE 'Tài khoản người được gán đã bị vô hiệu hoá'
           END AS reason
    FROM area_assigned_personnel ap
    JOIN users u ON u.id = ap.user_id
    WHERE ap.revoked_at IS NULL
      AND (ap.valid_to IS NULL OR ap.valid_to > CURRENT_TIMESTAMP)
      AND (u.is_active = FALSE OR u.deleted_at IS NOT NULL)
) t;


-- ----------------------------------------------------------------------------
-- Bước 2. Thu hồi
-- ----------------------------------------------------------------------------
UPDATE area_assigned_personnel ap
SET revoked_at    = CURRENT_TIMESTAMP,
    revoked_by    = NULL,
    revoke_reason = CASE WHEN u.deleted_at IS NOT NULL THEN 'Tài khoản người được gán đã bị xoá'
                         ELSE 'Tài khoản người được gán đã bị vô hiệu hoá'
                    END,
    updated_at    = CURRENT_TIMESTAMP
FROM users u
WHERE u.id = ap.user_id
  AND ap.revoked_at IS NULL
  AND (ap.valid_to IS NULL OR ap.valid_to > CURRENT_TIMESTAMP)
  AND (u.is_active = FALSE OR u.deleted_at IS NOT NULL);


-- ----------------------------------------------------------------------------
-- Bước 3. Kiểm sau khi chạy (chỉ để người chạy tay đối chiếu, Flyway không cần)
-- ----------------------------------------------------------------------------
-- SELECT revoke_reason, count(*) FROM area_assigned_personnel
--   WHERE revoked_by IS NULL AND revoke_reason IN ('Tài khoản người được gán đã bị vô hiệu hoá', 'Tài khoản người được gán đã bị xoá')
--   GROUP BY 1;
-- SELECT reason, old_value->>'status' AS old_status, count(*) FROM audit_logs
--   WHERE actor_source = 'FLYWAY_MIGRATION_V86' GROUP BY 1, 2;   -- tổng = tổng câu trên
-- SELECT count(*) FROM area_assigned_personnel ap JOIN users u ON u.id = ap.user_id
--   WHERE ap.revoked_at IS NULL AND (ap.valid_to IS NULL OR ap.valid_to > CURRENT_TIMESTAMP)
--     AND (u.is_active = FALSE OR u.deleted_at IS NOT NULL);     -- -> 0
