-- ============================================================================
-- V142_2: Khai báo sự kiện audit CAMERA / UPDATE (tách riêng vì V142_1 đã apply)
-- ============================================================================

INSERT INTO audit_event_types (target_type, action, module, description) VALUES
    ('CAMERA', 'UPDATE', 'CAMERA', 'Cập nhật camera')
ON CONFLICT (target_type, action) DO NOTHING;
