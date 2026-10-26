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

export async function triggerTestAlert(eventData) {
  return request('/api/incidents/test-alert', {
    method: 'POST',
    body: JSON.stringify(eventData),
  });
}

export async function triggerBatchTestAlerts(eventsList) {
  return request('/api/incidents/test-alert/batch', {
    method: 'POST',
    body: JSON.stringify(eventsList),
  });
}

export async function getActiveIncidents(building) {
  const query = building ? `?building=${encodeURIComponent(building)}` : '';
  return request(`/api/incidents/active${query}`);
}

export async function claimIncident(incidentId) {
  return request(`/api/incidents/${incidentId}/claim`, {
    method: 'POST',
  });
}

export async function resolveIncident(incidentId, payload) {
  return request(`/api/incidents/${incidentId}/resolve`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/**
 * Nghiệp vụ SOC: Xác nhận đã xem sự cố & kết thúc ghi hình video bằng chứng
 */
export async function acknowledgeIncident(incidentId, cameraCode = "") {
  // Đồng thời gọi trực tiếp AI Service để dừng ghi hình ngay lập tức (zero latency)
  try {
    const payload = { incident_id: String(incidentId) };
    if (cameraCode) {
      payload.camera_code = String(cameraCode).toUpperCase();
    }
    fetch('http://localhost:8000/api/v1/incident-recording/stop', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    }).catch(() => {});
  } catch (e) {}

  return request(`/api/incidents/${incidentId}/acknowledge`, {
    method: 'POST',
  }).catch((err) => {
    console.warn("Backend acknowledge warning:", err);
    return { status: "ACKNOWLEDGED" };
  });
}

/**
 * Nghiệp vụ SOC: Xác nhận đã xem tất cả sự cố & kết thúc toàn bộ video ghi hình bằng chứng
 */
export async function acknowledgeAllIncidents() {
  // Đồng thời gọi trực tiếp AI Service để dừng toàn bộ phiên ghi hình ngay lập tức
  try {
    fetch('http://localhost:8000/api/v1/incident-recording/stop', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ stop_all: true }),
    }).catch(() => {});
  } catch (e) {}

  return request('/api/incidents/acknowledge-all', {
    method: 'POST',
  }).catch((err) => {
    console.warn("Backend acknowledge-all warning:", err);
    return { status: "ALL_ACKNOWLEDGED" };
  });
}

