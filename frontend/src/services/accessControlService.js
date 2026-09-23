import { apiGet, apiPut } from '../api/apiClient';

/**
 * Lấy danh sách 4 preset mặc định theo loại khu vực (ADMIN, FACILITY_MANAGER)
 * GET /api/access-control/level-presets
 */
export async function getLevelPresets() {
  return apiGet('/api/access-control/level-presets');
}

/**
 * Cập nhật preset mặc định của một loại khu vực (chỉ FACILITY_MANAGER)
 * PUT /api/access-control/level-presets/{areaLevel}
 * Body: { areaAccessLevel, explicitAuthorizationRequired, reason, version }
 */
export async function updateLevelPreset(areaLevel, data) {
  return apiPut(`/api/access-control/level-presets/${areaLevel}`, data);
}

/**
 * Tra cứu danh sách nhật ký kiểm toán phân quyền (ADMIN, FACILITY_MANAGER)
 * GET /api/access-control/audit-logs
 * Params: targetType, areaId, subjectUserId, changedBy, from, to, page, size
 */
export async function getAuditLogs(params = {}) {
  const query = new URLSearchParams();
  if (params.targetType) query.append('targetType', params.targetType);
  if (params.areaId) query.append('areaId', params.areaId);
  if (params.subjectUserId) query.append('subjectUserId', params.subjectUserId);
  if (params.changedBy) query.append('changedBy', params.changedBy);
  if (params.from) query.append('from', params.from);
  if (params.to) query.append('to', params.to);
  if (params.page !== undefined) query.append('page', params.page);
  if (params.size !== undefined) query.append('size', params.size);

  const queryString = query.toString();
  return apiGet(`/api/access-control/audit-logs${queryString ? `?${queryString}` : ''}`);
}
