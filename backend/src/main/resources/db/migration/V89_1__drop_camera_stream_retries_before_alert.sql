-- ============================================================================
-- V89_1: Bỏ các cột retries_before_alert và timeout_ms khỏi bảng camera_stream_configurations
-- 1. Không dùng logic retry đếm số lần cảnh báo trước khi báo động camera.
-- 2. Chuyển timeout_ms sang biến môi trường CAMERA_RTSP_TIMEOUT_MS (mặc định 10000ms),
--    loại bỏ lưu trữ phân mảnh trong cơ sở dữ liệu.
-- ============================================================================

ALTER TABLE camera_stream_configurations
    DROP COLUMN IF EXISTS retries_before_alert,
    DROP COLUMN IF EXISTS timeout_ms;
