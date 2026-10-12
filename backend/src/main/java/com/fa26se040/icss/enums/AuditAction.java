package com.fa26se040.icss.enums;

public enum AuditAction {
    CREATE,
    UPDATE,
    UPDATE_GEOMETRY,
    DELETE_GEOMETRY,
    DEACTIVATE,
    REACTIVATE,
    UPDATE_CAMERAS,
    ENABLE_EVENT_MODE,
    DISABLE_EVENT_MODE,
    EXTEND_EVENT_MODE,
    ASSIGN,
    UPDATE_VALIDITY,
    REVOKE,
    APPROVE,
    REJECT,
    CANCEL,
    FINISH,
    EXPIRE,
    FAIL,
    EXPIRE_EVENT_MODE,
    // Step 5b (BR-TC-02): ADMIN đổi loại khu vực
    CHANGE_TYPE,
    // Khách G-A (BR-GV-30, V60)
    COMPLETE,
    ATTACH_PHOTO,
    VIEW_PHOTO,
    DELETE_BIOMETRIC,
    ANONYMIZE,
    // Step 6 (BR-AD-07, V62): ADMIN khôi phục khu vực đã vô hiệu hoá
    RESTORE
}
