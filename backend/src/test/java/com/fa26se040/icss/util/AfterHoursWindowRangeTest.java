package com.fa26se040.icss.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** EV-N7 (V115): AfterHoursWindow.firstAfterHoursInstant — khoảng [from, to) có chạm khung ban đêm của ngày nào không. */
class AfterHoursWindowRangeTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final LocalDate D = LocalDate.of(2026, 11, 20);
    private static final LocalTime N22 = LocalTime.of(22, 0);
    private static final LocalTime N06 = LocalTime.of(6, 0);

    private static OffsetDateTime at(int plusDays, int hour, int minute) {
        return D.plusDays(plusDays).atTime(hour, minute).atZone(VN).toOffsetDateTime();
    }

    private static Optional<OffsetDateTime> night(OffsetDateTime from, OffsetDateTime to, LocalTime start, LocalTime end) {
        return AfterHoursWindow.firstAfterHoursInstant(from, to, start, end);
    }

    @Test
    @DisplayName("Khung qua nửa đêm 22:00–06:00: 21:00–22:30 chạm lúc 22:00; 21:00–22:00 không chạm (biên); 06:00–07:00 không chạm (biên)")
    void overnightWindow_boundaries() {
        assertThat(night(at(0, 21, 0), at(0, 22, 30), N22, N06)).contains(at(0, 22, 0));
        assertThat(night(at(0, 21, 0), at(0, 22, 0), N22, N06)).isEmpty();
        assertThat(night(at(0, 6, 0), at(0, 7, 0), N22, N06)).isEmpty();
        assertThat(night(at(0, 5, 59), at(0, 7, 0), N22, N06)).contains(at(0, 5, 59));
        assertThat(night(at(0, 23, 0), at(1, 1, 0), N22, N06)).contains(at(0, 23, 0));
    }

    @Test
    @DisplayName("Khoảng ban ngày nhiều ngày (08:00 ngày 1 → 14:00 ngày 2, 30 giờ) chạm ban đêm đầu tiên lúc 22:00 ngày 1")
    void multiDayDaytimeRange_hitsFirstNight() {
        assertThat(night(at(0, 8, 0), at(1, 14, 0), N22, N06)).contains(at(0, 22, 0));
        assertThat(night(at(0, 8, 0), at(0, 21, 0), N22, N06)).isEmpty();
    }

    @Test
    @DisplayName("Khung KHÔNG qua nửa đêm 20:00–23:00: 19:00–20:30 chạm 20:00; 23:00–23:45 và 00:30–05:00 không chạm; 22:59–23:00 chạm")
    void sameDayWindow() {
        LocalTime s = LocalTime.of(20, 0);
        LocalTime e = LocalTime.of(23, 0);
        assertThat(night(at(0, 19, 0), at(0, 20, 30), s, e)).contains(at(0, 20, 0));
        assertThat(night(at(0, 23, 0), at(0, 23, 45), s, e)).isEmpty();
        assertThat(night(at(1, 0, 30), at(1, 5, 0), s, e)).isEmpty();
        assertThat(night(at(0, 22, 59), at(0, 23, 0), s, e)).contains(at(0, 22, 59));
        // 23:30 hôm nay -> 20:30 hôm sau chạm ban đêm của ngày sau
        assertThat(night(at(0, 23, 30), at(1, 20, 30), s, e)).contains(at(1, 20, 0));
    }

    @Test
    @DisplayName("START = END: không có ban đêm; khoảng rỗng / ngược -> không chạm")
    void emptyWindowAndEmptyRange() {
        LocalTime t = LocalTime.of(6, 0);
        assertThat(night(at(0, 0, 0), at(5, 0, 0), t, t)).isEmpty();
        assertThat(night(at(0, 23, 0), at(0, 23, 0), N22, N06)).isEmpty();
        assertThat(night(at(0, 23, 30), at(0, 23, 0), N22, N06)).isEmpty();
    }

    @Test
    @DisplayName("Mốc đầu vào ở múi giờ khác vẫn quy về giờ Việt Nam: 14:30Z (21:30 VN) → 15:30Z (22:30 VN) chạm lúc 22:00 VN")
    void otherOffsetInput_convertedToVietnamTime() {
        OffsetDateTime from = at(0, 21, 30).withOffsetSameInstant(java.time.ZoneOffset.UTC);
        OffsetDateTime to = at(0, 22, 30).withOffsetSameInstant(java.time.ZoneOffset.UTC);
        assertThat(night(from, to, N22, N06)).hasValueSatisfying(v -> assertThat(v.toInstant()).isEqualTo(at(0, 22, 0).toInstant()));
    }
}
