import { apiGet, apiPost, apiPut, apiPatch, apiDelete, apiFetch } from '../api/apiClient';

/**
 * Lấy danh sách tài khoản người dùng có phân trang, tìm kiếm và bộ lọc (ADMIN)
 * GET /api/admin/users
 */
export async function getUsers(params = {}) {
  const query = new URLSearchParams();
  if (params.keyword) query.append('keyword', params.keyword);
  if (params.accountType) query.append('accountType', params.accountType);
  if (params.isActive !== undefined && params.isActive !== null && params.isActive !== '') {
    query.append('isActive', params.isActive);
  }
  if (params.page !== undefined) query.append('page', params.page);
  if (params.size !== undefined) query.append('size', params.size);
  if (params.sort) query.append('sort', params.sort);

  const queryString = query.toString();
  return apiGet(`/api/admin/users${queryString ? `?${queryString}` : ''}`);
}

/**
 * Lấy số lượng tài khoản theo phân nhóm Người dùng thường và Tài khoản hệ thống (ADMIN)
 * GET /api/admin/users/counts
 */
export async function getUserCounts() {
  return apiGet('/api/admin/users/counts');
}

/**
 * Nạp danh sách người dùng thường từ file CSV (ADMIN)
 * POST /api/admin/users/import
 */
export async function importUsers(file) {
  const formData = new FormData();
  formData.append('file', file);
  return apiFetch('/api/admin/users/import', {
    method: 'POST',
    body: formData,
  });
}

/**
 * Tải file CSV mẫu để import người dùng
 * GET /api/admin/users/template
 */
export async function downloadUserTemplate() {
  const token = localStorage.getItem('accessToken');
  const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
  const response = await fetch(`${API_BASE_URL}/api/admin/users/template`, {
    headers: {
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  });
  if (!response.ok) {
    throw new Error('Không thể tải file mẫu. Vui lòng thử lại sau.');
  }
  const blob = await response.blob();
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'sample_users.csv';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.URL.revokeObjectURL(url);
}

/**
 * Đăng ký/Tạo tài khoản mới
 * POST /api/auth/register
 */
export async function registerUser(data) {
  return apiPost('/api/auth/register', data);
}

/**
 * Cập nhật thông tin tài khoản (Họ tên, Quyền) (ADMIN)
 * PUT /api/admin/users/{id}
 */
export async function updateUser(id, data) {
  return apiPut(`/api/admin/users/${id}`, data);
}

/**
 * Bật/Tắt trạng thái hoạt động của tài khoản (ADMIN)
 * PATCH /api/admin/users/{id}/toggle-active
 */
export async function toggleUserActive(id) {
  return apiPatch(`/api/admin/users/${id}/toggle-active`);
}

/**
 * Xóa mềm tài khoản (ADMIN)
 * DELETE /api/admin/users/{id}
 */
export async function deleteUser(id) {
  return apiDelete(`/api/admin/users/${id}`);
}
