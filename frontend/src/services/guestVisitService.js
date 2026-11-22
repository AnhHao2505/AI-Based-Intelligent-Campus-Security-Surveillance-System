import { apiGet, apiPost, apiPatch, apiFetch } from "../api/apiClient";

/**
 * Lượt khách (BR-GV v2). Backend: GuestVisitController (/api/guest-visits).
 */
export const guestVisitService = {
  /**
   * Khu vực có thể chọn cho lượt khách (BR-GV-04, V83): khu vực Bảo mật đang hoạt động.
   * GET /api/guest-visits/selectable-areas
   * Gửi kèm đủ { startTime, endTime } (ISO) thì mỗi khu vực có thêm hostCanInvite + reasonCode (BR-GV-06):
   * người gọi có vào được khu vực trong trọn khung giờ không (cấp truy cập hoặc nhân sự chỉ định).
   */
  async getSelectableAreas({ startTime, endTime } = {}) {
    if (startTime && endTime) {
      const query = new URLSearchParams({ startTime, endTime });
      return await apiGet(`/api/guest-visits/selectable-areas?${query.toString()}`);
    }
    return await apiGet("/api/guest-visits/selectable-areas");
  },

  /**
   * Điều kiện mời khách của người đang đăng nhập (B-04):
   * { canHost, myLevel, minLevel, consentNoticeVersion, photoMaxKb }
   * GET /api/guest-visits/eligibility
   */
  async getEligibility() {
    return await apiGet("/api/guest-visits/eligibility");
  },

  /**
   * Host tạo lượt kèm ảnh khách (multipart): phần "data" (JSON)
   * { purpose, startTime, endTime, areaIds[], consentNoticeVersion, guests[{ fullName, organization, consentConfirmed }] }
   * và phần "photos" là ảnh của từng khách theo đúng thứ tự guests.
   * Lỗi gắn với một khách có err.data.data.guestIndex (xem getErrorGuestIndex).
   */
  async createVisit(data, photos = []) {
    const formData = new FormData();
    formData.append("data", new Blob([JSON.stringify(data)], { type: "application/json" }));
    photos.forEach((file) => formData.append("photos", file));
    return await apiFetch("/api/guest-visits", {
      method: "POST",
      body: formData,
    });
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

  /** Host thay ảnh khách của lượt mình (lượt Chờ duyệt / Đã duyệt chưa tới giờ bắt đầu), kèm xác nhận đồng ý */
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

  /** URL xem ảnh khách có hạn (presigned): host xem khách của lượt mình, FM / ADMIN xem mọi lượt. Mỗi lần xem ghi audit. */
  async getGuestPhotoUrl(visitId, guestId) {
    return await apiPost(`/api/guest-visits/${visitId}/guests/${guestId}/photo-url`);
  },
};

export default guestVisitService;
