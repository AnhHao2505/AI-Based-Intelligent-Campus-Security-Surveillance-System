import { apiGet, apiPut } from '../api/apiClient';

/**
 * Lấy cấu hình AI toàn hệ thống
 * GET /api/ai-config
 */
export async function getAiConfig() {
  return apiGet('/api/ai-config');
}

/**
 * Cập nhật cấu hình AI toàn hệ thống (ADMIN)
 * PUT /api/ai-config
 */
export async function updateAiConfig(data) {
  return apiPut('/api/ai-config', data);
}
