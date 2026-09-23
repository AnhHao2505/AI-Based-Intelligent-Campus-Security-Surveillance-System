import { apiGet } from '../api/apiClient';

/**
 * Lấy danh sách toàn bộ Tòa nhà (kèm các tầng thuộc tòa nhà)
 * GET /api/buildings
 */
export async function getBuildings() {
  return apiGet('/api/buildings');
}

/**
 * Lấy danh sách các Tầng thuộc một Tòa nhà
 * GET /api/buildings/{id}/floors
 */
export async function getBuildingFloors(buildingId) {
  return apiGet(`/api/buildings/${buildingId}/floors`);
}

/**
 * Lấy toàn bộ danh sách Tầng đang hoạt động
 * GET /api/buildings/floors
 */
export async function getAllFloors() {
  return apiGet('/api/buildings/floors');
}
