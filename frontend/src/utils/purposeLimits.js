/**
 * Việc 7: ô "Mục đích sử dụng khu vực" của đơn truy cập dùng chung giới hạn với ô lý do
 * (REASON_MIN_LENGTH / REASON_MAX_LENGTH, đọc qua useReasonLimits). Độ dài tính sau khi trim, giống backend.
 * Chưa đọc được giới hạn (limits.loaded = false) thì KHÔNG chặn ở trình duyệt: không maxLength, không báo lỗi độ dài,
 * để backend kiểm theo cấu hình hiện hành.
 *
 * @param {string} value nội dung ô mục đích
 * @param {{ min: number, max: number, loaded: boolean }} limits kết quả useReasonLimits()
 * @returns {{
 *   enforced: boolean,
 *   maxLength: (number|undefined),
 *   error: (string|null),
 *   hint: string,
 *   counter: string
 * }}
 */
export function purposeLimitState(value, limits) {
	const raw = value || "";
	const length = raw.trim().length;
	if (!limits?.loaded) {
		return {
			enforced: false,
			maxLength: undefined,
			error: null,
			hint: "Bắt buộc",
			counter: `${raw.length} ký tự`,
		};
	}
	const { min, max } = limits;
	const outOfRange = length > 0 && (length < min || length > max);
	return {
		enforced: true,
		maxLength: max,
		error: outOfRange ? `Mục đích phải từ ${min} đến ${max} ký tự (hiện có ${length}).` : null,
		hint: `Bắt buộc, từ ${min} đến ${max} ký tự`,
		counter: `${raw.length}/${max} ký tự`,
	};
}
