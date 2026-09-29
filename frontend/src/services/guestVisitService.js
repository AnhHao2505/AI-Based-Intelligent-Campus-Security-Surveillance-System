import { apiGet, apiPost, apiPatch } from '../api/apiClient';

/**
 * Lượt khách (BR-GV v2). Backend: GuestVisitController (/api/guest-visits).
 */
export const guestVisitService = {
  /** Khu vực có thể chọn cho lượt khách: lấy danh sách khu vực hạn chế rồi lọc INTERNAL / CONTACT ở phía gọi. */
  async getSelectableAreas() {
    return await apiGet('/api/areas/available-for-request');
  },

  /** Host tạo lượt: { purpose, startTime, endTime, areaIds[], guests[{ fullName, organization }] } */
  async createVisit(data) {
    return await apiPost('/api/guest-visits', data);
  },

  /** Lượt của chính người đăng nhập (Page) */
  async getMyVisits({ page = 0, size = 10 } = {}) {
    const query = new URLSearchParams({ page: String(page), size: String(size) });
    return await apiGet(`/api/guest-visits/my?${query.toString()}`);
  },

  async getVisit(id) {
    return await apiGet(`/api/guest-visits/${id}`);
  },

  /** Host huỷ: { version, reason? } */
  async cancelVisit(id, { version, reason }) {
    const body = { version };
    if (reason && reason.trim()) body.reason = reason.trim();
    return await apiPatch(`/api/guest-visits/${id}/cancel`, body);
  },
};

export default guestVisitService;
