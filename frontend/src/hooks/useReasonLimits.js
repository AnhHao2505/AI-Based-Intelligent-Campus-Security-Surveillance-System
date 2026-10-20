import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { getReasonLimits } from "../services/systemConfigService";

/**
 * Dự phòng khi chưa tải được giới hạn: trùng mặc định REASON_MIN_LENGTH / REASON_MAX_LENGTH (ConfigKey ở backend).
 * Backend luôn kiểm lại theo cấu hình hiện hành; giá trị này chỉ để form dùng được ngay khi vừa mở.
 */
const FALLBACK_LIMITS = { min: 10, max: 500 };

/**
 * Checklist 6.1: giới hạn độ dài lý do cho mọi form nhập lý do, lấy từ GET /api/system-configurations/reason-limits
 * (mọi tài khoản đăng nhập đọc được). Độ dài tính sau khi trim, giống backend.
 * @returns {{
 *   min: number,
 *   max: number,
 *   range: string,
 *   isValid: (value: string) => boolean,
 *   error: (value: string, subject?: string) => (string|null)
 * }}
 */
export function useReasonLimits() {
	const { data } = useQuery({
		queryKey: ["system-configurations", "reason-limits"],
		queryFn: getReasonLimits,
		retry: false,
	});
	const min = data?.minLength ?? FALLBACK_LIMITS.min;
	const max = data?.maxLength ?? FALLBACK_LIMITS.max;

	return useMemo(() => {
		const lengthOf = (value) => (value || "").trim().length;
		const isValid = (value) => {
			const length = lengthOf(value);
			return length >= min && length <= max;
		};
		return {
			min,
			max,
			/** "10–500", dùng trong placeholder */
			range: `${min}–${max}`,
			isValid,
			/** Câu lỗi khi sai độ dài, null nếu hợp lệ: "<subject> phải từ <min> đến <max> ký tự (hiện có <n>)." */
			error: (value, subject = "Lý do") =>
				isValid(value) ? null : `${subject} phải từ ${min} đến ${max} ký tự (hiện có ${lengthOf(value)}).`,
		};
	}, [min, max]);
}

export default useReasonLimits;
