package com.fa26se040.icss.enums;

/**
 * Trạng thái lượt khách (V60).
 * PENDING → APPROVED / REJECTED (FM) · PENDING / APPROVED → CANCELLED (host) · APPROVED → REVOKED (FM)
 * · PENDING → EXPIRED (hệ thống) · APPROVED → COMPLETED (hệ thống).
 */
public enum GuestVisitStatus {
    PENDING,
    APPROVED,
    REJECTED,
    CANCELLED,
    REVOKED,
    EXPIRED,
    COMPLETED
}
