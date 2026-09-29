-- ============================================================================
-- V61: Cleanup Unused GuardScheduleTemplate and radio_channel Column
-- ============================================================================

-- 1. Xóa bảng guard_schedule_templates (không còn sử dụng do đã chuyển sang Staffing Wizard)
DROP TABLE IF EXISTS guard_schedule_templates CASCADE;

-- 2. Xóa cột radio_channel trong bảng guard_shifts (không dùng)
ALTER TABLE guard_shifts DROP COLUMN IF EXISTS radio_channel;
