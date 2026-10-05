/**
 * Định dạng ngày giờ dùng chung (UI-14) — theo múi giờ trình duyệt.
 *
 * Thứ tự chuẩn "HH:mm dd/MM/yyyy" (giờ trước ngày) vì đã là định dạng của:
 * thông điệp backend (DateTimeFormatter "HH:mm dd/MM/yyyy" trong AreaService), guestHelpers.formatDateTime
 * và toLocaleString('vi-VN') đang dùng ở areaHelpers.formatDisplayDateTime / trang thông báo.
 *
 * Đầu vào: chuỗi ISO, Date, số ms; null/undefined/không hợp lệ -> "—" (không ném lỗi).
 *
 * Ca thử (frontend chưa có test runner — kiểm bằng node khi sửa file này; giờ theo máy chạy):
 *   formatDate(new Date(2026, 9, 5, 8, 3))        -> "05/10/2026"
 *   formatTime(new Date(2026, 9, 5, 8, 3))        -> "08:03"
 *   formatDateTime(new Date(2026, 9, 5, 8, 3))    -> "08:03 05/10/2026"
 *   formatRange(5/10 08:00, 5/10 11:30)           -> "05/10/2026 08:00 – 11:30"
 *   formatRange(5/10 22:00, 6/10 01:00)           -> "22:00 05/10/2026 – 01:00 06/10/2026"
 *   formatDateTime(null) / ("abc")                -> "—"
 *   formatRange(null, x)                          -> "—"
 */

const EMPTY = "—";

function toDate(value) {
	if (value === null || value === undefined || value === "") return null;
	const d = value instanceof Date ? value : new Date(value);
	return Number.isNaN(d.getTime()) ? null : d;
}

const pad = (n) => String(n).padStart(2, "0");

/** dd/MM/yyyy */
export function formatDate(value) {
	const d = toDate(value);
	if (!d) return EMPTY;
	return `${pad(d.getDate())}/${pad(d.getMonth() + 1)}/${d.getFullYear()}`;
}

/** HH:mm */
export function formatTime(value) {
	const d = toDate(value);
	if (!d) return EMPTY;
	return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** HH:mm dd/MM/yyyy */
export function formatDateTime(value) {
	const d = toDate(value);
	if (!d) return EMPTY;
	return `${formatTime(d)} ${formatDate(d)}`;
}

/** Cùng ngày: "dd/MM/yyyy HH:mm – HH:mm"; khác ngày: "HH:mm dd/MM/yyyy – HH:mm dd/MM/yyyy". */
export function formatRange(start, end) {
	const s = toDate(start);
	const e = toDate(end);
	if (!s || !e) return EMPTY;
	if (s.toDateString() === e.toDateString()) {
		return `${formatDate(s)} ${formatTime(s)} – ${formatTime(e)}`;
	}
	return `${formatDateTime(s)} – ${formatDateTime(e)}`;
}
