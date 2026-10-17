package com.fa26se040.icss.enums;

/**
 * Nguồn cấp quyền ra vào được dùng trong AccessDecisionService#checkEntry.
 */
public enum AccessSource {
    ASSIGNED_PERSONNEL,
    ACCESS_LEVEL,
    OPEN_EVENT,
    ACCESS_REQUEST,
    GUEST_VISIT,
    // BR-GD-02 / BR-GD-03: bảo vệ đang xử lý sự cố mình đã tiếp nhận tại khu vực
    INCIDENT_RESPONSE,
    NONE
}
