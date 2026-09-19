-- ============================================================================
-- V34: Add ROI Geometry column to cameras table
-- Stores normalized polygon regions of interest as JSONB
-- ============================================================================

ALTER TABLE cameras ADD COLUMN IF NOT EXISTS roi_geometry JSONB;

COMMENT ON COLUMN cameras.roi_geometry IS 'Camera ROI polygons with alert rules';
