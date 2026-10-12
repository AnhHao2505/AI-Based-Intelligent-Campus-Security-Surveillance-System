package com.fa26se040.icss.enums;

/** Mã lý do từ chối khách vào khu vực (BR-GV-20). */
public enum GuestEntryDenyReason {
    VISIT_NOT_ACTIVE,
    OUTSIDE_WINDOW,
    AREA_NOT_IN_VISIT,
    AREA_INACTIVE,
    AREA_TYPE_NOT_ALLOWED,
    HOST_LOST_ACCESS,
    BIOMETRIC_DELETED,
    // BR-GV-38: khách chưa đăng ký khuôn mặt tại quầy (NO_PHOTO / PHOTO_ONLY) -> chưa nhận diện được, không cho vào
    BIOMETRIC_NOT_READY
}
