-- ============================================================================
-- FLYWAY MIGRATION V43: PARTIAL UNIQUE INDEX ON AREA NAME, BUILDING, FLOOR
-- Chống trùng tên khu vực (không phân biệt hoa thường, sau trim) trong cùng toà nhà và tầng.
-- Chỉ áp dụng cho các khu vực chưa bị xoá mềm (deleted_at IS NULL).
-- ============================================================================

CREATE UNIQUE INDEX ux_areas_name_building_floor
    ON areas (
        lower(coalesce(trim(name), '')),
        lower(coalesce(trim(building), '')),
        lower(coalesce(trim(floor), ''))
    )
    WHERE deleted_at IS NULL;
