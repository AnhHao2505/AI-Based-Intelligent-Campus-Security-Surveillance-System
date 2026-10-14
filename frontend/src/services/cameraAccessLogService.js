import { apiGet } from '../api/apiClient';

/**
 * Lấy danh sách nhật ký ra vào theo camera kèm phân trang và bộ lọc
 */
export async function fetchCameraAccessLogs(cameraCode, { page = 0, size = 20, result = '', search = '', from = '', to = '' } = {}) {
  const params = new URLSearchParams({
    page: page.toString(),
    size: size.toString(),
  });

  if (result) params.append('result', result);
  if (search && search.trim()) params.append('search', search.trim());
  if (from) params.append('from', from);
  if (to) params.append('to', to);

  return apiGet(`/api/cameras/${encodeURIComponent(cameraCode)}/access-logs?${params.toString()}`);
}

/**
 * Lấy thống kê số lượt ra vào trong ngày theo camera
 */
export async function fetchCameraAccessLogStats(cameraCode) {
  return apiGet(`/api/cameras/${encodeURIComponent(cameraCode)}/access-logs/stats`);
}
