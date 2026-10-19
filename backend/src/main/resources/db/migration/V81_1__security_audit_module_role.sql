-- ============================================================================
-- V81_1: Cho ADMIN xem nhật ký kiểm toán module SECURITY (audit xem ảnh qua /api/media, V81)
-- ============================================================================
-- AuditService.getAuditLogs lọc theo audit_module_roles; thiếu dòng này thì bản ghi FACE_PROFILE /
-- SECURITY_EVIDENCE đã ghi nhưng không ai xem được. Không cấp cho FACILITY_MANAGER (Lucas chốt 18/10).
-- Tách khỏi V81 vì V81 đã áp lên DB test dùng chung (sửa V81 sẽ lệch checksum).
INSERT INTO audit_module_roles (module, role) VALUES ('SECURITY', 'ADMIN')
ON CONFLICT DO NOTHING;
