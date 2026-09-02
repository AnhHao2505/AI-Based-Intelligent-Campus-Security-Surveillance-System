import { apiGet, apiPost, apiPut, apiPatch, apiDelete } from '../api/apiClient';

/**
 * Lấy danh sách Area có phân trang và bộ lọc
 * GET /api/areas
 */
export async function getAreas(params = {}) {
  const query = new URLSearchParams();
  if (params.keyword) query.append('keyword', params.keyword);
  if (params.areaLevel !== undefined && params.areaLevel !== null && params.areaLevel !== '') {
    query.append('areaLevel', params.areaLevel);
  }
  if (params.building) query.append('building', params.building);
  if (params.isActive !== undefined && params.isActive !== null) {
    query.append('isActive', params.isActive);
  }
  if (params.page !== undefined) query.append('page', params.page);
  if (params.size !== undefined) query.append('size', params.size);
  if (params.sort) query.append('sort', params.sort);

  const queryString = query.toString();
  return apiGet(`/api/areas${queryString ? `?${queryString}` : ''}`);
}

/**
 * Lấy chi tiết một Area theo ID
 * GET /api/areas/{id}
 */
export async function getAreaById(id) {
  return apiGet(`/api/areas/${id}`);
}

/**
 * Lấy danh sách Area Levels cấu hình an ninh
 * GET /api/area-levels
 */
export async function getAreaLevels() {
  return apiGet('/api/area-levels');
}

/**
 * Kiểm tra các phụ thuộc trước khi xóa/vô hiệu hóa khu vực
 * GET /api/areas/{id}/dependencies
 */
export async function getDependencies(id) {
  return apiGet(`/api/areas/${id}/dependencies`);
}

/**
 * Tạo mới một khu vực (ADMIN)
 * POST /api/areas
 */
export async function createArea(data) {
  return apiPost('/api/areas', data);
}

/**
 * Cập nhật thông tin khu vực (ADMIN)
 * PUT /api/areas/{id}
 */
export async function updateArea(id, data) {
  return apiPut(`/api/areas/${id}`, data);
}

/**
 * Vô hiệu hóa (Soft Delete) khu vực (ADMIN)
 * DELETE /api/areas/{id}
 */
export async function deactivateArea(id) {
  return apiDelete(`/api/areas/${id}`);
}

/**
 * Tạo thời gian sử dụng tạm thời cho phòng (ADMIN)
 * POST /api/areas/{areaId}/temporary-usages
 */
export async function createTemporaryUsage(areaId, data) {
  return apiPost(`/api/areas/${areaId}/temporary-usages`, data);
}

/**
 * Gia hạn thời gian sử dụng tạm thời (ADMIN)
 * PATCH /api/areas/{areaId}/temporary-usages/{temporaryUsageId}/extend
 */
export async function extendTemporaryUsage(areaId, temporaryUsageId, data) {
  return apiPatch(`/api/areas/${areaId}/temporary-usages/${temporaryUsageId}/extend`, data);
}

/**
 * Lấy danh sách floor plan
 * GET /api/floor-plans
 */
export async function getFloorPlans() {
  return apiGet('/api/floor-plans');
}

/**
 * Lấy danh sách Area kèm geometry theo tầng
 * GET /api/areas/geometries?building=&floor=
 * Cả hai tham số đều BẮT BUỘC.
 */
export async function getAreaGeometries(building, floor) {
  const query = new URLSearchParams({ building, floor });
  return apiGet(`/api/areas/geometries?${query.toString()}`);
}

/**
 * Lưu polygon cho một Area (ADMIN)
 * PATCH /api/areas/{id}/geometry
 */
export async function saveAreaGeometry(areaId, vertices) {
  return apiPatch(`/api/areas/${areaId}/geometry`, { vertices });
}

/**
 * Xóa polygon của một Area (ADMIN)
 * DELETE /api/areas/{id}/geometry
 */
export async function deleteAreaGeometry(areaId) {
  return apiDelete(`/api/areas/${areaId}/geometry`);
}


