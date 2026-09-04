import { apiGet, apiPost, apiPatch } from '../api/apiClient';

/**
 * Service handling Access Requests and selectable areas.
 */
export const accessRequestService = {
  /**
   * Lấy danh sách các khu vực khả dụng cho việc yêu cầu (SEMI_PRIVATE & PRIVATE)
   * Sử dụng API chuyên dụng, nhẹ, không kèm dữ liệu toạ độ hình học nặng.
   */
  async getAvailableAreas() {
    return await apiGet('/api/access-requests/available-areas');
  },

  /**
   * Tạo yêu cầu truy cập mới (Cá nhân hoặc Nhóm)
   * @param {Object} data - { areaId, requestType, startTime, endTime, purpose, memberUserCodes }
   */
  async createRequest(data) {
    return await apiPost('/api/access-requests', data);
  },

  /**
   * Lấy danh sách yêu cầu của bản thân người dùng đăng nhập
   * @param {Object} params - { status, page, size }
   */
  async getMyRequests({ status, page = 0, size = 10 } = {}) {
    const query = new URLSearchParams();
    if (status) query.append('status', status);
    query.append('page', page);
    query.append('size', size);
    return await apiGet(`/api/access-requests/my?${query.toString()}`);
  },

  /**
   * Dành cho FM / Quản trị viên: Lấy tất cả yêu cầu để phê duyệt
   * @param {Object} params - { status, page, size }
   */
  async getAllRequests({ status, page = 0, size = 10 } = {}) {
    const query = new URLSearchParams();
    if (status) query.append('status', status);
    query.append('page', page);
    query.append('size', size);
    return await apiGet(`/api/access-requests?${query.toString()}`);
  },

  /**
   * Lấy chi tiết một yêu cầu truy cập
   * @param {string} id - UUID của yêu cầu
   */
  async getRequestById(id) {
    return await apiGet(`/api/access-requests/${id}`);
  },

  /**
   * Phê duyệt hoặc Từ chối yêu cầu truy cập
   * @param {string} id - UUID của yêu cầu
   * @param {Object} data - { status: 'APPROVED' | 'REJECTED', rejectionReason?: string }
   */
  async reviewRequest(id, data) {
    return await apiPatch(`/api/access-requests/${id}/review`, data);
  },

  /**
   * Tra cứu thông tin người dùng theo mã số (userCode / MSSV / MSNV)
   * Dùng để thêm thành viên nhóm trong flow tạo yêu cầu
   * @param {string} code - Mã người dùng
   */
  async getUserByCode(code) {
    return await apiGet(`/api/users/${encodeURIComponent(code)}`);
  },
};

export default accessRequestService;
