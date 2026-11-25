-- V143: Hỗ trợ gửi hình ảnh trong tin nhắn nội bộ bảo vệ
ALTER TABLE guard_chat_messages
    ALTER COLUMN content DROP NOT NULL;

ALTER TABLE guard_chat_messages
    ADD COLUMN IF NOT EXISTS image_url VARCHAR(500);
