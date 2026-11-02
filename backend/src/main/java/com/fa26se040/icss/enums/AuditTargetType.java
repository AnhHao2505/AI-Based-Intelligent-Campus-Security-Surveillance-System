package com.fa26se040.icss.enums;

public enum AuditTargetType {
    AREA,
    AREA_EVENT_MODE,
    AREA_ASSIGNMENT,
    USER_ACCESS_LEVEL,
    AREA_ACCESS_RULES,
    LEVEL_PRESET,
    ACCESS_REQUEST,
    REASON_CATALOG,
    AREA_EVENT_SCHEDULE,
    // Khách G-A (BR-GV-30, V60)
    GUEST_VISIT,
    GUEST,
    // Xem ảnh qua /api/media (V81): ảnh khuôn mặt người dùng, ảnh bằng chứng sự cố
    FACE_PROFILE,
    SECURITY_EVIDENCE,
    // Phân phối sự cố (V90): luật severity, chính sách dispatch theo mức, danh mục vị trí trực
    INCIDENT_SEVERITY_RULE,
    INCIDENT_DISPATCH_POLICY,
    DUTY_POST_TYPE
}
