package com.fa26se040.icss.enums;

/**
 * Trạng thái sinh trắc của khách: NO_PHOTO → PHOTO_READY (có ảnh + embedding) | PHOTO_ONLY (chỉ ảnh) → DELETED.
 */
public enum GuestBiometricStatus {
    NO_PHOTO,
    PHOTO_READY,
    PHOTO_ONLY,
    DELETED
}
