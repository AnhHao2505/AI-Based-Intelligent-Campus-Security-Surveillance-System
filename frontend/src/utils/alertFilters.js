/**
 * Tiện ích lọc danh sách sự cố an ninh theo khoảng ngày (Từ ngày -> Đến ngày)
 * dựa trên múi giờ địa phương của trình duyệt.
 */

const ISO_DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * Chuyển chuỗi ngày "YYYY-MM-DD" thành mốc thời gian (ms) theo giờ địa phương:
 * - boundary = "start": 00:00:00.000 của ngày đó
 * - boundary = "end":   23:59:59.999 của ngày đó
 * Trả về null nếu chuỗi rỗng hoặc không hợp lệ.
 *
 * @param {string|null|undefined} dateStr
 * @param {"start"|"end"} [boundary="start"]
 * @returns {number|null}
 */
export function parseLocalDateBoundaryMs(dateStr, boundary = "start") {
	if (typeof dateStr !== "string") return null;
	const match = ISO_DATE_RE.exec(dateStr.trim());
	if (!match) return null;

	const year = Number(match[1]);
	const month = Number(match[2]);
	const day = Number(match[3]);

	const d =
		boundary === "end"
			? new Date(year, month - 1, day, 23, 59, 59, 999)
			: new Date(year, month - 1, day, 0, 0, 0, 0);

	// Kiểm tra ngày có tồn tại thật trên lịch (tránh JS tự tràn tháng, vd 2026-02-31)
	if (
		Number.isNaN(d.getTime()) ||
		d.getFullYear() !== year ||
		d.getMonth() !== month - 1 ||
		d.getDate() !== day
	) {
		return null;
	}

	return d.getTime();
}

/**
 * Lấy timestamp (epoch ms) của một sự cố.
 *
 * @param {Record<string, any>} alert
 * @returns {number|null}
 */
export function getAlertTimestampMs(alert) {
	if (!alert) return null;
	if (typeof alert.rawTime === "number" && Number.isFinite(alert.rawTime) && alert.rawTime > 0) {
		return alert.rawTime;
	}
	const rawIso =
		alert.detectedAt || alert.detected_at || alert.createdAt || alert.created_at;
	if (!rawIso) return null;
	const parsed = new Date(rawIso).getTime();
	return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

/**
 * Lọc danh sách sự cố theo khoảng [fromDate, toDate] (bao gồm cả 2 ngày đầu và cuối).
 *
 * @template T
 * @param {T[]} alerts
 * @param {string} fromDate - Chuỗi YYYY-MM-DD hoặc rỗng
 * @param {string} toDate - Chuỗi YYYY-MM-DD hoặc rỗng
 * @returns {T[]}
 */
export function filterAlertsByDateRange(alerts, fromDate, toDate) {
	if (!Array.isArray(alerts)) return [];
	const fromMs = parseLocalDateBoundaryMs(fromDate, "start");
	const toMs = parseLocalDateBoundaryMs(toDate, "end");

	if (fromMs === null && toMs === null) {
		return alerts;
	}

	return alerts.filter((alert) => {
		const ts = getAlertTimestampMs(alert);
		if (ts === null) return false;
		if (fromMs !== null && ts < fromMs) return false;
		if (toMs !== null && ts > toMs) return false;
		return true;
	});
}

/**
 * Định dạng chuỗi mô tả ngắn cho khoảng ngày đang lọc (dùng cho thông báo trạng thái rỗng).
 *
 * @param {string} fromDate
 * @param {string} toDate
 * @returns {string}
 */
export function formatDateFilterSummary(fromDate, toDate) {
	const fmt = (d) => {
		const match = ISO_DATE_RE.exec((d || "").trim());
		return match ? `${match[3]}/${match[2]}/${match[1]}` : d;
	};
	if (fromDate && toDate) {
		return fromDate === toDate
			? `trong ngày ${fmt(fromDate)}`
			: `từ ${fmt(fromDate)} đến ${fmt(toDate)}`;
	}
	if (fromDate) return `từ ngày ${fmt(fromDate)}`;
	if (toDate) return `đến ngày ${fmt(toDate)}`;
	return "";
}
