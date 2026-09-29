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
