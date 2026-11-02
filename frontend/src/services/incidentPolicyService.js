import { apiGet, apiPut } from "../api/apiClient";

/**
 * Lấy luật mức độ sự cố (chỉ ADMIN)
 * GET /api/incident-severity-rules
 */
export async function getSeverityRules() {
	return apiGet("/api/incident-severity-rules");
}

/**
 * Sửa mức độ của một luật (chỉ ADMIN). data = { severity, version }
 * PUT /api/incident-severity-rules/{id}
 */
export async function updateSeverityRule(id, data) {
	return apiPut(`/api/incident-severity-rules/${id}`, data);
}

/**
 * Lấy chính sách phân phối sự cố theo mức (chỉ ADMIN)
 * GET /api/incident-dispatch-policies
 */
export async function getDispatchPolicies() {
	return apiGet("/api/incident-dispatch-policies");
}

/**
 * Sửa chính sách của một mức (chỉ ADMIN).
 * data = { step1Size, step1TimeoutSeconds, escalateFmMinutes, notifyFmImmediately, version }
 * PUT /api/incident-dispatch-policies/{severity}
 */
export async function updateDispatchPolicy(severity, data) {
	return apiPut(`/api/incident-dispatch-policies/${severity}`, data);
}

/**
 * Lấy toàn bộ danh mục vị trí trực (chỉ ADMIN)
 * GET /api/duty-post-types
 */
export async function getDutyPostTypes() {
	return apiGet("/api/duty-post-types");
}

/**
 * Lấy vị trí trực đang sử dụng cho dropdown xếp ca (FACILITY_MANAGER, ADMIN)
 * GET /api/duty-post-types/active
 */
export async function getActiveDutyPostTypes() {
	return apiGet("/api/duty-post-types/active");
}

/**
 * Sửa một vị trí trực (chỉ ADMIN). data = { label, dispatchable, isActive, sortOrder, version }
 * PUT /api/duty-post-types/{code}
 */
export async function updateDutyPostType(code, data) {
	return apiPut(`/api/duty-post-types/${encodeURIComponent(code)}`, data);
}
