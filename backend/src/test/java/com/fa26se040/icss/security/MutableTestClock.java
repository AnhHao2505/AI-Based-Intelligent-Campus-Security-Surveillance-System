package com.fa26se040.icss.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Đồng hồ chỉnh tay cho test bộ đếm đăng nhập: tua thời gian thay vì sleep. */
final class MutableTestClock extends Clock {

    private volatile Instant now;

    MutableTestClock(Instant start) {
        this.now = start;
    }

    void advance(Duration duration) {
        now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
