import { useMemo, useReducer } from "react";
import guestVisitService from "../services/guestVisitService";
import {
	GUEST_FORM_LIMITS_INITIAL,
	createGuestFormLimitsLoader,
	guestFormLimitsReducer,
} from "../utils/guestHelpers";

/**
 * TC-F12-S07: giới hạn ảnh khách + phiên bản thông báo đồng ý cho một form khách, đọc lại
 * GET /api/guest-visits/eligibility mỗi lần gọi reload() (gọi khi mở form). Giống form tạo tài khoản đọc lại
 * USER_FACE_IMAGE_MAX_KB mỗi lần mở: Admin đổi GUEST_PHOTO_MAX_KB khi trang đang mở thì lần mở form sau dùng giá trị mới.
 *
 * @returns {[{ status: string, photoMaxKb: number|null, consentVersion: string|null }, () => Promise<object|null>]}
 *   [trạng thái, reload]; reload trả dữ liệu /eligibility mới (null nếu lỗi / đã có lần mở mới hơn).
 */
export function useGuestFormLimits() {
	const [limits, dispatch] = useReducer(guestFormLimitsReducer, GUEST_FORM_LIMITS_INITIAL);
	const reload = useMemo(
		() =>
			createGuestFormLimitsLoader(
				() =>
					guestVisitService.getEligibility().catch((err) => {
						console.error("Lỗi tải giới hạn ảnh khách:", err);
						throw err;
					}),
				dispatch,
			),
		[],
	);
	return [limits, reload];
}

export default useGuestFormLimits;
