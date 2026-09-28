import { apiGet, apiPost, apiPut, apiPatch } from "../api/apiClient";

/**
 * Lấy danh sách danh mục lý do (chỉ ADMIN)
 * GET /api/reason-catalogs?actionType=&isActive=
 */
export async function getReasonCatalogs(params = {}) {
	const searchParams = new URLSearchParams();
	if (params.actionType) searchParams.append("actionType", params.actionType);
	if (params.isActive !== undefined && params.isActive !== null && params.isActive !== "") {
		searchParams.append("isActive", params.isActive);
	}
	const qs = searchParams.toString();
	return apiGet(`/api/reason-catalogs${qs ? `?${qs}` : ""}`);
}

/**
 * Lấy danh sách lý do đang hoạt động theo actionType (FACILITY_MANAGER, ADMIN)
 * GET /api/reason-catalogs/active?actionType=EVENT_ENABLE
 */
export async function getActiveReasons(actionType) {
	return apiGet(`/api/reason-catalogs/active?actionType=${encodeURIComponent(actionType)}`);
}

/**
 * Tạo mới lý do (chỉ ADMIN)
 * POST /api/reason-catalogs
 */
export async function createReasonCatalog(data) {
	return apiPost("/api/reason-catalogs", data);
}

/**
 * Sửa nhãn và thứ tự lý do (chỉ ADMIN)
 * PUT /api/reason-catalogs/{id}
 */
export async function updateReasonCatalog(id, data) {
	return apiPut(`/api/reason-catalogs/${id}`, data);
}

/**
 * Ngừng dùng lý do (chỉ ADMIN)
 * PATCH /api/reason-catalogs/{id}/deactivate
 */
export async function deactivateReasonCatalog(id) {
	return apiPatch(`/api/reason-catalogs/${id}/deactivate`);
}

/**
 * Dùng lại lý do (chỉ ADMIN)
 * PATCH /api/reason-catalogs/{id}/reactivate
 */
export async function reactivateReasonCatalog(id) {
	return apiPatch(`/api/reason-catalogs/${id}/reactivate`);
}
