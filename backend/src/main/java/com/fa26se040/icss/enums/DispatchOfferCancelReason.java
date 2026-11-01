package com.fa26se040.icss.enums;

/** Lý do huỷ lời mời (V93, BR-DSP-07, BR-DSP-08). */
public enum DispatchOfferCancelReason {
    /** Bảo vệ khác đã nhận sự cố (BR-DSP-07). */
    CLAIMED_BY_OTHER,
    /** Ca của bảo vệ được mời đã kết thúc (BR-DSP-08, E3). */
    SHIFT_ENDED,
    /** Bảo vệ được mời không còn phiên STOMP từ mobile (BR-DSP-08, E4). */
    DISCONNECTED,
    /** Sự cố không còn NEW (BR-DSP-08). */
    INCIDENT_CLOSED
}
