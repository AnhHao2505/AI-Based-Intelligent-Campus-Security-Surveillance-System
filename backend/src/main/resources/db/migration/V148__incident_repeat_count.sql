-- V148: Lưu số lần lặp cảnh báo (repeat_count) trên security_incidents để không mất khi tải lại trang.

ALTER TABLE security_incidents
    ADD COLUMN IF NOT EXISTS repeat_count INTEGER NOT NULL DEFAULT 1;
