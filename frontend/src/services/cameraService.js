import { getAccessToken } from './authService';

const API_BASE_URL =
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

function getHeaders() {
  const token = getAccessToken();
  return {
    "Content-Type": "application/json",
    ...(token ? { "Authorization": `Bearer ${token}` } : {}),
  };
}

async function request(url, options = {}) {
  const res = await fetch(`${API_BASE_URL}${url}`, {
    ...options,
    headers: {
      ...getHeaders(),
      ...options.headers,
    },
  });

  if (!res.ok) {
    const error = await res.json().catch(() => ({ message: "Yêu cầu thất bại" }));
    throw new Error(error.message || `Lỗi HTTP ${res.status}`);
  }

  if (res.status === 204) return null;
  return res.json();
}

/**
 * Lấy danh sách camera kèm phân trang và tìm kiếm/lọc
 */
export function fetchCameras({ page = 0, size = 10, search = '', status = '', operationalStatus = '' }) {
  const params = new URLSearchParams({
    page: page.toString(),
    size: size.toString(),
  });
  if (search) params.append('search', search);
  if (status) params.append('status', status);
  if (operationalStatus) params.append('operationalStatus', operationalStatus);

  return request(`/api/cameras?${params.toString()}`);
}

/**
 * Lấy chi tiết camera và toàn bộ cấu hình con
 */
export function fetchCameraDetail(id) {
  return request(`/api/cameras/${id}`);
}

/**
 * Tạo camera mới
 */
export function createCamera(data) {
  return request('/api/cameras', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

/**
 * Cập nhật thông tin cơ bản camera và toạ độ
 */
export function updateCamera(id, data) {
  return request(`/api/cameras/${id}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

/**
 * Tắt camera (soft-delete)
 */
export function decommissionCamera(id) {
  return request(`/api/cameras/${id}/decommission`, {
    method: 'PATCH',
  });
}

/**
 * Kích hoạt lại camera
 */
export function reactivateCamera(id) {
  return request(`/api/cameras/${id}/reactivate`, {
    method: 'PATCH',
  });
}

/**
 * Cập nhật/Tạo mới đặc tả kỹ thuật camera
 */
export function upsertSpecification(id, data) {
  return request(`/api/cameras/${id}/specification`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

/**
 * Cập nhật/Tạo mới cấu hình stream camera
 */
export function upsertStreamConfig(id, data) {
  return request(`/api/cameras/${id}/stream-config`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

/**
 * Cập nhật/Tạo mới cấu hình AI camera
 */
export function upsertAIConfig(id, data) {
  return request(`/api/cameras/${id}/ai-config`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

/**
 * Xem lịch sử health logs của camera
 */
export function fetchHealthLogs(id, { page = 0, size = 10 } = {}) {
  return request(`/api/cameras/${id}/health-logs?page=${page}&size=${size}`);
}
