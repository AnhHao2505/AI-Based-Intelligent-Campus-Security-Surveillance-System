const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

function getDemoResponse(path) {
  if (path.includes('/auth/me')) return JSON.parse(localStorage.getItem('user') || 'null');
  if (path.includes('/areas')) return { content: [], totalElements: 0, totalPages: 0 };
  if (path.includes('/floor-plans') || path.includes('/notifications')) return [];
  if (path.includes('/access-requests/available-areas')) return [];
  if (path.includes('/guard-shifts') || path.includes('/guard-schedules')) return [];
  if (path.includes('/api/users')) {
    return {
      users: {
        content: [
          {
            id: 'fa744a70-3db2-434d-a6c3-6c00a452578c',
            fullName: 'Nguyễn Văn An (Bảo Vệ)',
            userCode: 'NV-BV01',
            email: 'guard.an@fpt.edu.vn',
            role: 'GUARD',
            isActive: true,
          },
          {
            id: '84cc1997-bae7-4a88-a20e-519b857cb722',
            fullName: 'Bảo Vệ Demo',
            userCode: 'NV-BV02',
            email: 'guard.demo@fpt.edu.vn',
            role: 'GUARD',
            isActive: true,
          },
        ],
        totalElements: 2,
        totalPages: 1,
      },
      normalCount: 0,
      systemCount: 2,
    };
  }
  if (path.includes('/cameras') || path.includes('/access-requests')) {
    return { content: [], totalElements: 0, totalPages: 0 };
  }
  return {};
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

  if (token?.startsWith('frontend-demo-')) {
    return (options.method || 'GET').toUpperCase() === 'GET'
      ? getDemoResponse(path)
      : null;
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
    localStorage.removeItem('accessToken');
    localStorage.removeItem('user');
    if (window.location.pathname !== '/login') {
      window.location.href = '/login';
    }
    const err = new Error('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
    err.status = 401;
    throw err;
  }

  if (response.status === 403) {
    const errorData = await response.json().catch(() => null);
    const err = new Error(errorData?.message || 'Bạn không có quyền thực hiện thao tác này.');
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
    const err = new Error(errorData?.message || `Yêu cầu thất bại (HTTP ${response.status})`);
    err.status = response.status;
    err.code = errorData?.code;
    err.data = errorData;
    throw err;
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
