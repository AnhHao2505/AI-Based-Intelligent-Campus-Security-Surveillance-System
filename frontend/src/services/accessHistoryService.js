import { apiGet } from '../api/apiClient';

/**
 * Lịch sử ra vào tại camera (bảng camera_access_logs, gồm cả lượt được và không được vào).
 * Thời gian from / to là chuỗi ISO có offset; bỏ trống thì backend lấy 7 ngày gần nhất.
 */

function buildQuery(params) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.append(key, value);
    }
  });
  return query.toString();
}

/**
 * Lịch sử ra vào của chính người đang đăng nhập (NORMAL_USER, FACILITY_MANAGER, ADMIN).
 * Người được xác định từ token — không có tham số để xem người khác.
 * @param {{from?: string, to?: string, page?: number, size?: number}} params
 */
export async function getMyAccessHistory({ from, to, page = 0, size = 20 } = {}) {
  return apiGet(`/api/access-history/my?${buildQuery({ from, to, page, size })}`);
}

/**
 * Lịch sử ra vào toàn hệ thống (FACILITY_MANAGER, ADMIN), có mã + họ tên người.
 * @param {{areaId?: string, cameraCode?: string, userCode?: string, accessResult?: string,
 *          from?: string, to?: string, page?: number, size?: number}} params
 */
export async function searchAccessHistory({
  areaId, cameraCode, userCode, accessResult, from, to, page = 0, size = 20,
} = {}) {
  return apiGet(`/api/access-history?${buildQuery({ areaId, cameraCode, userCode, accessResult, from, to, page, size })}`);
}
