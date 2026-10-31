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

export function fetchCameraDetail(id) {
	return request(`/api/cameras/${id}`);
}

export function createCamera(data) {
	return request("/api/cameras", {
		method: "POST",
		body: JSON.stringify(data),
	});
}

export function updateCamera(id, data) {
	return request(`/api/cameras/${id}`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export function decommissionCamera(id) {
	return request(`/api/cameras/${id}/decommission`, {
		method: "PATCH",
	});
}

export function reactivateCamera(id) {
	return request(`/api/cameras/${id}/reactivate`, {
		method: "PATCH",
	});
}

export function deleteCamera(id) {
	return request(`/api/cameras/${id}`, {
		method: "DELETE",
	});
}

export function upsertStreamConfig(id, data) {
	return request(`/api/cameras/${id}/stream-config`, {
		method: "PUT",
		body: JSON.stringify(data),
	});
}

export function fetchAllSimpleCameras() {
	return request("/api/cameras/all-simple");
}

export function fetchHealthLogs(id, { page = 0, size = 10 } = {}) {
	return request(`/api/cameras/${id}/health-logs?page=${page}&size=${size}`);
}

export function captureSnapshot(cameraId) {
	return request(`/api/cameras/${cameraId}/snapshot`, { method: "POST" });
}

export function startStream(cameraId) {
	return request(`/api/cameras/${cameraId}/stream/start`, { method: "POST" });
}

export function stopStream(cameraId) {
	return request(`/api/cameras/${cameraId}/stream/stop`, { method: "POST" });
}

/**
 * Yêu cầu bật/tắt On-Demand Cloud Stream Relay cho camera qua MQTT Control Plane.
 */
export function toggleCloudRelay(cameraCode, active = true) {
	const params = new URLSearchParams({
		cameraCode: cameraCode || "",
		active: String(Boolean(active)),
	});
	return request(`/api/cameras/stream/cloud-relay?${params.toString()}`, {
		method: "POST",
	});
}

export function connectStream(cameraId) {
	return request(`/api/cameras/${cameraId}/connect`, { method: "POST" });
}

export function testConnection(cameraId, data) {
	return request(`/api/cameras/${cameraId}/test-connection`, {
		method: "POST",
		body: data ? JSON.stringify(data) : undefined,
	});
}

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
