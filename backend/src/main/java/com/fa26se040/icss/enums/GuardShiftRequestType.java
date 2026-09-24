package com.fa26se040.icss.enums;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum GuardShiftRequestType {
    SWAP_SHIFT,
    LEAVE_REQUEST;

    @JsonCreator
    public static GuardShiftRequestType fromString(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if ("SWAP".equals(normalized) || "SWAP_SHIFT".equals(normalized)) {
            return SWAP_SHIFT;
        }
        if ("LEAVE".equals(normalized) || "LEAVE_REQUEST".equals(normalized)) {
            return LEAVE_REQUEST;
        }
        return GuardShiftRequestType.valueOf(normalized);
    }
}
