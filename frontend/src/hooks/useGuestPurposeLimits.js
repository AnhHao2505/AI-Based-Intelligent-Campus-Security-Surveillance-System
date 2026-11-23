import { useMemo, useReducer } from "react";
import { getGuestPurposeLimits } from "../services/systemConfigService";
import {
	GUEST_PURPOSE_LIMITS_INITIAL,
	createGuestPurposeLimitsLoader,
	guestPurposeLimitsReducer,
} from "../utils/guestPurposeLimits";

/**
 * N1 (V120): độ dài mục đích lượt khách (BR-GV-03), lấy từ GET /api/system-configurations/guest-purpose-limits
 * (NORMAL_USER, FACILITY_MANAGER — người mời khách).
 * Đọc lại mỗi lần gọi reload() (gọi khi mở form "Tạo lượt khách"): Admin đổi GUEST_PURPOSE_MIN_LENGTH /
 * GUEST_PURPOSE_MAX_LENGTH khi trang đang mở thì lần mở form sau dùng giá trị mới. Không còn số dự phòng 10/500:
 * chưa đọc được thì form không chặn độ dài, backend kiểm (xem guestPurposeState).
 *
 * @returns {[{ status: string, min: number|null, max: number|null }, () => Promise<void>]} [giới hạn, reload]
 */
export function useGuestPurposeLimits() {
	const [limits, dispatch] = useReducer(guestPurposeLimitsReducer, GUEST_PURPOSE_LIMITS_INITIAL);
	const reload = useMemo(
		() =>
			createGuestPurposeLimitsLoader(
				() =>
					getGuestPurposeLimits().catch((err) => {
						console.error("Lỗi tải giới hạn độ dài mục đích lượt khách:", err);
						throw err;
					}),
				dispatch,
			),
		[],
	);
	return [limits, reload];
}

export default useGuestPurposeLimits;
