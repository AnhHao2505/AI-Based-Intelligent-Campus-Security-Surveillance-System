package com.fa26se040.icss.dto.guest;

import com.fa26se040.icss.enums.GuestBiometricStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Khách trong response. KHÔNG có object key / URL ảnh / embedding (BR-GV-18). */
public record GuestResponse(
        UUID id,
        String fullName,
        String organization,
        GuestBiometricStatus biometricStatus,
        OffsetDateTime photoAttachedAt,
        String consentNoticeVersion,
        boolean anonymized
) {
}
