import { apiGet, apiPatch } from '../api/apiClient';

/**
 * Service xử lý thông báo trong ứng dụng (In-app Notifications).
 */
export const notificationService = {
  /**
   * Lấy danh sách thông báo của người dùng hiện tại (có phân trang)
   * @param {Object} params - { page, size }
   * @returns {Promise<Object>} Page object chứa content, totalElements, totalPages,...
   */
  async getMyNotifications({ page = 0, size = 20 } = {}) {
    const query = new URLSearchParams();
    query.append('page', page);
    query.append('size', size);
    return await apiGet(`/api/notifications/my?${query.toString()}`);
  },

  /**
   * Lấy số lượng thông báo chưa đọc của người dùng hiện tại
   * @returns {Promise<{ count: number }>}
   */
  async getUnreadCount() {
    return await apiGet('/api/notifications/unread-count');
  },

  /**
   * Đánh dấu một thông báo cụ thể là đã đọc
   * @param {string} id - UUID của thông báo
   * @returns {Promise<Object>} NotificationResponse đã cập nhật
   */
  async markAsRead(id) {
    return await apiPatch(`/api/notifications/${id}/read`);
  },

  /**
   * Đánh dấu toàn bộ thông báo chưa đọc là đã đọc
   * @returns {Promise<{ updated: number }>}
   */
  async markAllAsRead() {
    return await apiPatch('/api/notifications/read-all');
  },
};

export default notificationService;
