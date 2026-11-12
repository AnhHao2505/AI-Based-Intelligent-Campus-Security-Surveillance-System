-- Migration V116: Bảng lưu tin nhắn văn bản giữa các nhân viên bảo vệ (Guard-to-Guard Chat)
CREATE TABLE IF NOT EXISTS guard_chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    receiver_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_guard_chat_conversation
    ON guard_chat_messages(sender_id, receiver_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_guard_chat_receiver_unread
    ON guard_chat_messages(receiver_id, is_read);

