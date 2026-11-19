-- ============================================================================
-- V97_1: Dọn sạch toàn bộ dữ liệu liên quan đến Tòa Alpha (chỉ giữ Tòa Epsilon)
-- Thứ tự xóa tuân thủ các ràng buộc FK ON DELETE RESTRICT / NO ACTION.
-- ============================================================================

DO $$
DECLARE
    v_alpha_building_ids UUID[];
    v_alpha_area_ids UUID[];
    v_alpha_camera_ids UUID[];
    v_alpha_camera_codes TEXT[];
    v_alpha_incident_ids UUID[];
BEGIN
    -- 1. Tìm ID các tòa nhà thuộc Tòa Alpha
    SELECT COALESCE(ARRAY_AGG(id), ARRAY[]::UUID[])
    INTO v_alpha_building_ids
    FROM buildings
    WHERE LOWER(TRIM(name)) IN ('tòa alpha', 'toa alpha', 'tòa hcm', 'tòa fpt hcm')
       OR UPPER(name) LIKE '%ALPHA%';

    -- 2. Tìm ID các khu vực thuộc Tòa Alpha (theo floor -> building hoặc building string)
    SELECT COALESCE(ARRAY_AGG(DISTINCT a.id), ARRAY[]::UUID[])
    INTO v_alpha_area_ids
    FROM areas a
    LEFT JOIN floors f ON a.floor_id = f.id
    WHERE (f.building_id = ANY(v_alpha_building_ids))
       OR (
           a.building IS NOT NULL AND (
               LOWER(TRIM(a.building)) IN ('tòa alpha', 'toa alpha', 'tòa hcm', 'tòa fpt hcm')
               OR UPPER(a.building) LIKE '%ALPHA%'
           )
       );

    -- 3. Tìm ID và mã các camera thuộc khu vực Tòa Alpha
    SELECT COALESCE(ARRAY_AGG(id), ARRAY[]::UUID[]),
           COALESCE(ARRAY_AGG(camera_code), ARRAY[]::TEXT[])
    INTO v_alpha_camera_ids, v_alpha_camera_codes
    FROM cameras
    WHERE area_id = ANY(v_alpha_area_ids);

    -- 4. Tìm ID các sự cố liên quan đến khu vực, camera hoặc tòa Alpha
    SELECT COALESCE(ARRAY_AGG(DISTINCT id), ARRAY[]::UUID[])
    INTO v_alpha_incident_ids
    FROM security_incidents
    WHERE area_id = ANY(v_alpha_area_ids)
       OR camera_code = ANY(v_alpha_camera_codes)
       OR (
           building IS NOT NULL AND (
               LOWER(TRIM(building)) IN ('tòa alpha', 'toa alpha', 'tòa hcm', 'tòa fpt hcm')
               OR UPPER(building) LIKE '%ALPHA%'
           )
       );

    -- 5. Xóa dữ liệu các bảng phụ thuộc vào security_incidents / cameras / areas
    IF to_regclass('public.incident_dispatch_offers') IS NOT NULL THEN
        DELETE FROM incident_dispatch_offers WHERE incident_id = ANY(v_alpha_incident_ids);
    END IF;

    IF to_regclass('public.incident_escalations') IS NOT NULL THEN
        DELETE FROM incident_escalations WHERE incident_id = ANY(v_alpha_incident_ids);
    END IF;

    IF to_regclass('public.camera_recordings') IS NOT NULL THEN
        DELETE FROM camera_recordings
        WHERE camera_id = ANY(v_alpha_camera_ids)
           OR camera_code = ANY(v_alpha_camera_codes)
           OR incident_id = ANY(v_alpha_incident_ids);
    END IF;

    IF to_regclass('public.security_incidents') IS NOT NULL THEN
        DELETE FROM security_incidents WHERE id = ANY(v_alpha_incident_ids);
    END IF;

    IF to_regclass('public.camera_access_logs') IS NOT NULL THEN
        DELETE FROM camera_access_logs
        WHERE area_id = ANY(v_alpha_area_ids)
           OR camera_code = ANY(v_alpha_camera_codes);
    END IF;

    IF to_regclass('public.guest_visit_areas') IS NOT NULL THEN
        DELETE FROM guest_visit_areas WHERE area_id = ANY(v_alpha_area_ids);
    END IF;

    IF to_regclass('public.area_temporary_usages') IS NOT NULL THEN
        DELETE FROM area_temporary_usages WHERE area_id = ANY(v_alpha_area_ids);
    END IF;

    IF to_regclass('public.access_requests') IS NOT NULL THEN
        DELETE FROM access_requests WHERE area_id = ANY(v_alpha_area_ids);
    END IF;

    IF to_regclass('public.area_assigned_personnel') IS NOT NULL THEN
        DELETE FROM area_assigned_personnel WHERE area_id = ANY(v_alpha_area_ids);
    END IF;

    -- Ngắt tham chiếu vòng giữa area_event_schedules và area_event_sessions
    IF to_regclass('public.area_event_schedules') IS NOT NULL THEN
        UPDATE area_event_schedules SET session_id = NULL WHERE area_id = ANY(v_alpha_area_ids);
    END IF;

    IF to_regclass('public.area_event_sessions') IS NOT NULL THEN
        UPDATE area_event_sessions SET schedule_id = NULL WHERE area_id = ANY(v_alpha_area_ids);
        DELETE FROM area_event_sessions WHERE area_id = ANY(v_alpha_area_ids);
    END IF;

    IF to_regclass('public.area_event_schedules') IS NOT NULL THEN
        DELETE FROM area_event_schedules WHERE area_id = ANY(v_alpha_area_ids);
    END IF;

    -- 6. Xử lý audit_logs (bảng có trigger append-only và FK RESTRICT tới areas)
    IF to_regclass('public.audit_logs') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_audit_logs_append_only' AND tgrelid = 'public.audit_logs'::regclass) THEN
            ALTER TABLE audit_logs DISABLE TRIGGER trg_audit_logs_append_only;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_access_control_audit_logs_append_only' AND tgrelid = 'public.audit_logs'::regclass) THEN
            ALTER TABLE audit_logs DISABLE TRIGGER trg_access_control_audit_logs_append_only;
        END IF;

        DELETE FROM audit_logs WHERE area_id = ANY(v_alpha_area_ids);

        IF EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_audit_logs_append_only' AND tgrelid = 'public.audit_logs'::regclass) THEN
            ALTER TABLE audit_logs ENABLE TRIGGER trg_audit_logs_append_only;
        END IF;
        IF EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_access_control_audit_logs_append_only' AND tgrelid = 'public.audit_logs'::regclass) THEN
            ALTER TABLE audit_logs ENABLE TRIGGER trg_access_control_audit_logs_append_only;
        END IF;
    END IF;

    IF to_regclass('public.access_control_audit_logs') IS NOT NULL THEN
        IF EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_access_control_audit_logs_append_only' AND tgrelid = 'public.access_control_audit_logs'::regclass) THEN
            ALTER TABLE access_control_audit_logs DISABLE TRIGGER trg_access_control_audit_logs_append_only;
        END IF;

        DELETE FROM access_control_audit_logs WHERE area_id = ANY(v_alpha_area_ids);

        IF EXISTS (SELECT 1 FROM pg_trigger WHERE tgname = 'trg_access_control_audit_logs_append_only' AND tgrelid = 'public.access_control_audit_logs'::regclass) THEN
            ALTER TABLE access_control_audit_logs ENABLE TRIGGER trg_access_control_audit_logs_append_only;
        END IF;
    END IF;

    -- Xóa thông báo mồ côi liên quan đến các khu vực / sự cố của Tòa Alpha
    IF to_regclass('public.notifications') IS NOT NULL THEN
        DELETE FROM notifications
        WHERE reference_id = ANY(v_alpha_area_ids)
           OR reference_id = ANY(v_alpha_incident_ids);
    END IF;

    -- 7. Gỡ liên kết tòa/khu vực Alpha ở guard_shifts và guard_teams trước khi xóa buildings/areas
    IF to_regclass('public.guard_shifts') IS NOT NULL THEN
        UPDATE guard_shifts
        SET area_id = CASE WHEN area_id = ANY(v_alpha_area_ids) THEN NULL ELSE area_id END,
            building_id = CASE WHEN building_id = ANY(v_alpha_building_ids) THEN NULL ELSE building_id END
        WHERE area_id = ANY(v_alpha_area_ids)
           OR building_id = ANY(v_alpha_building_ids);
    END IF;

    IF to_regclass('public.guard_teams') IS NOT NULL THEN
        UPDATE guard_teams
        SET building_id = NULL,
            description = CASE
                WHEN description IS NOT NULL AND UPPER(description) LIKE '%ALPHA%' THEN 'Đội bảo vệ'
                ELSE description
            END,
            updated_at = CURRENT_TIMESTAMP
        WHERE building_id = ANY(v_alpha_building_ids)
           OR (description IS NOT NULL AND UPPER(description) LIKE '%ALPHA%');
    END IF;

    -- 8. Xóa cameras, areas, floors, buildings của Tòa Alpha
    IF to_regclass('public.cameras') IS NOT NULL THEN
        DELETE FROM cameras WHERE id = ANY(v_alpha_camera_ids);
    END IF;

    IF to_regclass('public.areas') IS NOT NULL THEN
        DELETE FROM areas WHERE id = ANY(v_alpha_area_ids);
    END IF;

    IF to_regclass('public.floors') IS NOT NULL THEN
        DELETE FROM floors WHERE building_id = ANY(v_alpha_building_ids);
    END IF;

    IF to_regclass('public.buildings') IS NOT NULL THEN
        DELETE FROM buildings WHERE id = ANY(v_alpha_building_ids);
    END IF;
END $$;
