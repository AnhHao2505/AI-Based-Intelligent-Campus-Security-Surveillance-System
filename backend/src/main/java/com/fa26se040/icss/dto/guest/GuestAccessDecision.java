package com.fa26se040.icss.dto.guest;

import com.fa26se040.icss.enums.GuestEntryDenyReason;

import java.util.UUID;

/**
 * Kết quả checkGuestEntry (BR-GV-20). source = GUEST_VISIT khi cho vào, NONE khi từ chối (kèm mã lý do).
 */
public record GuestAccessDecision(boolean allowed, String source, GuestEntryDenyReason reason, UUID visitId) {

    public static final String SOURCE_GUEST_VISIT = "GUEST_VISIT";
    public static final String SOURCE_NONE = "NONE";

    public static GuestAccessDecision allow(UUID visitId) {
        return new GuestAccessDecision(true, SOURCE_GUEST_VISIT, null, visitId);
    }

    public static GuestAccessDecision deny(GuestEntryDenyReason reason, UUID visitId) {
        return new GuestAccessDecision(false, SOURCE_NONE, reason, visitId);
    }
}
