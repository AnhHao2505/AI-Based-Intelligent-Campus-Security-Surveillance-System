import { apiGet, apiPost, apiPatch, apiFetch } from "../api/apiClient";

/**
 * Lượt khách (BR-GV v2). Backend: GuestVisitController (/api/guest-visits).
 */
export const guestVisitService = {
  /**
   * Khu vực có thể chọn cho lượt khách (BR-GV-04): khu vực INTERNAL / CONTACT đang hoạt động, gồm cả khu vực
   * INTERNAL (danh sách xin truy cập /api/areas/available-for-request không có loại này).
   * GET /api/guest-visits/selectable-areas
   */
  async getSelectableAreas() {
    return await apiGet("/api/guest-visits/selectable-areas");
  },

  /** Host tạo lượt: { purpose, startTime, endTime, areaIds[], guests[{ fullName, organization }] } */
  /**
   * Điều kiện mời khách của người đang đăng nhập (B-04): { canHost, myLevel, minLevel }
   * GET /api/guest-visits/eligibility
   */
  async getEligibility() {
    return await apiGet("/api/guest-visits/eligibility");
  },

  async createVisit(data) {
    return await apiPost("/api/guest-visits", data);
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

  /** FM / ADMIN: danh sách toàn bộ lượt khách (Page) */
  async listVisits({ status, page = 0, size = 10 } = {}) {
    const params = new URLSearchParams();
    if (status && status !== "ALL") {
      params.append("status", status);
    }
    params.append("page", String(page));
    params.append("size", String(size));
    return await apiGet(`/api/guest-visits?${params.toString()}`);
  },

  /** FM duyệt / từ chối: { version, decision, reason } */
  async reviewVisit(id, { version, decision, reason } = {}) {
    const body = { version, decision };
    if (reason !== undefined && reason !== null && reason.trim()) {
      body.reason = reason.trim();
    }
    return await apiPatch(`/api/guest-visits/${id}/review`, body);
  },

  /** FM thu hồi lượt đã duyệt: { version, reason } */
  async revokeVisit(id, { version, reason } = {}) {
    const body = { version };
    if (reason !== undefined && reason !== null && reason.trim()) {
      body.reason = reason.trim();
    }
    return await apiPatch(`/api/guest-visits/${id}/revoke`, body);
  },

  /** ADMIN: Gắn ảnh cho khách (Multipart FormData kèm xác nhận đồng ý) */
  async uploadGuestPhoto(visitId, guestId, { file, consentConfirmed, consentNoticeVersion }) {
    const formData = new FormData();
    if (file) formData.append("file", file);
    if (consentConfirmed !== undefined && consentConfirmed !== null) {
      formData.append("consentConfirmed", String(consentConfirmed));
    }
    if (consentNoticeVersion) {
      formData.append("consentNoticeVersion", consentNoticeVersion);
    }
    return await apiFetch(`/api/guest-visits/${visitId}/guests/${guestId}/photo`, {
      method: "POST",
      body: formData,
    });
  },

  /** ADMIN: Lấy URL xem ảnh khách có hạn (presigned) */
  async getGuestPhotoUrl(visitId, guestId) {
    return await apiPost(`/api/guest-visits/${visitId}/guests/${guestId}/photo-url`);
  },
};

export default guestVisitService;
