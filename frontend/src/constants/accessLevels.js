// BR-AL-33: cấp ban đầu Admin chọn khi tạo tay người dùng thường.
// Khớp UserAccessLevelHelper.NORMAL_USER_INITIAL_LEVELS ở backend (backend vẫn tự kiểm, trả ERR_AC_014 nếu sai).
export const NORMAL_USER_INITIAL_LEVELS = [1, 2];

/**
 * Cấp chọn sẵn trong form từ giá trị cấu hình ACCESS_LEVEL_DEFAULT_NORMAL_USER (chuỗi hoặc số).
 * Ngoài NORMAL_USER_INITIAL_LEVELS (vd dữ liệu cũ = 3) hoặc không đọc được -> cấp thấp nhất, giống backend
 * (V136: getIntWithinBounds trả mặc định của ConfigKey = 1 và ghi WARN). `invalid` để form báo cho Admin biết.
 * @param {string|number|null|undefined} configuredDefault
 * @returns {{ level: number, invalid: boolean, configured: number|null }}
 */
export const pickInitialLevel = (configuredDefault) => {
	const n =
		typeof configuredDefault === "number"
			? configuredDefault
			: Number.parseInt(configuredDefault, 10);
	const configured = Number.isInteger(n) ? n : null;
	if (configured !== null && NORMAL_USER_INITIAL_LEVELS.includes(configured)) {
		return { level: configured, invalid: false, configured };
	}
	return { level: NORMAL_USER_INITIAL_LEVELS[0], invalid: true, configured };
};

// Mô tả chỉ để hiển thị cạnh ô chọn cấp, không lưu DB. Quy tắc vào khu: cấp người dùng ≥ cấp khu
// (Công khai cấp 1, Bảo mật cấp 2, Bảo mật cao cấp 3).
const LEVEL_AUDIENCE = {
	1: "Sinh viên, người dùng phổ thông",
	2: "Giảng viên, nhân viên",
};

const LEVEL_AREAS = {
	1: "vào khu Công khai; khu cao hơn phải gửi yêu cầu truy cập",
	2: "vào khu Công khai và Bảo mật",
};

/**
 * Mô tả ngắn một cấp ban đầu. guestHostMinLevel lấy từ cấu hình GUEST_HOST_MIN_LEVEL;
 * null (chưa đọc được) thì không nói gì về quyền mời khách, không đoán con số.
 * @param {number} level
 * @param {number|null} guestHostMinLevel
 * @returns {string}
 */
export const describeInitialLevel = (level, guestHostMinLevel) => {
	const parts = [LEVEL_AUDIENCE[level], LEVEL_AREAS[level]].filter(Boolean);
	if (Number.isFinite(guestHostMinLevel)) {
		parts.push(level >= guestHostMinLevel ? "được mời khách" : "không mời khách");
	}
	return parts.join(" · ");
};
