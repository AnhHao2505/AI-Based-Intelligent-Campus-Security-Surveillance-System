// Nhãn tiếng Việt dùng chung cho nhật ký kiểm toán (AuditLogPage, Dashboard "Hoạt động quản trị gần đây").

export const SYSTEM_ACTOR_LABELS = {
	EXPIRE_OVERDUE_REQUESTS_JOB: "Tự động hết hạn đơn quá giờ",
	EVENT_MODE_EXPIRY: "Tự động kết thúc sự kiện hết hạn",
	EVENT_SCHEDULE_ACTIVATION: "Tự động kích hoạt lịch sự kiện",
	GUEST_VISIT_JOB: "Tự động xử lý lượt khách",
	FINISH_ELAPSED_REQUESTS_JOB: "Tự động kết thúc đơn hết giờ",
	AREA_TYPE_CHANGE: "Đổi loại khu vực",
	AREA_DEACTIVATION: "Vô hiệu hoá khu vực",
	REQUESTER_DEACTIVATION: "Vô hiệu hoá tài khoản người tạo đơn",
	// PR-AUDIT (V112)
	AUTH_LOGIN: "Đăng nhập",
	AUTH_PASSWORD_RESET: "Đặt lại mật khẩu",
	ACCOUNT_SERVICE: "Quản lý tài khoản",
	USER_BULK_IMPORT: "Nạp tài khoản theo lô",
	FACE_REGISTRATION: "Đăng ký khuôn mặt",
};

// Dòng do migration Flyway ghi (vd FLYWAY_MIGRATION_V83) -> "Nâng cấp dữ liệu V83"
const MIGRATION_SOURCE = /^FLYWAY_MIGRATION_(V[\d_]+)$/;

export const renderSystemActor = (source) => {
	if (!source) return "Hệ thống";
	if (SYSTEM_ACTOR_LABELS[source]) return `Hệ thống – ${SYSTEM_ACTOR_LABELS[source]}`;
	const migration = MIGRATION_SOURCE.exec(source);
	if (migration) return `Hệ thống – Nâng cấp dữ liệu ${migration[1].replace(/_/g, ".")}`;
	return `Hệ thống – ${source}`;
};

export const TARGET_TYPE_LABELS = {
	USER_ACCESS_LEVEL: "Cấp người dùng",
	AREA_ACCESS_RULES: "Quy tắc khu vực",
	AREA_ASSIGNMENT: "Nhân sự chỉ định",
	LEVEL_PRESET: "Mặc định loại",
	AREA_EVENT_MODE: "Chế độ sự kiện",
	AREA_EVENT_SCHEDULE: "Lịch sự kiện",
	REASON_CATALOG: "Danh mục lý do",
	AREA: "Khu vực",
	AREA_GEOMETRY: "Tọa độ khu vực",
	AREA_CAMERAS: "Camera khu vực",
	ACCESS_REQUEST: "Yêu cầu truy cập",
	SYSTEM_CONFIG: "Cấu hình hệ thống",
	SYSTEM: "Hệ thống",
	GUEST_VISIT: "Lượt khách",
	GUEST: "Khách",
	FACE_PROFILE: "Ảnh khuôn mặt",
	SECURITY_EVIDENCE: "Ảnh bằng chứng sự cố",
	// PR-AUDIT (V112)
	USER_ACCOUNT: "Tài khoản",
	USER_IMPORT_BATCH: "Lô tài khoản",
	FACE_REGISTRATION: "Khuôn mặt",
	AUTH: "Đăng nhập",
};

// PR-AUDIT: lý do nội bộ của LOGIN_FAILED (chỉ ADMIN xem; người đăng nhập luôn nhận câu chung BR-AU-32)
export const LOGIN_FAILURE_REASON_LABELS = {
	UNKNOWN_ACCOUNT: "E-mail không có tài khoản",
	BAD_PASSWORD: "Sai mật khẩu",
	DEACTIVATED: "Tài khoản đã vô hiệu hoá",
	DELETED: "Tài khoản đã xoá",
	LOCKED: "Đang bị khoá tạm",
	GOOGLE_NOT_REGISTERED: "Google chưa đăng ký",
	GOOGLE_TOKEN_INVALID: "Xác minh Google thất bại",
};

// PR-AUDIT: nhóm của tab "Nhật ký" (màn Quản lý tài khoản) -> module audit + hành động thuộc nhóm
export const ACCOUNT_LOG_GROUPS = [
	{
		module: "ACCOUNT",
		label: "Tài khoản",
		actions: ["CREATE", "IMPORT", "REGISTER_FACE", "DEACTIVATE", "REACTIVATE", "DELETE", "RESTORE"],
	},
	{
		module: "AUTH",
		label: "Đăng nhập",
		actions: [
			"LOGIN_SUCCESS",
			"LOGIN_FAILED",
			"ACCOUNT_LOCKED",
			"PASSWORD_RESET_REQUESTED",
			"PASSWORD_RESET_COMPLETED",
		],
	},
	{ module: "SYSTEM_CONFIG", label: "Cấu hình", actions: ["UPDATE"] },
];

export const getActionLabel = (targetType, action) => {
	let actionLabel = action;
	if (action === "UPDATE") actionLabel = "Cập nhật";
	else if (action === "ASSIGN") actionLabel = "Gán mới";
	else if (action === "UPDATE_VALIDITY") actionLabel = "Gia hạn";
	else if (action === "REVOKE") actionLabel = "Thu hồi";
	else if (action === "ENABLE_EVENT_MODE") actionLabel = "Bật chế độ sự kiện";
	else if (action === "DISABLE_EVENT_MODE") actionLabel = "Tắt chế độ sự kiện";
	else if (action === "EXTEND_EVENT_MODE")
		actionLabel = "Điều chỉnh giờ kết thúc";
	else if (action === "CREATE") actionLabel = "Tạo mới";
	else if (action === "DEACTIVATE") actionLabel = "Ngừng dùng";
	else if (action === "REACTIVATE") actionLabel = "Dùng lại";
	else if (action === "UPDATE_RULES") actionLabel = "Cập nhật quy tắc";
	else if (action === "UPDATE_PRESET") actionLabel = "Cập nhật mặc định";
	else if (action === "UPDATE_GEOMETRY") actionLabel = "Cập nhật tọa độ";
	else if (action === "DELETE_GEOMETRY") actionLabel = "Xóa tọa độ";
	else if (action === "UPDATE_CAMERAS") actionLabel = "Cập nhật camera";
	else if (action === "SUBMIT") actionLabel = "Gửi yêu cầu";
	else if (action === "APPROVE") actionLabel = "Phê duyệt";
	else if (action === "REJECT") actionLabel = "Từ chối";
	else if (action === "CANCEL") actionLabel = "Huỷ bỏ";
	else if (action === "FINISH") actionLabel = "Kết thúc";
	else if (action === "EXPIRE") actionLabel = "Hết hạn";
	else if (action === "AUTO_EXPIRE") actionLabel = "Tự động hết hạn";
	else if (action === "EXPIRE_EVENT_MODE") actionLabel = "Sự kiện hết hạn";
	else if (action === "CHANGE_TYPE") actionLabel = "Đổi loại khu vực";
	else if (action === "COMPLETE") actionLabel = "Hoàn thành";
	else if (action === "ATTACH_PHOTO") actionLabel = "Đính kèm ảnh";
	else if (action === "VIEW_PHOTO") actionLabel = "Xem ảnh";
	else if (action === "DELETE_BIOMETRIC") actionLabel = "Xóa dữ liệu sinh trắc";
	else if (action === "ANONYMIZE") actionLabel = "Ẩn danh hóa";
	else if (action === "FAIL") actionLabel = "Thất bại";
	else if (action === "RESTORE") actionLabel = "Khôi phục";
	// PR-AUDIT (V112)
	else if (action === "DELETE") actionLabel = "Xoá";
	else if (action === "IMPORT") actionLabel = "Nạp theo lô";
	else if (action === "REGISTER_FACE") actionLabel = "Đăng ký khuôn mặt";
	else if (action === "LOGIN_SUCCESS") actionLabel = "Đăng nhập thành công";
	else if (action === "LOGIN_FAILED") actionLabel = "Đăng nhập thất bại";
	else if (action === "ACCOUNT_LOCKED") actionLabel = "Khoá đăng nhập tạm thời";
	else if (action === "PASSWORD_RESET_REQUESTED") actionLabel = "Yêu cầu đặt lại mật khẩu";
	else if (action === "PASSWORD_RESET_COMPLETED") actionLabel = "Đặt lại mật khẩu";

	if (targetType === "USER_ACCOUNT") {
		if (action === "CREATE") actionLabel = "Tạo tài khoản";
		else if (action === "DEACTIVATE") actionLabel = "Vô hiệu hoá";
		else if (action === "REACTIVATE") actionLabel = "Kích hoạt lại";
		else if (action === "DELETE") actionLabel = "Xoá tài khoản";
	} else if (targetType === "USER_IMPORT_BATCH") {
		if (action === "DELETE") actionLabel = "Gỡ lô";
		else if (action === "RESTORE") actionLabel = "Khôi phục lô";
	} else if (targetType === "SYSTEM_CONFIG" && action === "UPDATE") {
		actionLabel = "Sửa cấu hình";
	}

	if (targetType === "AREA_EVENT_SCHEDULE") {
		if (action === "CREATE") actionLabel = "Đặt lịch";
		else if (action === "UPDATE") actionLabel = "Sửa lịch";
		else if (action === "CANCEL") actionLabel = "Huỷ lịch";
		else if (action === "FAIL") actionLabel = "Lịch thất bại";
	}
	return actionLabel;
};
