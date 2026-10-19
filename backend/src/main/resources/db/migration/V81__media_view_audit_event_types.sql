-- ============================================================================
-- V81: Loại sự kiện audit cho việc xem ảnh qua /api/media (sửa bảo mật sau PR #101)
-- ============================================================================
-- audit_logs có FK (target_type, action) -> audit_event_types nên phải khai báo trước khi ghi.
INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    ('FACE_PROFILE', 'VIEW_PHOTO', 'SECURITY', 'Xem ảnh khuôn mặt người dùng'),
    ('SECURITY_EVIDENCE', 'VIEW_PHOTO', 'SECURITY', 'Xem ảnh bằng chứng sự cố')
ON CONFLICT (target_type, action) DO NOTHING;
