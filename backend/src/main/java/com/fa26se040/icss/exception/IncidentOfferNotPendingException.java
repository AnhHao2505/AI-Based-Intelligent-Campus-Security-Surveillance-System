package com.fa26se040.icss.exception;

/**
 * V93 (K5): bảo vệ từ chối nhưng không có lời mời còn chờ, chưa hết hạn cho sự cố đó (đã nhận, đã từ chối, đã hết hạn
 * hoặc bị huỷ). SecurityIncidentController trả 409, errorCode {@code INCIDENT_OFFER_NOT_PENDING}.
 */
public class IncidentOfferNotPendingException extends RuntimeException {
    public static final String ERROR_CODE = "INCIDENT_OFFER_NOT_PENDING";

    public IncidentOfferNotPendingException(String message) {
        super(message);
    }
}
