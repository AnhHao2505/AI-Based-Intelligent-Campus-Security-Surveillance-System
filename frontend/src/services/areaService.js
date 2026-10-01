import { apiGet, apiPost, apiPut, apiPatch, apiDelete } from "../api/apiClient";

/**
 * Lấy danh sách Area có phân trang và bộ lọc
 * GET /api/areas
 */
export async function getAreas(params = {}) {
	const query = new URLSearchParams();
	if (params.keyword) query.append("keyword", params.keyword);
	if (
		params.areaLevel !== undefined &&
		params.areaLevel !== null &&
		params.areaLevel !== ""
	) {
		query.append("areaLevel", params.areaLevel);
	}
	if (params.building) query.append("building", params.building);
	if (params.isActive !== undefined && params.isActive !== null) {
		query.append("isActive", params.isActive);
	}
	if (params.page !== undefined) query.append("page", params.page);
	if (params.size !== undefined) query.append("size", params.size);
	if (params.sort) query.append("sort", params.sort);

	const queryString = query.toString();
	return apiGet(`/api/areas${queryString ? `?${queryString}` : ""}`);
}

/**
 * Lấy chi tiết một Area theo ID
 * GET /api/areas/{id}
 */
export async function getAreaById(id) {
	return apiGet(`/api/areas/${id}`);
}

/**
 * Xem trước vô hiệu hoá khu vực (chỉ đọc, ADMIN) — Step 6 BR-AD-08
 * GET /api/areas/{id}/dependencies
 * @returns {Promise<{areaId, version, canDeactivate, blockers: Array<{errorCode, count, message}>,
 *   apToRevoke, requestsToCancel, guestVisitsToCancel, guestVisitsToRevoke, note}>}
 */
export async function getDependencies(id) {
	return apiGet(`/api/areas/${id}/dependencies`);
}

/**
 * Tạo mới một khu vực (ADMIN)
 * POST /api/areas
 */
export async function createArea(data) {
	return apiPost("/api/areas", data);
}

/**
 * Cập nhật thông tin khu vực (ADMIN)
 * PUT /api/areas/{id}
 * @param {Object} data { name, areaLevel, building, floor, floorId, centerLatitude, centerLongitude, reason, version }
 *   reason bắt buộc 10–500 ký tự khi đổi loại khu vực
 */
export async function updateArea(id, data) {
	return apiPut(`/api/areas/${id}`, data);
}

/**
 * Vô hiệu hoá khu vực (ADMIN) — Step 6 BR-AD-01. Thay cho DELETE /api/areas/{id} (đã bỏ).
 * POST /api/areas/{id}/deactivate
 * @param {Object} data { reason, version } — lý do 10–500 ký tự sau trim, version lấy từ xem trước
 */
export async function deactivateArea(id, data) {
	return apiPost(`/api/areas/${id}/deactivate`, data);
}

/**
 * Khôi phục khu vực đã vô hiệu hoá (ADMIN) — Step 6 BR-AD-07.
 * Không tự khôi phục nhân sự chỉ định, đơn, lượt khách, camera.
 * POST /api/areas/{id}/restore
 * @param {Object} data { reason, version }
 */
export async function restoreArea(id, data) {
	return apiPost(`/api/areas/${id}/restore`, data);
}

/**
 * Lấy danh sách floor plan
 * GET /api/floor-plans
 */
export async function getFloorPlans() {
	return apiGet("/api/floor-plans");
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
 * PATCH /api/areas/{id}/geometry?version=
 * @param {string} areaId
 * @param {Array} vertices
 * @param {number} [version] version khu vực đang xem (bắt buộc từ Step 5b; thiếu -> 400 ERR_AREA_044)
 */
export async function saveAreaGeometry(areaId, vertices, version) {
	const query = version !== undefined && version !== null ? `?version=${encodeURIComponent(version)}` : "";
	return apiPatch(`/api/areas/${areaId}/geometry${query}`, { vertices });
}

/**
 * Xóa polygon của một Area (ADMIN)
 * DELETE /api/areas/{id}/geometry?version=
 * @param {string} areaId
 * @param {number} [version] version khu vực đang xem (bắt buộc từ Step 5b)
 */
export async function deleteAreaGeometry(areaId, version) {
	const query = version !== undefined && version !== null ? `?version=${encodeURIComponent(version)}` : "";
	return apiDelete(`/api/areas/${areaId}/geometry${query}`);
}

/**
 * Lấy danh sách camera đã được gán vào Khu vực
 * GET /api/areas/{id}/cameras
 */
export async function getAreaCameras(areaId) {
	return apiGet(`/api/areas/${areaId}/cameras`);
}

/**
 * Cập nhật danh sách camera gán vào Khu vực (ADMIN)
 * PUT /api/areas/{id}/cameras
 */
export async function updateAreaCameras(areaId, cameraIds) {
	return apiPut(`/api/areas/${areaId}/cameras`, { cameraIds });
}

/**
 * Cập nhật quy tắc truy cập khu vực (FACILITY_MANAGER)
 * PATCH /api/areas/{id}/access-rules
 * @param {Object} data { areaAccessLevel, explicitAuthorizationRequired, reason, version }
 */
export async function updateAreaAccessRules(areaId, data) {
	return apiPatch(`/api/areas/${areaId}/access-rules`, data);
}

/**
 * Lấy danh sách nhân sự được gán vào khu vực (FACILITY_MANAGER, ADMIN)
 * GET /api/areas/{areaId}/assigned-personnel?status=
 */
export async function getAssignedPersonnel(areaId, status) {
	const query = status ? `?status=${encodeURIComponent(status)}` : "";
	return apiGet(`/api/areas/${areaId}/assigned-personnel${query}`);
}

/**
 * Gán nhân sự vào khu vực (FACILITY_MANAGER)
 * POST /api/areas/{areaId}/assigned-personnel
 */
export async function assignPersonnel(areaId, data) {
	return apiPost(`/api/areas/${areaId}/assigned-personnel`, data);
}

/**
 * Cập nhật thời hạn gán nhân sự (FACILITY_MANAGER)
 * PATCH /api/areas/{areaId}/assigned-personnel/{id}
 */
export async function updateAssignedPersonnel(areaId, id, data) {
	return apiPatch(`/api/areas/${areaId}/assigned-personnel/${id}`, data);
}

/**
 * Thu hồi quyền gán nhân sự (FACILITY_MANAGER)
 * PATCH /api/areas/{areaId}/assigned-personnel/{id}/revoke
 */
export async function revokeAssignedPersonnel(areaId, id, data) {
	return apiPatch(`/api/areas/${areaId}/assigned-personnel/${id}/revoke`, data);
}

/**
 * Bật / điều chỉnh / tắt chế độ sự kiện khu vực (chỉ FACILITY_MANAGER)
 * PATCH /api/areas/{areaId}/event-mode
 * @param {string} areaId
 * @param {Object} data { action: "ENABLE" | "ADJUST" | "DISABLE", openUntil, reasonCode, note, version } — KHÔNG gửi enabled
 */
export async function updateAreaEventMode(areaId, data) {
	return apiPatch(`/api/areas/${areaId}/event-mode`, data);
}

/**
 * Lấy danh sách lịch chế độ sự kiện của khu vực, sắp startAt tăng dần (FACILITY_MANAGER, ADMIN)
 * GET /api/areas/{areaId}/event-schedules?status=
 * @param {string} areaId
 * @param {string} [status] SCHEDULED | STARTED | COMPLETED | ENDED_EARLY | CANCELLED | FAILED — bỏ trống để lấy tất cả
 */
export async function getEventSchedules(areaId, status) {
	const query = status ? `?status=${encodeURIComponent(status)}` : "";
	return apiGet(`/api/areas/${areaId}/event-schedules${query}`);
}

/**
 * Đặt lịch bật chế độ sự kiện trong tương lai (chỉ FACILITY_MANAGER)
 * POST /api/areas/{areaId}/event-schedules
 * @param {string} areaId
 * @param {Object} data { startAt, endAt, reasonCode, note }
 */
export async function createEventSchedule(areaId, data) {
	return apiPost(`/api/areas/${areaId}/event-schedules`, data);
}

/**
 * Sửa lịch chế độ sự kiện đang SCHEDULED (chỉ FACILITY_MANAGER)
 * PATCH /api/areas/{areaId}/event-schedules/{scheduleId}
 * @param {string} areaId
 * @param {string} scheduleId
 * @param {Object} data { startAt, endAt, reasonCode, note }
 */
export async function updateEventSchedule(areaId, scheduleId, data) {
	return apiPatch(`/api/areas/${areaId}/event-schedules/${scheduleId}`, data);
}

/**
 * Huỷ lịch chế độ sự kiện đang SCHEDULED (chỉ FACILITY_MANAGER)
 * POST /api/areas/{areaId}/event-schedules/{scheduleId}/cancel
 * @param {string} areaId
 * @param {string} scheduleId
 * @param {Object} data { reasonCode, note }
 */
export async function cancelEventSchedule(areaId, scheduleId, data) {
	return apiPost(`/api/areas/${areaId}/event-schedules/${scheduleId}/cancel`, data);
}

/**
 * Xem trước tác động khi ADMIN đổi loại khu vực (chỉ đọc, BR-TC-03)
 * GET /api/areas/{id}/type-change-preview?newAreaLevel=
 * @param {string} id
 * @param {string} newAreaLevel PUBLIC | INTERNAL_CONFIDENTIAL | CONFIDENTIAL_CONTACT_REQUIRED | HIGHLY_CONFIDENTIAL
 * @returns {Promise<Object>} AreaTypeChangePreviewResponse
 */
export async function getTypeChangePreview(id, newAreaLevel) {
	const query = new URLSearchParams({ newAreaLevel });
	return apiGet(`/api/areas/${id}/type-change-preview?${query.toString()}`);
}
