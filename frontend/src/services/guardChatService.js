import { apiGet, apiPost } from '../api/apiClient';

/**
 * Lấy danh sách bảo vệ kèm trạng thái trực ca và số tin nhắn chưa đọc
 * GET /api/guard-chat/guards
 */
export async function getGuardsForChat() {
  return apiGet('/api/guard-chat/guards');
}

/**
 * Lấy lịch sử hội thoại 1-1 với một bảo vệ
 * GET /api/guard-chat/messages?peerId=...&limit=...
 */
export async function getConversationMessages(peerId, limit = 60) {
  return apiGet(`/api/guard-chat/messages?peerId=${encodeURIComponent(peerId)}&limit=${limit}`);
}

/**
 * Gửi tin nhắn 1-1 tới một bảo vệ
 * POST /api/guard-chat/messages
 */
export async function sendChatMessage(receiverId, content) {
  return apiPost('/api/guard-chat/messages', { receiverId, content });
}

/**
 * Đánh dấu đã đọc các tin nhắn từ người gửi này
 * POST /api/guard-chat/read/{peerId}
 */
export async function markChatConversationAsRead(peerId) {
  return apiPost(`/api/guard-chat/read/${encodeURIComponent(peerId)}`);
}

/**
 * Lấy tổng số tin nhắn chưa đọc của người dùng hiện tại
 * GET /api/guard-chat/unread-count
 */
export async function getUnreadChatCount() {
  return apiGet('/api/guard-chat/unread-count');
}

