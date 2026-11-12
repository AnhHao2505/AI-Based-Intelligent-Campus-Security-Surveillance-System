import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import { getGuestPurposeLimits } from "../services/systemConfigService";

/**
 * Dự phòng khi chưa tải được giới hạn: trùng mặc định GUEST_PURPOSE_MIN_LENGTH / GUEST_PURPOSE_MAX_LENGTH (ConfigKey ở backend).
 * Backend luôn kiểm lại theo cấu hình hiện hành; giá trị này chỉ để form dùng được ngay khi vừa mở.
 */
const FALLBACK_LIMITS = { min: 10, max: 500 };

/**
 * N1 (V120): độ dài mục đích lượt khách (BR-GV-03), lấy từ GET /api/system-configurations/guest-purpose-limits
 * (NORMAL_USER, FACILITY_MANAGER — người mời khách). Độ dài tính sau khi trim, giống backend.
 * @returns {{ min: number, max: number, isValid: (value: string) => boolean }}
 */
export function useGuestPurposeLimits() {
	const { data } = useQuery({
		queryKey: ["system-configurations", "guest-purpose-limits"],
		queryFn: getGuestPurposeLimits,
		retry: false,
	});
	const min = data?.minLength ?? FALLBACK_LIMITS.min;
	const max = data?.maxLength ?? FALLBACK_LIMITS.max;

	return useMemo(() => {
		const isValid = (value) => {
			const length = (value || "").trim().length;
			return length >= min && length <= max;
		};
		return { min, max, isValid };
	}, [min, max]);
}

export default useGuestPurposeLimits;
