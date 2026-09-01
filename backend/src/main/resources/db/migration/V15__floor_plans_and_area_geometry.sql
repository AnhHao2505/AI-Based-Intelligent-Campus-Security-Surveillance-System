-- ============================================================================
-- V11: Create floor_plans table and add geometry column to areas
-- ============================================================================

CREATE TABLE floor_plans (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    building        VARCHAR(50)  NOT NULL,
    floor           VARCHAR(20)  NOT NULL,
    image_key       VARCHAR(255) NOT NULL,
    original_width  INTEGER      NOT NULL,
    original_height INTEGER      NOT NULL,
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_floor_plans_building_floor ON floor_plans (LOWER(building), LOWER(floor));

INSERT INTO floor_plans (building, floor, image_key, original_width, original_height, is_active) VALUES
('FPT_AROUND', 'G', 'fptaround-floor-g-v1.png', 1838, 963, true),
('FPT_AROUND', '1', 'fptaround-floor-01-v1.png', 1532, 803, true),
('FPT_AROUND', '2', 'fptaround-floor-02-v1.png', 1532, 803, true);

ALTER TABLE areas ADD COLUMN geometry JSONB;
