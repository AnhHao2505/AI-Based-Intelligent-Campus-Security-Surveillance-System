import { apiGet, apiPatch } from '../api/apiClient';

/**
 * Service quản lý Cấu hình Hệ thống (System Configurations)
 */

/**
 * Lấy danh sách toàn bộ cấu hình hệ thống
 * @returns {Promise<Array>} Danh sách cấu hình kèm metadata
 */
export async function getSystemConfigs() {
  return apiGet('/api/system-configurations');
}

/**
 * Cập nhật giá trị của một cấu hình hệ thống (Yêu cầu quyền ADMIN)
 * @param {string} configKey - Mã khóa cấu hình
 * @param {string} configValue - Giá trị mới dạng chuỗi
 * @returns {Promise<Object>} Bản ghi cấu hình sau khi cập nhật
 */
export async function updateSystemConfig(configKey, configValue) {
  return apiPatch(`/api/system-configurations/${encodeURIComponent(configKey)}`, { configValue });
}

/**
 * Lấy lịch sử thay đổi của một cấu hình cụ thể
 * @param {string} configKey - Mã khóa cấu hình
 * @param {Object} params - { page, size }
 * @returns {Promise<Object>} Page dữ liệu các lần thay đổi
 */
export async function getSystemConfigHistory(configKey, { page = 0, size = 10 } = {}) {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  return apiGet(`/api/system-configurations/${encodeURIComponent(configKey)}/history?${query.toString()}`);
}

export default {
  getSystemConfigs,
  updateSystemConfig,
  getSystemConfigHistory,
};
