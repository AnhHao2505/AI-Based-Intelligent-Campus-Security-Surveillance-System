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
  const json = await res.json();
  if (json && typeof json === 'object' && 'httpCode' in json && 'message' in json) {
    return json.data !== undefined ? json.data : json;
  }
  return json;
}

/**
 * Lấy danh sách vị trí thời gian thực của các bảo vệ đang trực
 */
export async function getActiveGuardLocations() {
  return request('/api/guards/active-locations');
}

/**
 * Lấy thông tin Geofence duy nhất của toàn bộ khuôn viên trường
 */
export async function getCampusGeofence() {
  return request('/api/campus/geofence');
}

/**
 * Cập nhật vị trí GPS tự động của bảo vệ
 */
export async function updateGuardLocation(data) {
  return request('/api/guards/me/location', {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

/**
 * Cập nhật đa giác ranh giới Geofence khuôn viên (chỉ dành cho ADMIN)
 */
export async function updateCampusGeofence(data) {
  return request('/api/campus/geofence', {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}
