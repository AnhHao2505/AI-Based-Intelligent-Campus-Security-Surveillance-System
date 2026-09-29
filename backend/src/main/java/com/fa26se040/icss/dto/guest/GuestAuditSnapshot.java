package com.fa26se040.icss.dto.guest;

import com.fa26se040.icss.dto.accesscontrol.snapshot.AuditSnapshot;
import com.fa26se040.icss.enums.GuestBiometricStatus;

import java.util.UUID;

/**
 * Snapshot audit của một khách (BR-GV-30). Không chứa họ tên, ảnh, embedding, object key, URL.
 * photoRemovedFromStorage: false = xoá ảnh trên kho lỗi, job sẽ thử lại (BR-GV-27).
 */
public record GuestAuditSnapshot(
        UUID guestId,
        UUID visitId,
        GuestBiometricStatus biometricStatus,
        String consentNoticeVersion,
        Boolean photoRemovedFromStorage,
        boolean anonymized
) implements AuditSnapshot {
}
