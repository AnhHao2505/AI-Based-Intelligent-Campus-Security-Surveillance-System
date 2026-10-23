import { getAccessToken } from "./authService";

const API_BASE_URL =
	import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

function getHeaders() {
	const token = getAccessToken();
	return {
		"Content-Type": "application/json",
		...(token ? { Authorization: `Bearer ${token}` } : {}),
	};
}

async function request(url, options = {}) {
	const res = await fetch(`${API_BASE_URL}${url}`, {
		...options,
		headers: {
			...getHeaders(),
			...options.headers,
		},
	});

	if (!res.ok) {
		const error = await res
			.json()
			.catch(() => ({ message: "Yêu cầu thất bại" }));
		throw new Error(error.message || `Lỗi HTTP ${res.status}`);
	}

	if (res.status === 204) return null;
	const json = await res.json();
	if (
		json &&
		typeof json === "object" &&
		"httpCode" in json &&
		"message" in json
	) {
		if (json.data !== undefined && json.data !== null) {
			if (typeof json.data === "object" && !Array.isArray(json.data)) {
				json.data._message = json.message;
				json.data._httpCode = json.httpCode;
			}
			return json.data;
		}
		return { message: json.message, httpCode: json.httpCode, success: true };
	}
	return json;
}

/**
 * Lấy danh sách camera kèm phân trang và tìm kiếm/lọc
 */
export function fetchCameras({
	page = 0,
	size = 10,
	sort = "cameraCode,asc",
	search = "",
	status = "",
	operationalStatus = "",
}) {
	const params = new URLSearchParams({
		page: page.toString(),
		size: size.toString(),
	});
	if (sort) params.append("sort", sort);
	if (search) params.append("search", search);
	if (status) params.append("status", status);
	if (operationalStatus) params.append("operationalStatus", operationalStatus);

	return request(`/api/cameras?${params.toString()}`);
}

/**
 * Lấy chi tiết camera và toàn bộ cấu hình con
 */
export function fetchCameraDetail(id) {
	return request(`/api/cameras/${id}`);
}

/**
 * Tạo camera mới
 */
export function createCamera(data) {
	return request("/api/cameras", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

/**
 * Cập nhật thông tin cơ bản camera và toạ độ
 */
export function updateCamera(id, data) {
	return request(`/api/cameras/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

/**
 * Dừng hoạt động camera (decommission)
 */
export function decommissionCamera(id) {
	return request(`/api/cameras/${id}/decommission`, {
		method: "PATCH",
	});
}

/**
 * Kích hoạt lại camera
 */
export function reactivateCamera(id) {
	return request(`/api/cameras/${id}/reactivate`, {
		method: "PATCH",
	});
}

/**
 * Xóa mềm camera khỏi hệ thống
 */
export function deleteCamera(id) {
	return request(`/api/cameras/${id}`, {
		method: "DELETE",
	});
}

/**
 * Cập nhật/Tạo mới cấu hình stream camera
 */
export function upsertStreamConfig(id, data) {
	return request(`/api/cameras/${id}/stream-config`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

/**
 * Lấy danh sách gọn các camera đang hoạt động (dùng cho dropdown/gán khu vực)
 */
export function fetchAllSimpleCameras() {
	return request("/api/cameras/all-simple");
}

/**
 * Xem lịch sử health logs của camera
 */
export function fetchHealthLogs(id, { page = 0, size = 10 } = {}) {
	return request(`/api/cameras/${id}/health-logs?page=${page}&size=${size}`);
}

/**
 * Trích xuất 1 khung hình tĩnh (snapshot) từ camera (Pure Read-Only / Idempotent).
 * Dùng cho cấu hình vẽ ROI hoặc kiểm tra sai lệch góc (Drift Check).
 */
export function captureSnapshot(cameraId) {
	return request(`/api/cameras/${cameraId}/snapshot`, { method: "POST" });
}

/**
 * Kết nối Stream phát video trực tiếp (MediaMTX) và worker phân tích AI.
 * Cập nhật operationalStatus = ONLINE và ghi nhật ký hoạt động.
 */
export function startStream(cameraId) {
	return request(`/api/cameras/${cameraId}/stream/start`, { method: "POST" });
}

/**
 * Ngắt Stream phát video trực tiếp và dừng worker phân tích AI.
 * Cập nhật operationalStatus = OFFLINE và ghi nhật ký hoạt động.
 */
export function stopStream(cameraId) {
	return request(`/api/cameras/${cameraId}/stream/stop`, { method: "POST" });
}

/**
 * @deprecated Legacy endpoint kết nối RTSP stream. Khuyến nghị sử dụng startStream / stopStream và captureSnapshot.
 */
export function connectStream(cameraId) {
	return request(`/api/cameras/${cameraId}/connect`, { method: "POST" });
}

/**
 * Kiểm tra tín hiệu socket RTSP và đo độ trễ mạng (Read-Only, không làm thay đổi trạng thái camera).
 */
export function testConnection(cameraId, data) {
	return request(`/api/cameras/${cameraId}/test-connection`, {
		method: "POST",
		body: data ? JSON.stringify(data) : undefined,
	});
}

/**
 * Cập nhật ROI geometry cho camera kèm ảnh reference snapshot nếu có
 */
export function updateRoiGeometry(
	cameraId,
	roiGeometry,
	{ snapshotBase64, snapshotWidth, snapshotHeight } = {},
) {
	return request(`/api/cameras/${cameraId}/roi`, {
		method: "PUT",
		body: JSON.stringify({
			roiGeometry,
			snapshot_base64: snapshotBase64,
			snapshot_width: snapshotWidth,
			snapshot_height: snapshotHeight,
		}),
	});
}
