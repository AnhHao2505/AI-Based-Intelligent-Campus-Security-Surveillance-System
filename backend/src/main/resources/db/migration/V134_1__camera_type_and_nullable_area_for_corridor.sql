-- V134_1: Phân loại 3 loại camera (RESTRICTED_AREA, PUBLIC_AREA, CORRIDOR),
-- cho phép area_id NULL đối với camera CORRIDOR, và dọn sạch các camera chưa có tọa độ bản đồ.

ALTER TABLE cameras
    ADD COLUMN IF NOT EXISTS camera_type VARCHAR(30) NOT NULL DEFAULT 'RESTRICTED_AREA';

-- Backfill loại camera từ cấp độ bảo mật của khu vực hiện tại
UPDATE cameras c
SET camera_type = 'PUBLIC_AREA'
FROM areas a
WHERE c.area_id = a.id
  AND (a.area_level = 'PUBLIC' OR a.area_access_level <= 1);

-- Cho phép area_id NULL khi camera là loại CORRIDOR (và cho sự cố / nhật ký phát sinh từ camera hành lang)
ALTER TABLE cameras
    ALTER COLUMN area_id DROP NOT NULL;

ALTER TABLE security_incidents
    ALTER COLUMN area_id DROP NOT NULL;

ALTER TABLE camera_access_logs
    ALTER COLUMN area_id DROP NOT NULL;

-- Dọn sạch các camera chưa có tọa độ ghim trên bản đồ (tuân thủ thứ tự FK)
DO $$
DECLARE
    v_unpinned_camera_ids UUID[];
    v_unpinned_camera_codes TEXT[];
    v_related_incident_ids UUID[];
BEGIN
    SELECT COALESCE(ARRAY_AGG(id), ARRAY[]::UUID[]),
           COALESCE(ARRAY_AGG(camera_code), ARRAY[]::TEXT[])
    INTO v_unpinned_camera_ids, v_unpinned_camera_codes
    FROM cameras
    WHERE map_latitude IS NULL OR map_longitude IS NULL;

    IF COALESCE(ARRAY_LENGTH(v_unpinned_camera_ids, 1), 0) = 0 THEN
        RETURN;
    END IF;

    IF to_regclass('public.security_incidents') IS NOT NULL THEN
        SELECT COALESCE(ARRAY_AGG(DISTINCT id), ARRAY[]::UUID[])
        INTO v_related_incident_ids
        FROM security_incidents
        WHERE camera_code = ANY(v_unpinned_camera_codes);

        IF COALESCE(ARRAY_LENGTH(v_related_incident_ids, 1), 0) > 0 THEN
            IF to_regclass('public.incident_dispatch_offers') IS NOT NULL THEN
                DELETE FROM incident_dispatch_offers WHERE incident_id = ANY(v_related_incident_ids);
            END IF;

            IF to_regclass('public.incident_escalations') IS NOT NULL THEN
                DELETE FROM incident_escalations WHERE incident_id = ANY(v_related_incident_ids);
            END IF;
        END IF;
    END IF;

    IF to_regclass('public.camera_recordings') IS NOT NULL THEN
        DELETE FROM camera_recordings
        WHERE camera_id = ANY(v_unpinned_camera_ids)
           OR camera_code = ANY(v_unpinned_camera_codes)
           OR (COALESCE(ARRAY_LENGTH(v_related_incident_ids, 1), 0) > 0 AND incident_id = ANY(v_related_incident_ids));
    END IF;

    IF to_regclass('public.security_incidents') IS NOT NULL
       AND COALESCE(ARRAY_LENGTH(v_related_incident_ids, 1), 0) > 0 THEN
        DELETE FROM security_incidents WHERE id = ANY(v_related_incident_ids);
    END IF;

    IF to_regclass('public.camera_access_logs') IS NOT NULL THEN
        DELETE FROM camera_access_logs WHERE camera_code = ANY(v_unpinned_camera_codes);
    END IF;

    IF to_regclass('public.camera_stream_configurations') IS NOT NULL THEN
        DELETE FROM camera_stream_configurations WHERE camera_id = ANY(v_unpinned_camera_ids);
    END IF;

    DELETE FROM cameras WHERE id = ANY(v_unpinned_camera_ids);
END $$;
