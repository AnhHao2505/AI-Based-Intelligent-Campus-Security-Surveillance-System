package com.fa26se040.icss.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Khoảng thời gian của Dashboard thống kê an ninh, tính theo ngày lịch giờ Việt Nam và luôn gồm hôm nay:
 * từ 00:00 của ngày đầu khoảng đến thời điểm gọi API.
 */
@Getter
@RequiredArgsConstructor
public enum SecurityStatsRange {
    TODAY(1),
    LAST_7_DAYS(7),
    LAST_30_DAYS(30);

    /** Số ngày lịch trong khoảng, kể cả hôm nay. */
    private final int days;
}
