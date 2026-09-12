import { apiGet, apiPost, apiPatch, apiDelete, apiFetch } from '../api/apiClient';

/**
 * Lấy danh sách tài khoản người dùng kèm tổng số lượng phân nhóm (ADMIN)
 * GET /api/users
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
  return apiGet(`/api/users${queryString ? `?${queryString}` : ''}`);
}

/**
 * Tạo tài khoản cán bộ/nhân viên thủ công kèm ảnh khuôn mặt (ADMIN)
 * POST /api/users/staff-accounts (multipart/form-data)
 */
export async function createStaffAccount(data) {
  const formData = new FormData();
  formData.append('fullName', data.fullName);
  formData.append('userCode', data.userCode);
  formData.append('email', data.email);
  formData.append('role', data.role);
  if (data.faceImage) {
    formData.append('faceImage', data.faceImage);
  }

  return apiFetch('/api/users/staff-accounts', {
    method: 'POST',
    body: formData,
  });
}

/**
 * Tải file CSV mẫu để import người dùng
 * GET /api/users/csv-template
 */
export async function downloadUserTemplate() {
  const token = localStorage.getItem('accessToken');
  const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
  const response = await fetch(`${API_BASE_URL}/api/users/csv-template`, {
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
 * Đăng ký/Tạo tài khoản người dùng
 * POST /api/auth/register
 */
export async function registerUser(data) {
  return apiPost('/api/auth/register', data);
}

/**
 * Bật/Tắt trạng thái hoạt động của tài khoản (ADMIN)
 * PATCH /api/users/{id}/toggle-active
 */
export async function toggleUserActive(id) {
  return apiPatch(`/api/users/${id}/toggle-active`);
}

/**
 * Xóa mềm tài khoản (ADMIN)
 * DELETE /api/users/{id}
 */
export async function deleteUser(id) {
  return apiDelete(`/api/users/${id}`);
}

/**
 * Nạp hàng loạt tài khoản người dùng thông thường từ file ZIP (.zip)
 * POST /api/users/normal/bulk-import (multipart/form-data)
 */
export async function bulkImportNormalUsers(file) {
  const formData = new FormData();
  formData.append('file', file);

  return apiFetch('/api/users/normal/bulk-import', {
    method: 'POST',
    body: formData,
  });
}

/**
 * Tải file CSV mẫu cho Nạp hàng loạt tài khoản thông thường
 * GET /api/users/normal/bulk-import/template
 */
export async function downloadNormalUserTemplate() {
  const token = localStorage.getItem('accessToken');
  const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
  const response = await fetch(`${API_BASE_URL}/api/users/normal/bulk-import/template`, {
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
  a.download = 'metadata.xlsx';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.URL.revokeObjectURL(url);
}

/**
 * Nạp hàng loạt tài khoản cán bộ/nhân viên từ file ZIP (.zip) (ADMIN)
 * POST /api/users/staff/bulk-import (multipart/form-data)
 */
export async function bulkImportStaffUsers(file) {
  const formData = new FormData();
  formData.append('file', file);

  return apiFetch('/api/users/staff/bulk-import', {
    method: 'POST',
    body: formData,
  });
}

/**
 * Tải file Excel mẫu cho Nạp hàng loạt tài khoản cán bộ/nhân viên
 * GET /api/users/staff/bulk-import/template
 */
export async function downloadStaffUserTemplate() {
  const token = localStorage.getItem('accessToken');
  const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';
  const response = await fetch(`${API_BASE_URL}/api/users/staff/bulk-import/template`, {
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
  a.download = 'metadata.xlsx';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  window.URL.revokeObjectURL(url);
}

/**
 * Lấy danh sách các lô import theo phân trang (ADMIN)
 * GET /api/users/import-batches
 */
export async function getImportBatches(page = 0, size = 10) {
  return apiGet(`/api/users/import-batches?page=${page}&size=${size}`);
}

/**
 * Lấy danh sách tài khoản thuộc lô import (ADMIN)
 * GET /api/users/import-batches/{batchId}
 */
export async function getImportBatchDetails(batchId) {
  return apiGet(`/api/users/import-batches/${batchId}`);
}

/**
 * Gỡ lô import (soft-delete các tài khoản trong lô) (ADMIN)
 * DELETE /api/users/import-batches/{batchId}
 */
export async function deleteImportBatch(batchId) {
  return apiDelete(`/api/users/import-batches/${batchId}`);
}

/**
 * Khôi phục các tài khoản đã gỡ trong lô import (ADMIN)
 * POST /api/users/import-batches/{batchId}/restore
 */
export async function restoreImportBatch(batchId) {
  return apiPost(`/api/users/import-batches/${batchId}/restore`);
}


