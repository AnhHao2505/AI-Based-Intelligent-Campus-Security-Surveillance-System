package com.fa26se040.icss.enums;

/**
 * Khung giờ của luật severity (V90, BR-SEV-02). AFTER_HOURS = [AI_AFTER_HOUR_START, AI_AFTER_HOUR_END),
 * IN_HOURS = phần còn lại, ANY = luật dự phòng khi không có luật đúng khung giờ.
 */
public enum IncidentTimeWindow {
    IN_HOURS,
    AFTER_HOURS,
    ANY
}
