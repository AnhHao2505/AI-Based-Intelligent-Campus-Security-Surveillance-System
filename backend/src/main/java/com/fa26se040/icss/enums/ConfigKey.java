package com.fa26se040.icss.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ConfigKey {

    ACCESS_REQUEST_MAX_GROUP_MEMBERS("ACCESS_REQUEST_MAX_GROUP_MEMBERS", "30"),
    ACCESS_REQUEST_MAX_DURATION_HOURS("ACCESS_REQUEST_MAX_DURATION_HOURS", "12"),
    ACCESS_REQUEST_MAX_ADVANCE_DAYS("ACCESS_REQUEST_MAX_ADVANCE_DAYS", "30"),
    ACCESS_REQUEST_PAST_START_BUFFER_MINUTES("ACCESS_REQUEST_PAST_START_BUFFER_MINUTES", "5"),
    ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE("ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE", "false"),
    ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES("ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES", "CONFIDENTIAL"),
    // V83: đơn cá nhân chỉ xin khu có cấp cao hơn cấp người tạo tối đa N cấp (1–2)
    ACCESS_REQUEST_MAX_LEVEL_GAP("ACCESS_REQUEST_MAX_LEVEL_GAP", "1"),
    NOTIFICATION_PENDING_OVERDUE_HOURS("NOTIFICATION_PENDING_OVERDUE_HOURS", "24"),
    NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES("NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES", "30"),
    // V113: chu kỳ quét 2 job thông báo (NotificationScanScheduler đọc mỗi nhịp 1 phút)
    NOTIFICATION_STARTING_SOON_SCAN_MINUTES("NOTIFICATION_STARTING_SOON_SCAN_MINUTES", "5"),
    NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES("NOTIFICATION_PENDING_OVERDUE_SCAN_MINUTES", "60"),
    SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE("SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE", "20"),
    ACCESS_LEVEL_DEFAULT_NORMAL_USER("ACCESS_LEVEL_DEFAULT_NORMAL_USER", "1"),
    ACCESS_LEVEL_DEFAULT_GUARD("ACCESS_LEVEL_DEFAULT_GUARD", "2"),
    // V83 (BR-AL-24): FM và ADMIN tạo mới mặc định cấp 3; tài khoản đang có không đổi
    ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER("ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER", "3"),
    ACCESS_LEVEL_DEFAULT_ADMIN("ACCESS_LEVEL_DEFAULT_ADMIN", "3"),
    // V125: mặc định 0.50 theo đánh giá trên camera thật (0.75 khiến người đã đăng ký gần như không bao giờ được nhận ra)
    AI_FACE_MATCH_THRESHOLD("AI_FACE_MATCH_THRESHOLD", "0.50"),
    // V145: ngưỡng độ tin cậy phát hiện người YOLO (trước đây cố định 0.35 trong ai-service)
    AI_DETECTION_CONFIDENCE("AI_DETECTION_CONFIDENCE", "0.35"),
    // V146: ngưỡng độ tin cậy phát hiện khuôn mặt SCRFD (trước đây cố định 0.45 trong ai-service)
    AI_FACE_CONFIDENCE("AI_FACE_CONFIDENCE", "0.45"),
    AI_AFTER_HOUR_START("AI_AFTER_HOUR_START", "22:00"),
    AI_AFTER_HOUR_END("AI_AFTER_HOUR_END", "06:00"),
    // V142: bỏ khỏi màn Cấu hình AI_TRACK_MAX_INACTIVE_SECONDS, AI_ACCESS_CHECK_TIMEOUT_SECONDS,
    // AI_ACCESS_DECISION_CACHE_TTL, AI_ACCESS_DENY_CACHE_TTL, AI_IDENTITY_GRACE_SECONDS, AI_IDENTITY_HOLD_SECONDS,
    // AI_CAMERA_UNKNOWN_MIN_INTERVAL_SECONDS (tham số kỹ thuật, AI đọc từ .env)
    AI_UNKNOWN_ALERT_COOLDOWN_SECONDS("AI_UNKNOWN_ALERT_COOLDOWN_SECONDS", "30"),
    // V125: chính sách cảnh báo của AI, trước đây chỉ nằm trong .env / code
    AI_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS("AI_UNAUTHORIZED_ALERT_COOLDOWN_SECONDS", "30"),
    AI_SUSPICIOUS_MOTION_COOLDOWN_SECONDS("AI_SUSPICIOUS_MOTION_COOLDOWN_SECONDS", "5"),
    // V115 (EV-N1, EV-N2): bỏ EVENT_MODE_MAX_HOURS, EVENT_MODE_WINDOW_DAYS, EVENT_MODE_BUDGET_HOURS,
    // EVENT_MODE_MAX_SCHEDULES_PER_AREA (không còn trần giờ / ngân sách / số lịch chờ)
    EVENT_MODE_MIN_MINUTES("EVENT_MODE_MIN_MINUTES", "15"),
    EVENT_MODE_EXPIRY_REMINDER_MINUTES("EVENT_MODE_EXPIRY_REMINDER_MINUTES", "30"),
    EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS("EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS", "30"),
    EVENT_MODE_SCHEDULE_REMINDER_MINUTES("EVENT_MODE_SCHEDULE_REMINDER_MINUTES", "30"),
    // Khách G-A (V60)
    GUEST_HOST_MIN_LEVEL("GUEST_HOST_MIN_LEVEL", "2"),
    GUEST_MAX_PER_VISIT("GUEST_MAX_PER_VISIT", "10"),
    GUEST_VISIT_MAX_HOURS("GUEST_VISIT_MAX_HOURS", "8"),
    GUEST_MAX_ADVANCE_DAYS("GUEST_MAX_ADVANCE_DAYS", "14"),
    GUEST_FACE_RETENTION_HOURS("GUEST_FACE_RETENTION_HOURS", "72"),
    GUEST_RECORD_RETENTION_DAYS("GUEST_RECORD_RETENTION_DAYS", "90"),
    GUEST_PHOTO_URL_TTL_SECONDS("GUEST_PHOTO_URL_TTL_SECONDS", "60"),
    GUEST_CONSENT_NOTICE_VERSION("GUEST_CONSENT_NOTICE_VERSION", "v1"),
    // BR-GV-39 (V71): nhắc ADMIN gắn ảnh khách trước giờ bắt đầu lượt bao nhiêu phút
    GUEST_PHOTO_REMINDER_MINUTES_BEFORE("GUEST_PHOTO_REMINDER_MINUTES_BEFORE", "60"),
    // N1 (V120): độ dài mục đích lượt khách (BR-GV-03), kiểm chéo min < max ≤ GuestRules.PURPOSE_STORAGE_MAX_LENGTH
    GUEST_PURPOSE_MIN_LENGTH("GUEST_PURPOSE_MIN_LENGTH", "10"),
    GUEST_PURPOSE_MAX_LENGTH("GUEST_PURPOSE_MAX_LENGTH", "500"),
    // V141: dung lượng tối đa mỗi ảnh khách (KB): host gửi ảnh khi tạo lượt, host gắn / thay ảnh; thay property
    // icss.guest.photo-max-kb (đã bỏ). Khoảng 100–10240 ở DB (phép tính trong V141)
    GUEST_PHOTO_MAX_KB("GUEST_PHOTO_MAX_KB", "2048"),
    // UI-19 (V69): số dòng tối đa mỗi lần nạp tài khoản theo lô (cả luồng người dùng thường và cán bộ)
    USER_BULK_IMPORT_MAX_ROWS("USER_BULK_IMPORT_MAX_ROWS", "200"),
    // V140: dung lượng tối đa ảnh khuôn mặt tài khoản (KB), đăng ký đơn lẻ + nạp theo lô; ảnh khách dùng GUEST_PHOTO_MAX_KB (V141)
    USER_FACE_IMAGE_MAX_KB("USER_FACE_IMAGE_MAX_KB", "350"),
    // Nhật ký ra vào tại camera (V77): khoảng chống ghi trùng cùng người + cùng camera, và số ngày giữ log
    ENTRY_LOG_DEBOUNCE_SECONDS("ENTRY_LOG_DEBOUNCE_SECONDS", "30"),
    ENTRY_LOG_RETENTION_DAYS("ENTRY_LOG_RETENTION_DAYS", "90"),
    // BR-GD-02 / BR-GD-03 (V79): bảo vệ đã tiếp nhận sự cố được vào khu có sự cố trong N phút kể từ lúc tiếp nhận;
    // xử lý xong thì còn thời gian ân hạn để rời khu (không vượt quá N phút)
    GUARD_INCIDENT_ACCESS_MINUTES("GUARD_INCIDENT_ACCESS_MINUTES", "30"),
    GUARD_INCIDENT_EXIT_GRACE_MINUTES("GUARD_INCIDENT_EXIT_GRACE_MINUTES", "5"),
    // BR-IE-04 / BR-IE-08 / BR-IE-09 (V80): sự cố còn NEW sau N phút kể từ lúc tạo thì báo FM;
    // chu kỳ job kiểm tra leo thang, đọc lại trước mỗi lần chạy
    INCIDENT_ESCALATE_FM_MINUTES("INCIDENT_ESCALATE_FM_MINUTES", "5"),
    INCIDENT_ESCALATION_CHECK_SECONDS("INCIDENT_ESCALATION_CHECK_SECONDS", "30"),
    // Checklist 6.1 (V82): độ dài lý do thao tác (huỷ, từ chối, hoàn thành đơn, quy tắc khu vực, cấp truy cập...),
    // tính sau khi trim. Kiểm duy nhất ở ReasonValidator, đọc lại mỗi lần gọi
    REASON_MIN_LENGTH("REASON_MIN_LENGTH", "10"),
    REASON_MAX_LENGTH("REASON_MAX_LENGTH", "500"),
    // M-03 (V89): hạn link đặt lại mật khẩu (phút), đọc lúc tạo token
    PASSWORD_RESET_TOKEN_MINUTES("PASSWORD_RESET_TOKEN_MINUTES", "15"),
    // BE-06 (V119): độ dài tối thiểu mật khẩu mới người dùng tự đặt (kèm quy tắc có chữ + số ở AuthService)
    PASSWORD_MIN_LENGTH("PASSWORD_MIN_LENGTH", "8"),
    // V121: thời hạn token xem video ghi hình / live có chú thích (giây), đọc mỗi lần cấp token
    RECORDING_STREAM_TOKEN_SECONDS("RECORDING_STREAM_TOKEN_SECONDS", "300"),
    ANNOTATED_STREAM_TOKEN_SECONDS("ANNOTATED_STREAM_TOKEN_SECONDS", "60"),
    // V126 (Checklist 6.1): thời hạn lưu trữ video ghi hình camera định kỳ (giờ), mặc định 0.5 giờ = 30 phút
    RECORDING_RETENTION_HOURS("RECORDING_RETENTION_HOURS", "0.5"),
    // V130: ghi thêm sau khi hết phát hiện sự cố (mặc định 0s). V142: bỏ RECORDING_SEGMENT_SECONDS và
    // INCIDENT_RECORDING_START_DELAY_SECONDS khỏi màn Cấu hình (AI đọc từ .env)
    INCIDENT_RECORDING_NO_DETECTION_TIMEOUT_SECONDS("INCIDENT_RECORDING_NO_DETECTION_TIMEOUT_SECONDS", "0.0"),
    // V123 (P-MAP-2, MM-05): ước tính thời gian di chuyển trên bản đồ (web + app bảo vệ)
    WALKING_SPEED_MPS("WALKING_SPEED_MPS", "1.45"),
    FLOOR_CHANGE_SECONDS("FLOOR_CHANGE_SECONDS", "14"),
    // Phân phối sự cố (V90, K7): bật mời theo đợt, cửa sổ tính load score, chu kỳ job lời mời;
    // mức độ khi không có luật severity khớp (BR-SEV-03, validate riêng trong SystemConfigService)
    DISPATCH_ENABLED("DISPATCH_ENABLED", "false"),
    DISPATCH_LOAD_WINDOW_MINUTES("DISPATCH_LOAD_WINDOW_MINUTES", "60"),
    DISPATCH_CHECK_SECONDS("DISPATCH_CHECK_SECONDS", "5"),
    INCIDENT_SEVERITY_DEFAULT("INCIDENT_SEVERITY_DEFAULT", "MEDIUM"),
    // PR-AU (V110): chống dò tài khoản / dò mật khẩu khi đăng nhập — khoá tạm theo e-mail và giới hạn theo IP
    LOGIN_MAX_FAILED_ATTEMPTS("LOGIN_MAX_FAILED_ATTEMPTS", "5"),
    LOGIN_FAILED_WINDOW_MINUTES("LOGIN_FAILED_WINDOW_MINUTES", "15"),
    LOGIN_LOCK_MINUTES("LOGIN_LOCK_MINUTES", "15"),
    LOGIN_IP_RATE_PER_MINUTE("LOGIN_IP_RATE_PER_MINUTE", "20"),
    // V143 (Checklist 6.1): khung nhận ca / kết thúc ca bảo vệ (±5 phút mặc định)
    GUARD_SHIFT_CHECK_IN_WINDOW_MINUTES("GUARD_SHIFT_CHECK_IN_WINDOW_MINUTES", "5"),
    GUARD_SHIFT_CHECK_OUT_WINDOW_MINUTES("GUARD_SHIFT_CHECK_OUT_WINDOW_MINUTES", "5");

    private final String key;
    private final String defaultValue;
}
