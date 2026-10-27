// Nhãn tiếng Việt dùng chung cho nhật ký kiểm toán (AuditLogPage, Dashboard "Hoạt động quản trị gần đây").

export const SYSTEM_ACTOR_LABELS = {
	EXPIRE_OVERDUE_REQUESTS_JOB: "Tự động hết hạn đơn quá giờ",
	EVENT_MODE_EXPIRY: "Tự động kết thúc sự kiện hết hạn",
	EVENT_SCHEDULE_ACTIVATION: "Tự động kích hoạt lịch sự kiện",
	GUEST_VISIT_JOB: "Tự động xử lý lượt khách",
};

export const renderSystemActor = (source) => {
	if (!source) return "Hệ thống";
	return SYSTEM_ACTOR_LABELS[source]
		? `Hệ thống – ${SYSTEM_ACTOR_LABELS[source]}`
		: `Hệ thống – ${source}`;
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
};

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

	if (targetType === "AREA_EVENT_SCHEDULE") {
		if (action === "CREATE") actionLabel = "Đặt lịch";
		else if (action === "UPDATE") actionLabel = "Sửa lịch";
		else if (action === "CANCEL") actionLabel = "Huỷ lịch";
		else if (action === "FAIL") actionLabel = "Lịch thất bại";
	}
	return actionLabel;
};
