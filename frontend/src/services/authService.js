import { apiGet } from '../api/apiClient';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

/**
 * Gửi Google ID token lên backend để xác thực
 * POST /api/auth/google
 * Giữ nguyên fetch trực tiếp do gọi khi chưa có token
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
    const error = await response
      .json()
      .catch(() => ({ message: 'Authentication failed' }));
    throw new Error(error.message || `HTTP ${response.status}`);
  }

  return response.json();
}

/**
 * Lấy thông tin user hiện tại qua apiClient
 * GET /api/auth/me (Cần token, 401 tự động redirect về /login)
 */
export async function getCurrentUser() {
  return apiGet('/api/auth/me');
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

/**
 * Đăng nhập bằng email và password
 * Giữ nguyên fetch trực tiếp do gọi khi chưa có token
 */
export async function loginWithCredentials(email, password) {
  const response = await fetch(`${API_BASE_URL}/api/auth/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ email, password }),
  });

  if (!response.ok) {
    const error = await response
      .json()
      .catch(() => ({ message: 'Đăng nhập thất bại' }));
    throw new Error(error.message || `HTTP ${response.status}`);
  }

  return response.json();
}

/**
 * Gửi yêu cầu nhận link reset password qua email
 * Giữ nguyên fetch trực tiếp do gọi khi chưa đăng nhập
 */
export async function sendResetLink(email) {
  const response = await fetch(`${API_BASE_URL}/api/auth/send-reset-link`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ email }),
  });

  if (!response.ok) {
    const error = await response
      .json()
      .catch(() => ({ message: 'Gửi yêu cầu thất bại' }));
    throw new Error(error.message || `HTTP ${response.status}`);
  }
}

/**
 * Khôi phục mật khẩu mới bằng token xác nhận
 * Giữ nguyên fetch trực tiếp do gọi khi chưa đăng nhập
 */
export async function resetPasswordWithToken(token, newPassword) {
  const response = await fetch(`${API_BASE_URL}/api/auth/reset-password`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ token, newPassword }),
  });

  if (!response.ok) {
    const error = await response
      .json()
      .catch(() => ({ message: 'Đổi mật khẩu thất bại' }));
    throw new Error(error.message || `HTTP ${response.status}`);
  }
}
