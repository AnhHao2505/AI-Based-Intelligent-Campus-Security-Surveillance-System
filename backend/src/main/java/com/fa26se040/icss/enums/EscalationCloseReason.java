package com.fa26se040.icss.enums;

/**
 * Lý do đóng bản ghi incident_escalations (V80, CHECK chk_incident_escalations_close_reason).
 * ESCALATED: đã báo FM (BR-IE-04 / BR-IE-05). CLAIMED / RESOLVED / DISMISSED: sự cố rời NEW trước hạn (BR-IE-06).
 */
public enum EscalationCloseReason {
    ESCALATED,
    CLAIMED,
    RESOLVED,
    DISMISSED
}
