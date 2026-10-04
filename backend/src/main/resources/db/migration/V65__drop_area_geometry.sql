-- ============================================================================
-- V65: Remove area geometry and obsolete floor plans
-- ============================================================================

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'areas' AND column_name = 'geometry'
    ) THEN
        ALTER TABLE areas DROP COLUMN geometry CASCADE;
    END IF;
END $$;

DROP TABLE IF EXISTS floor_plans CASCADE;
