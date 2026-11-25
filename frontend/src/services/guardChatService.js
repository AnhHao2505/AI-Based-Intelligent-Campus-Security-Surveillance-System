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
  const data = await apiGet(`/api/guard-chat/messages?peerId=${encodeURIComponent(peerId)}&size=${limit}`);
  const list = Array.isArray(data) ? data : (Array.isArray(data?.content) ? data.content : []);
  return [...list].sort((a, b) => new Date(a.createdAt || 0) - new Date(b.createdAt || 0));
}

/**
 * Gửi tin nhắn 1-1 tới một bảo vệ
 * POST /api/guard-chat/messages
 */
export async function sendChatMessage(receiverId, content) {
  return apiPost('/api/guard-chat/messages', { receiverId, content });
}

/**
 * Gửi tin nhắn hình ảnh (kèm chú thích tùy chọn) tới một bảo vệ
 * POST /api/guard-chat/messages/image
 */
export async function sendChatImageMessage(receiverId, file, content = '') {
  const formData = new FormData();
  formData.append('receiverId', receiverId);
  if (content && content.trim()) {
    formData.append('content', content.trim());
  }
  formData.append('file', file);
  return apiPost('/api/guard-chat/messages/image', formData);
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

