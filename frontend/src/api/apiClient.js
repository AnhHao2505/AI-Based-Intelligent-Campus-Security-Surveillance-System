const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

/**
 * Native fetch wrapper with automatic Authorization header,
 * 401 auto-logout & redirect, and error handling.
 */
export async function apiFetch(path, options = {}) {
  const token = localStorage.getItem('icss_token');
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
    const url = path.startsWith('http') ? path : `${API_BASE_URL}${path.startsWith('/') ? '' : '/'}${path}`;
    response = await fetch(url, {
      ...options,
      headers,
    });
  } catch {
    throw new Error('Không thể kết nối đến máy chủ. Vui lòng kiểm tra lại kết nối mạng hoặc máy chủ Backend.');
  }

  if (response.status === 401) {
    localStorage.removeItem('icss_token');
    localStorage.removeItem('icss_user');
    if (window.location.pathname !== '/login') {
      window.location.href = '/login';
    }
    throw new Error('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
  }

  if (response.status === 403) {
    const errorData = await response.json().catch(() => null);
    throw new Error(errorData?.message || 'Bạn không có quyền thực hiện thao tác này.');
  }

  if (response.status === 204) {
    return null;
  }

  if (!response.ok) {
    const errorData = await response.json().catch(() => null);
    throw new Error(errorData?.message || `Yêu cầu thất bại (HTTP ${response.status})`);
  }

  return await response.json();
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

export function apiDelete(path, options = {}) {
  return apiFetch(path, { ...options, method: 'DELETE' });
}
