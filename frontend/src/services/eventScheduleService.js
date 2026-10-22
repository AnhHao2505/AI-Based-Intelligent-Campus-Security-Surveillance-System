import { apiGet } from '../api/apiClient';

/**
 * Danh sách lịch sự kiện của mọi khu vực cho màn Quản lý sự kiện (FACILITY_MANAGER, ADMIN).
 * GET /api/event-schedules — sắp theo giờ bắt đầu giảm dần. Tạo / sửa / huỷ dùng areaService (theo khu).
 * @param {Object} [params]
 * @param {string} [params.status] SCHEDULED | STARTED | COMPLETED | ENDED_EARLY | CANCELLED | FAILED — bỏ trống = mọi trạng thái
 * @param {string} [params.areaId]
 * @param {string} [params.from] yyyy-MM-dd — lấy lịch giao với khoảng [from, to]
 * @param {string} [params.to] yyyy-MM-dd
 * @param {string} [params.q] tìm trong tên sự kiện và tên khu
 * @param {number} [params.page=0]
 * @param {number} [params.size=10]
 * @returns {Promise<{content: Array, totalPages: number, totalElements: number}>}
 */
export async function getAllEventSchedules({ status, areaId, from, to, q, page = 0, size = 10 } = {}) {
  const query = new URLSearchParams();
  if (status) query.append('status', status);
  if (areaId) query.append('areaId', areaId);
  if (from) query.append('from', from);
  if (to) query.append('to', to);
  if (q) query.append('q', q);
  query.append('page', page);
  query.append('size', size);
  return apiGet(`/api/event-schedules?${query.toString()}`);
}
