import { ShieldCheck, ShieldAlert, Clock, UserX, AlertTriangle } from "lucide-react";

/**
 * 5 nhóm kết quả nhật ký ra vào (enum AccessResult của backend) — nhãn + icon dùng chung cho
 * tab nhật ký camera (CameraAccessLogsTab) và các trang lịch sử ra vào.
 * badge: variant của components/ui/Badge (màu lấy từ token theme.css). Theme chỉ có 4 họ màu trạng thái
 * nên "Ngoài giờ" dùng chung tông danger với "Sai quyền", phân biệt bằng nhãn và icon.
 */
export const ACCESS_RESULT_META = {
	AUTHORIZED_ACCESS: { label: "Hợp lệ", icon: ShieldCheck, badge: "success" },
	UNAUTHORIZED_ACCESS: { label: "Sai quyền", icon: ShieldAlert, badge: "danger" },
	ACCESS_OUT_OF_TIME: { label: "Sai giờ", icon: Clock, badge: "warning" },
	UNKNOWN_PERSON: { label: "Người lạ", icon: UserX, badge: "neutral" },
	INTRUSION: { label: "Ngoài giờ", icon: AlertTriangle, badge: "danger" },
};

/** Lựa chọn cho ô lọc kết quả ("" = tất cả). */
export const ACCESS_RESULT_OPTIONS = [
	{ value: "", label: "Tất cả kết quả" },
	...Object.entries(ACCESS_RESULT_META).map(([value, meta]) => ({ value, label: meta.label })),
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
