package com.fa26se040.icss.exception;

/**
 * V93 (BR-DSP-06): sự cố đang ở bước 1 và bảo vệ gọi claim không có lời mời còn chờ.
 * SecurityIncidentController trả 403, errorCode {@code INCIDENT_NOT_OFFERED}. Không kế thừa IllegalStateException để
 * không rơi vào nhánh 409 INCIDENT_ALREADY_CLAIMED.
 */
public class IncidentNotOfferedException extends RuntimeException {
    public static final String ERROR_CODE = "INCIDENT_NOT_OFFERED";

    public IncidentNotOfferedException(String message) {
        super(message);
    }
}
