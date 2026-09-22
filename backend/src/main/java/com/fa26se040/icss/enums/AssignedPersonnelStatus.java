package com.fa26se040.icss.enums;

/**
 * Trạng thái của một bản ghi Assigned Personnel. KHÔNG lưu DB, tính tại thời điểm trả về
 * dựa trên valid_from, valid_to, revoked_at.
 */
public enum AssignedPersonnelStatus {
    ACTIVE,
    UPCOMING,
    EXPIRED,
    REVOKED
}
