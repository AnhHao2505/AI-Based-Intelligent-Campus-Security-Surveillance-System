-- ============================================================================
-- V4: Triggers — 4 nhóm trigger bảo vệ toàn vẹn dữ liệu
-- ============================================================================

-- ============================================================================
-- TRIGGER 1: Tự động cập nhật updated_at khi UPDATE
-- 1 function dùng chung + 8 trigger cho 8 bảng có cột updated_at
-- ============================================================================

CREATE OR REPLACE FUNCTION update_modified_column()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_areas_updated_at
    BEFORE UPDATE ON areas
    FOR EACH ROW EXECUTE FUNCTION update_modified_column();

CREATE TRIGGER trg_area_escalation_policies_updated_at
    BEFORE UPDATE ON area_escalation_policies
    FOR EACH ROW EXECUTE FUNCTION update_modified_column();

CREATE TRIGGER trg_cameras_updated_at
    BEFORE UPDATE ON cameras
    FOR EACH ROW EXECUTE FUNCTION update_modified_column();

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION update_modified_column();

CREATE TRIGGER trg_access_permissions_updated_at
    BEFORE UPDATE ON access_permissions
    FOR EACH ROW EXECUTE FUNCTION update_modified_column();

CREATE TRIGGER trg_security_incidents_updated_at
    BEFORE UPDATE ON security_incidents
    FOR EACH ROW EXECUTE FUNCTION update_modified_column();

CREATE TRIGGER trg_severity_rules_updated_at
    BEFORE UPDATE ON severity_rules
    FOR EACH ROW EXECUTE FUNCTION update_modified_column();

CREATE TRIGGER trg_system_config_updated_at
    BEFORE UPDATE ON system_config
    FOR EACH ROW EXECUTE FUNCTION update_modified_column();

-- ============================================================================
-- TRIGGER 2: APPEND-ONLY cho audit_logs — chặn UPDATE và DELETE
-- ============================================================================

CREATE OR REPLACE FUNCTION prevent_audit_log_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only: % operations are not allowed', TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_logs_no_update
    BEFORE UPDATE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();

CREATE TRIGGER trg_audit_logs_no_delete
    BEFORE DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_modification();

-- ============================================================================
-- TRIGGER 3: Tự tạo area_escalation_policies mặc định khi INSERT area mới
-- Tránh trường hợp Area không có Policy → lỗi runtime khi MF3 tra routing
-- ============================================================================

CREATE OR REPLACE FUNCTION create_default_area_policy()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO area_escalation_policies (area_id)
    VALUES (NEW.id);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_areas_auto_policy
    AFTER INSERT ON areas
    FOR EACH ROW EXECUTE FUNCTION create_default_area_policy();

-- ============================================================================
-- TRIGGER 4 & 5: Kiểm tra vai trò + trạng thái active khi phân công
-- assigned_guard_id và escalated_to_id trên security_incidents
--
-- Tách thành 2 trigger riêng:
--   - INSERT: luôn chạy (không dùng WHEN vì PostgreSQL không cho phép
--             WHEN tham chiếu OLD trên INSERT trigger)
--   - UPDATE: chỉ chạy khi assigned_guard_id hoặc escalated_to_id thay đổi
-- ============================================================================

CREATE OR REPLACE FUNCTION validate_incident_user_roles()
RETURNS TRIGGER AS $$
DECLARE
    guard_role VARCHAR(50);
    manager_role VARCHAR(50);
BEGIN
    -- Kiểm tra assigned_guard_id (nếu có giá trị)
    IF NEW.assigned_guard_id IS NOT NULL THEN
        SELECT role_type INTO guard_role
        FROM users
        WHERE id = NEW.assigned_guard_id
          AND is_active = TRUE
          AND deleted_at IS NULL;

        IF guard_role IS NULL THEN
            RAISE EXCEPTION 'User % không tồn tại hoặc không còn hoạt động', NEW.assigned_guard_id;
        END IF;

        IF guard_role NOT IN ('INTERNAL_GUARD', 'OUTSOURCED_GUARD') THEN
            RAISE EXCEPTION 'User % có role_type = % — chỉ INTERNAL_GUARD hoặc OUTSOURCED_GUARD mới được gán làm bảo vệ xử lý',
                NEW.assigned_guard_id, guard_role;
        END IF;
    END IF;

    -- Kiểm tra escalated_to_id (nếu có giá trị)
    IF NEW.escalated_to_id IS NOT NULL THEN
        SELECT role_type INTO manager_role
        FROM users
        WHERE id = NEW.escalated_to_id
          AND is_active = TRUE
          AND deleted_at IS NULL;

        IF manager_role IS NULL THEN
            RAISE EXCEPTION 'User % không tồn tại hoặc không còn hoạt động', NEW.escalated_to_id;
        END IF;

        IF manager_role <> 'FACILITY_MANAGER' THEN
            RAISE EXCEPTION 'User % có role_type = % — chỉ FACILITY_MANAGER mới được nhận escalation',
                NEW.escalated_to_id, manager_role;
        END IF;
    END IF;

    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Trigger INSERT: luôn chạy, không dùng WHEN (PostgreSQL không cho phép
-- tham chiếu OLD trong WHEN clause của INSERT trigger)
CREATE TRIGGER trg_incidents_validate_roles_insert
    BEFORE INSERT ON security_incidents
    FOR EACH ROW EXECUTE FUNCTION validate_incident_user_roles();

-- Trigger UPDATE: chỉ chạy khi assigned_guard_id hoặc escalated_to_id thực sự thay đổi
CREATE TRIGGER trg_incidents_validate_roles_update
    BEFORE UPDATE ON security_incidents
    FOR EACH ROW
    WHEN (
        OLD.assigned_guard_id IS DISTINCT FROM NEW.assigned_guard_id
        OR OLD.escalated_to_id IS DISTINCT FROM NEW.escalated_to_id
    )
    EXECUTE FUNCTION validate_incident_user_roles();
