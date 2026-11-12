-- V117 (M4): Bảng lưu trữ trạng thái sức khỏe và nhịp tim (heartbeat) của thiết bị biên (Edge Devices).
-- Mỗi thiết bị biên định danh bằng edge_id (tương ứng MQTT_CLIENT_ID), gửi heartbeat mỗi 30s.
CREATE TABLE IF NOT EXISTS edge_device_statuses (
    edge_id VARCHAR(100) PRIMARY KEY,
    status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
    last_seen TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    cameras_json TEXT,
    gpu BOOLEAN DEFAULT FALSE,
    version VARCHAR(50),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_edge_device_statuses_status ON edge_device_statuses(status);
