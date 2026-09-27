package com.fa26se040.icss.enums;

/**
 * Nguồn cấp quyền ra vào được dùng trong AccessDecisionService#checkEntry.
 */
public enum AccessSource {
    ASSIGNED_PERSONNEL,
    ACCESS_LEVEL,
    OPEN_EVENT,
    ACCESS_REQUEST,
    NONE
}
