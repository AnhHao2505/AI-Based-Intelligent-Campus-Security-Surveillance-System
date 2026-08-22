const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

/**
 * Gửi Google ID token lên backend để xác thực
 * POST /api/auth/google
 */
export async function loginWithGoogle(idToken) {
  const response = await fetch(`${API_BASE_URL}/api/auth/google`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ idToken }),
  });

  if (!response.ok) {
    const error = await response.json().catch(() => ({ message: 'Authentication failed' }));
    throw new Error(error.message || `HTTP ${response.status}`);
  }

  return response.json();
}

/**
 * Lấy thông tin user hiện tại
 * GET /api/auth/me
 */
export async function getCurrentUser(accessToken) {
  const response = await fetch(`${API_BASE_URL}/api/auth/me`, {
    method: 'GET',
    headers: {
      'Authorization': `Bearer ${accessToken}`,
      'Content-Type': 'application/json',
    },
  });

  if (!response.ok) {
    throw new Error('Failed to fetch user info');
  }

  return response.json();
}

/**
 * Lưu auth data vào localStorage
 */
export function saveAuth(authResponse) {
  localStorage.setItem('accessToken', authResponse.accessToken);
  localStorage.setItem('user', JSON.stringify(authResponse.user));
}

/**
 * Lấy token từ localStorage
 */
export function getAccessToken() {
  return localStorage.getItem('accessToken');
}

/**
 * Lấy user info từ localStorage
 */
export function getStoredUser() {
  const user = localStorage.getItem('user');
  return user ? JSON.parse(user) : null;
}

/**
 * Xoá auth data (logout)
 */
export function clearAuth() {
  localStorage.removeItem('accessToken');
  localStorage.removeItem('user');
}

/**
 * Kiểm tra đã đăng nhập chưa
 */
export function isAuthenticated() {
  return !!getAccessToken();
}
