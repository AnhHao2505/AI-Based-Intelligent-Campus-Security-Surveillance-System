/**
 * Các tiện ích hình học và định dạng URL cho vùng giám sát (ROI)
 */

/**
 * Định dạng URL ảnh từ MinIO thành proxy URL nội bộ (/api/media/...)
 * để có thể xác thực qua Bearer Token nếu bucket là private.
 *
 * @param {string|null|undefined} url - URL gốc từ MinIO hoặc backend
 * @returns {string} URL đã chuẩn hóa
 */
export function formatImageUrl(url) {
	if (!url) return "";
	// Khi MinIO bucket là private, tự động định tuyến qua Backend Media Proxy (/api/media/...)
	const minioMatch = url.match(/^https?:\/\/[^/]+:9000\/([^/]+)\/(.+)$/);
	if (minioMatch) {
		const bucket = minioMatch[1];
		const path = minioMatch[2];
		return `/api/media/${bucket}/${path}`;
	}
	return url;
}

/**
 * Tính toán tọa độ trọng tâm (Centroid) của đa giác (tỷ lệ chuẩn hóa [0.0, 1.0])
 * để hiển thị nhãn tên vùng ở trung tâm đa giác.
 *
 * @param {Array<{x: number|string, y: number|string}>} vertices - Danh sách đỉnh đa giác
 * @returns {{x: number, y: number}} Tọa độ tâm
 */
export function computePolygonCentroid(vertices) {
	if (!vertices || vertices.length === 0) return { x: 0.5, y: 0.5 };
	let sumX = 0;
	let sumY = 0;
	vertices.forEach((v) => {
		sumX += Number(v.x) || 0;
		sumY += Number(v.y) || 0;
	});
	return {
		x: sumX / vertices.length,
		y: sumY / vertices.length,
	};
}
