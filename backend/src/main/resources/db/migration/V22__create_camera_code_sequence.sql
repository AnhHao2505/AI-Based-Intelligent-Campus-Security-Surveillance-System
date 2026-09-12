-- V22__create_camera_code_sequence.sql
-- Create database sequence for atomic camera code generation (Anti-Race Condition)

CREATE SEQUENCE IF NOT EXISTS camera_code_seq START WITH 1 INCREMENT BY 1;

-- Initialize sequence with next value based on existing max camera code
DO $$
DECLARE
    max_val BIGINT;
BEGIN
    SELECT COALESCE(MAX(SUBSTRING(camera_code FROM 5)::BIGINT), 0)
    INTO max_val
    FROM cameras
    WHERE camera_code ~ '^CAM-[0-9]+$';

    IF max_val > 0 THEN
        PERFORM setval('camera_code_seq', max_val + 1, false);
    END IF;
END $$;
