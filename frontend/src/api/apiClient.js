import { clearStoredAuth } from '../utils/authStorage';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

// Không hiện mã lỗi (ERR_XXX, reason phrase HTTP) trong câu cho người dùng.
// Mã nghiệp vụ giữ ở err.code và gán vào err.name để console.error vẫn in "ERR_XXX: <message>".
function tagBusinessCode(err, code) {
  if (code && /^[A-Z][A-Z0-9_]*$/.test(code)) err.name = code;
  return err;
}

// Lỗi hệ thống (5xx): câu thân thiện, giữ mã tra cứu nếu backend có trả
function toSystemErrorMessage(rawMessage) {
  const match = typeof rawMessage === 'string' ? rawMessage.match(/Mã tra cứu:\s*([\w-]+)/) : null;
  return match ? `Đã có lỗi xảy ra. Mã tra cứu: ${match[1]}` : 'Đã có lỗi xảy ra. Vui lòng thử lại.';
}



function toApiUrl(path) {
  return path.startsWith('http') ? path : `${API_BASE_URL}${path.startsWith('/') ? '' : '/'}${path}`;
}

// 401: xoá phiên, về trang đăng nhập, ném lỗi có status
function handleUnauthorized() {
  clearStoredAuth();
  if (window.location.pathname !== '/login') {
    window.location.href = '/login';
  }
  const err = new Error('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
  err.status = 401;
  throw err;
}

/**
 * Native fetch wrapper with automatic Authorization header,
 * 401 auto-logout & redirect, and error handling.
 */
export async function apiFetch(path, options = {}) {
  const token = localStorage.getItem('accessToken');
  const headers = {
    ...options.headers,
  };

  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  if (options.body && !(options.body instanceof FormData) && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }



  let response;
  try {
    response = await fetch(toApiUrl(path), {
      ...options,
      headers,
    });
  } catch {
    throw new Error('Không thể kết nối đến máy chủ. Vui lòng kiểm tra lại kết nối mạng hoặc máy chủ Backend.');
  }

  if (response.status === 401) {
    handleUnauthorized();
  }

  if (response.status === 403) {
    const errorData = await response.json().catch(() => null);
    const msg = errorData?.message || 'Bạn không có quyền thực hiện thao tác này.';
    const err = tagBusinessCode(new Error(msg), errorData?.code);
    err.status = 403;
    err.code = errorData?.code;
    err.data = errorData;
    throw err;
  }

  if (response.status === 204) {
    return null;
  }

  if (!response.ok) {
    const errorData = await response.json().catch(() => null);
    let msg = errorData?.message;
    if (!msg && errorData?.details) {
      if (typeof errorData.details === 'object') {
        msg = Object.entries(errorData.details)
          .map(([field, fieldErr]) => `${field}: ${fieldErr}`)
          .join(', ');
      } else {
        msg = String(errorData.details);
      }
    }
    if (!msg && errorData?.error) {
      msg = errorData.error;
    }
    if (!msg) {
      msg = `Yêu cầu thất bại (HTTP ${response.status})`;
    }
    if (response.status >= 500) {
      msg = toSystemErrorMessage(errorData?.message);
    }
    const err = tagBusinessCode(new Error(msg), errorData?.code);
    err.status = response.status;
    err.code = errorData?.code;
    err.data = errorData;
    throw err;
  }

  const json = await response.json();
  if (json && typeof json === 'object' && 'httpCode' in json && 'message' in json) {
    if (json.data !== undefined && json.data !== null) {
      if (typeof json.data === 'object' && !Array.isArray(json.data)) {
        json.data._message = json.message;
        json.data._httpCode = json.httpCode;
      }
      return json.data;
    }
    return { message: json.message, httpCode: json.httpCode, success: true };
  }
  return json;
}

/**
 * Tải dữ liệu nhị phân (vd. ảnh /api/media/...) có gắn Bearer token.
 * Cùng API_BASE_URL và cùng xử lý 401 như apiFetch; lỗi khác ném Error có .status.
 *
 * @param {string} path - đường dẫn API, vd. "/api/media/camera-snapshots/CAM-01/roi.jpg"
 * @returns {Promise<Blob>}
 */
export async function apiFetchBlob(path) {
  const token = localStorage.getItem('accessToken');

  let response;
  try {
    response = await fetch(toApiUrl(path), {
      method: 'GET',
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
  } catch {
    throw new Error('Không thể kết nối đến máy chủ. Vui lòng kiểm tra lại kết nối mạng hoặc máy chủ Backend.');
  }

  if (response.status === 401) {
    handleUnauthorized();
  }

  if (!response.ok) {
    const errorData = await response.json().catch(() => null);
    const msg = response.status >= 500
      ? toSystemErrorMessage(errorData?.message)
      : errorData?.message || `Yêu cầu thất bại (HTTP ${response.status})`;
    const err = tagBusinessCode(new Error(msg), errorData?.code);
    err.status = response.status;
    err.code = errorData?.code;
    throw err;
  }

  return response.blob();
}

export function apiGet(path, options = {}) {
  return apiFetch(path, { ...options, method: 'GET' });
}

export function apiPost(path, body, options = {}) {
  return apiFetch(path, {
    ...options,
    method: 'POST',
    body: body ? JSON.stringify(body) : undefined,
  });
}

export function apiPut(path, body, options = {}) {
  return apiFetch(path, {
    ...options,
    method: 'PUT',
    body: body ? JSON.stringify(body) : undefined,
  });
}

export function apiPatch(path, body, options = {}) {
  return apiFetch(path, {
    ...options,
    method: 'PATCH',
    body: body ? JSON.stringify(body) : undefined,
  });
}

export function apiDelete(path, options = {}) {
  return apiFetch(path, { ...options, method: 'DELETE' });
}
