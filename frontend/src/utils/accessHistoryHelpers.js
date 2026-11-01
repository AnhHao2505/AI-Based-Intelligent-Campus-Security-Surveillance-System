import { ShieldCheck, ShieldAlert, Clock, UserX, AlertTriangle } from "lucide-react";

/**
 * 5 nhóm kết quả nhật ký ra vào (enum AccessResult của backend) — nhãn + icon dùng chung cho
 * tab nhật ký camera (CameraAccessLogsTab) và các trang lịch sử ra vào.
 */
export const ACCESS_RESULT_META = {
	// 5 Tag Chuẩn hóa toàn hệ thống
	AUTHORIZED_PERSON: { label: "Hợp lệ", icon: ShieldCheck, badge: "success" },
	UNAUTHORIZED_PERSON: { label: "Không có quyền", icon: ShieldAlert, badge: "danger" },
	UNKNOWN_PERSON: { label: "Người lạ", icon: UserX, badge: "neutral" },
	PERSON_INTRUSION: { label: "Ngoài giờ", icon: AlertTriangle, badge: "danger" },
	SUSPICIOUS_MOVING_OBJECT: { label: "Chuyển động nghi vấn", icon: AlertTriangle, badge: "warning" },

	// Legacy mappings for backwards compatibility
	AUTHORIZED_ACCESS: { label: "Hợp lệ", icon: ShieldCheck, badge: "success" },
	UNAUTHORIZED_ACCESS: { label: "Không đủ thẩm quyền", icon: ShieldAlert, badge: "danger" },
	ACCESS_OUT_OF_TIME: { label: "Sai giờ", icon: Clock, badge: "warning" },
	INTRUSION: { label: "Ngoài giờ", icon: AlertTriangle, badge: "danger" },
};

/**
 * Rút gọn lý do từ chối / cảnh báo thành cụm từ tối giản (Minimal Reason).
 */
export function minimizeReason(reason) {
	if (!reason) return "Không có quyền";
	const r = String(reason).toLowerCase();

	if (r.includes("cấp độ") || r.includes("level")) return "Cấp độ không đủ";
	if (r.includes("chưa tới") && (r.includes("đơn") || r.includes("giờ") || r.includes("khung"))) return "Chưa tới giờ duyệt";
	if (r.includes("hết hạn") && (r.includes("đơn") || r.includes("lượt") || r.includes("sử dụng"))) return "Hết hạn đơn duyệt";
	if (r.includes("phân công") && (r.includes("chưa tới") || r.includes("chưa đến"))) return "Chưa tới ngày trực";
	if (r.includes("phân công") && r.includes("hết hạn")) return "Hết hạn phân công";
	if (r.includes("vô hiệu") || r.includes("khoá") || r.includes("khóa")) return "Tài khoản bị khóa";
	if (r.includes("ngừng hoạt động") || r.includes("tạm dừng") || r.includes("tạm đóng")) return "Khu vực tạm đóng";
	if (r.includes("ngoài giờ") || r.includes("đột nhập") || r.includes("after_hours")) return "Ngoài giờ hoạt động";
	if (r.includes("khách")) return "Ngoài giờ tiếp khách";
	if (r.includes("không tồn tại") || r.includes("chưa đăng ký")) return "Chưa đăng ký";

	return "Không có quyền";
}

/**
 * Mã giữ chỗ backend ghi vào userCode khi lượt ra vào không gắn được với tài khoản nào.
 * Đây không phải mã người dùng nên hiển thị bằng nhãn, không hiển thị như mã.
 */
export const PLACEHOLDER_USER_CODE_LABELS = {
	UNKNOWN: "Người lạ",
};

/** Nhãn của mã giữ chỗ (UNKNOWN, VIOLATION), null nếu là mã người dùng thật. */
export function placeholderUserCodeLabel(userCode) {
	if (typeof userCode !== "string") return null;
	return PLACEHOLDER_USER_CODE_LABELS[userCode.trim().toUpperCase()] || null;
}

/** 5 kết quả chuẩn dùng cho bộ lọc */
export const CANONICAL_ACCESS_RESULTS = [
	"AUTHORIZED_PERSON",
	"UNAUTHORIZED_PERSON",
	"UNKNOWN_PERSON",
	"PERSON_INTRUSION",
	"SUSPICIOUS_MOVING_OBJECT",
];

/** Lựa chọn cho ô lọc kết quả ("" = tất cả). */
export const ACCESS_RESULT_OPTIONS = [
	{ value: "", label: "Tất cả kết quả" },
	...CANONICAL_ACCESS_RESULTS.map((key) => ({
		value: key,
		label: ACCESS_RESULT_META[key]?.label || key,
	})),
];

const pad = (n) => String(n).padStart(2, "0");

/** Date → "yyyy-MM-dd" theo giờ máy (giá trị cho input type="date"). */
export function toDateInputValue(date) {
	return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** Khoảng mặc định: 7 ngày gần nhất (6 ngày trước → hôm nay), khớp mặc định của backend. */
export function defaultDateRange() {
	const to = new Date();
	const from = new Date();
	from.setDate(from.getDate() - 6);
	return { from: toDateInputValue(from), to: toDateInputValue(to) };
}

/**
 * Ngày lọc là ngày theo giờ Việt Nam (UTC+7), giống AuditLogPage: from = đầu ngày, to = cuối ngày.
 * Trả về {} cho ô bỏ trống để backend dùng mặc định.
 */
export function toRangeParams(fromDate, toDate) {
	const params = {};
	if (fromDate) params.from = `${fromDate}T00:00:00+07:00`;
	if (toDate) params.to = `${toDate}T23:59:59+07:00`;
	return params;
}

/** Chuỗi lỗi khi "Từ ngày" sau "Đến ngày" (so sánh chuỗi yyyy-MM-dd), null nếu hợp lệ. */
export function dateRangeError(fromDate, toDate) {
	if (fromDate && toDate && fromDate > toDate) {
		return "Từ ngày phải trước hoặc bằng Đến ngày";
	}
	return null;
}
