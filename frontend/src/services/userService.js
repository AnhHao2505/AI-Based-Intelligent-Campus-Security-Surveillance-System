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

/**
 * Tìm kiếm người dùng theo từ khoá (FACILITY_MANAGER, ADMIN)
 * GET /api/users/search?q=&page=&size=&accessLevel=
 * @param {number} [accessLevel] - BR-AL-28: lọc theo cấp hiện tại (bỏ trống = mọi cấp)
 */
export async function searchUsers(q, page = 0, size = 20, accessLevel) {
  const query = new URLSearchParams({
    page: String(page),
    size: String(size),
  });
  if (q && q.trim()) query.append('q', q.trim());
  if (accessLevel) query.append('accessLevel', String(accessLevel));
  return apiGet(`/api/users/search?${query.toString()}`);
}

/**
 * Đổi cấp truy cập cho nhiều người (FACILITY_MANAGER) — BR-AL-28
 * PATCH /api/users/bulk-access-level
 * @returns {{ total, updated, unchanged, failed, results: Array<{userId, userCode, fullName, outcome, oldLevel, newLevel, message}> }}
 */
export async function bulkUpdateUserAccessLevel(userIds, accessLevel, reason) {
  return apiPatch('/api/users/bulk-access-level', { userIds, accessLevel, reason });
}

/**
 * Cập nhật cấp độ truy cập của người dùng (FACILITY_MANAGER)
 * PATCH /api/users/{id}/access-level
 */
export async function updateUserAccessLevel(id, accessLevel, reason) {
  return apiPatch(`/api/users/${id}/access-level`, { accessLevel, reason });
}



/**
 * PR-AUDIT: nhật ký tài khoản / đăng nhập / cấu hình cho tab "Nhật ký" (ADMIN)
 * GET /api/users/audit-logs
 * @param {{ modules?: string[], actions?: string[], subject?: string, subjectUserId?: string, actor?: string,
 *           correlationId?: string, from?: string, to?: string, page?: number, size?: number }} params
 * @returns {Promise<{content: Array, totalPages: number, totalElements: number}>}
 */
export async function getAccountAuditLogs(params = {}) {
  const query = new URLSearchParams();
  (params.modules || []).forEach((m) => query.append('modules', m));
  (params.actions || []).forEach((a) => query.append('actions', a));
  if (params.subject) query.append('subject', params.subject);
  if (params.subjectUserId) query.append('subjectUserId', params.subjectUserId);
  if (params.actor) query.append('actor', params.actor);
  if (params.correlationId) query.append('correlationId', params.correlationId);
  if (params.from) query.append('from', params.from);
  if (params.to) query.append('to', params.to);
  if (params.page !== undefined) query.append('page', String(params.page));
  if (params.size !== undefined) query.append('size', String(params.size));
  const queryString = query.toString();
  return apiGet(`/api/users/audit-logs${queryString ? `?${queryString}` : ''}`);
}
