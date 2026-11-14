/**
 * Ràng buộc chéo của chính sách phân phối sự cố (Lucas chốt 14/11/2026; trùng IncidentPolicyService.validateEscalationAfterStep1
 * và CHECK V124 ở backend, mã ERR_INCPOL_009).
 * Mức có đợt 1 (số bảo vệ mời > 0): thời gian báo Quản lý CSVC (phút × 60, tính từ lúc tạo sự cố) phải LỚN HƠN thời gian
 * chờ đợt 1 (giây) — không thì Quản lý CSVC bị báo trước khi cảnh báo được phát cho mọi bảo vệ.
 * Số bảo vệ mời = 0 (không có đợt 1) thì không kiểm. Giá trị không phải số để backend báo lỗi phạm vi.
 * @param {{step1Size: number|string, step1TimeoutSeconds: number|string, escalateFmMinutes: number|string}} policy
 * @returns {string|null} câu lỗi (cùng câu backend trả về) hoặc null khi hợp lệ
 */
export function escalationAfterStep1Error({ step1Size, step1TimeoutSeconds, escalateFmMinutes }) {
	const size = Number(step1Size);
	const timeout = Number(step1TimeoutSeconds);
	const minutes = Number(escalateFmMinutes);
	if (![size, timeout, minutes].every(Number.isFinite) || size === 0) return null;
	const seconds = minutes * 60;
	if (seconds > timeout) return null;
	return `Thời gian chờ trước khi báo Quản lý CSVC (${minutes} phút = ${seconds} giây) phải lớn hơn thời gian chờ đợt 1 (${timeout} giây)`;
}
