package com.fa26se040.icss.dto.guest;

import com.fa26se040.icss.dto.accesscontrol.snapshot.AuditSnapshot;
import com.fa26se040.icss.enums.GuestBiometricStatus;

import java.util.UUID;

/**
 * Snapshot audit của một khách (BR-GV-30). Không chứa họ tên, ảnh, embedding, object key, URL.
 * photoDeletionScheduled: true = có ảnh trên kho được lên lịch xoá SAU commit (A9); xoá lỗi thì job thử lại (BR-GV-27).
 */
public record GuestAuditSnapshot(
        UUID guestId,
        UUID visitId,
        GuestBiometricStatus biometricStatus,
        String consentNoticeVersion,
        Boolean photoDeletionScheduled,
        boolean anonymized
) implements AuditSnapshot {
}
