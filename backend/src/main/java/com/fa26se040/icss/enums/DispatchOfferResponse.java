package com.fa26se040.icss.enums;

/** Kết quả của một lời mời (V93, K3). NULL trong DB = lời mời còn chờ. */
public enum DispatchOfferResponse {
    ACCEPTED,
    DECLINED,
    EXPIRED,
    CANCELLED
}
