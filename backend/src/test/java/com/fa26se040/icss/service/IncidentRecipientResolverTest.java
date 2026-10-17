package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.OnDutyGuardQueryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BR-IE-02: bảo vệ đang trực = ca CHECKED_IN, now (giờ Việt Nam) trong [start, end), kể cả ca qua đêm; mỗi bảo vệ một lần.
 * Lọc trạng thái nằm ở query (chỉ hỏi CHECKED_IN); SCHEDULED trên DB thật kiểm ở IncidentEscalationIntegrationTest.
 */
@ExtendWith(MockitoExtension.class)
class IncidentRecipientResolverTest {

    private static final ZoneOffset VN = ZoneOffset.ofHours(7);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 17);
    private static final LocalDate YESTERDAY = TODAY.minusDays(1);

    @Mock
    private OnDutyGuardQueryRepository onDutyGuardQueryRepository;

    @InjectMocks
    private IncidentRecipientResolver resolver;

    private static User guard(String name) {
        return User.builder().id(UUID.randomUUID()).fullName(name).role(Role.GUARD).build();
    }

    private static GuardShift shift(User guard, LocalDate date, String start, String end, ShiftStatus status) {
        return GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard)
                .shiftDate(date)
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.parse(start))
                .endTime(LocalTime.parse(end))
                .status(status)
                .build();
    }

    private static OffsetDateTime vnTime(LocalDate date, String time) {
        return OffsetDateTime.of(date, LocalTime.parse(time), VN);
    }

    private void givenCheckedInShifts(GuardShift... shifts) {
        when(onDutyGuardQueryRepository.findByStatusAndShiftDateBetween(eq(ShiftStatus.CHECKED_IN), any(), any()))
                .thenReturn(List.of(shifts));
    }

    @Test
    @DisplayName("CHECKED_IN trong khung giờ -> có")
    void checkedInWithinWindow_included() {
        User a = guard("A");
        givenCheckedInShifts(shift(a, TODAY, "08:00", "12:00", ShiftStatus.CHECKED_IN));

        List<User> result = resolver.findOnDutyGuards(vnTime(TODAY, "10:00"));

        assertEquals(List.of(a), result);
    }

    @Test
    @DisplayName("Mốc kết thúc không tính: đúng giờ hết ca -> không")
    void endTimeExclusive() {
        givenCheckedInShifts(shift(guard("A"), TODAY, "08:00", "12:00", ShiftStatus.CHECKED_IN));

        assertTrue(resolver.findOnDutyGuards(vnTime(TODAY, "12:00")).isEmpty());
    }

    @Test
    @DisplayName("SCHEDULED -> không: chỉ hỏi ca CHECKED_IN của hôm nay và hôm qua (giờ Việt Nam)")
    void onlyCheckedInShiftsOfTodayAndYesterdayAreQueried() {
        givenCheckedInShifts();

        // 19:00 UTC ngày 16 = 02:00 ngày 17 giờ Việt Nam -> "hôm nay" là ngày 17
        resolver.findOnDutyGuards(OffsetDateTime.of(YESTERDAY, LocalTime.of(19, 0), ZoneOffset.UTC));

        verify(onDutyGuardQueryRepository).findByStatusAndShiftDateBetween(ShiftStatus.CHECKED_IN, YESTERDAY, TODAY);
    }

    @Test
    @DisplayName("CHECKED_IN từ hôm qua đã hết giờ (quên trả ca) -> không")
    void stuckCheckedInFromYesterday_excluded() {
        givenCheckedInShifts(shift(guard("A"), YESTERDAY, "08:00", "16:00", ShiftStatus.CHECKED_IN));

        assertTrue(resolver.findOnDutyGuards(vnTime(TODAY, "10:00")).isEmpty());
    }

    @Test
    @DisplayName("Ca qua đêm 22:00–06:00 (ngày ca hôm qua) lúc 02:00 -> có")
    void overnightShiftAt0200_included() {
        User a = guard("A");
        givenCheckedInShifts(shift(a, YESTERDAY, "22:00", "06:00", ShiftStatus.CHECKED_IN));

        assertEquals(List.of(a), resolver.findOnDutyGuards(vnTime(TODAY, "02:00")));
    }

    @Test
    @DisplayName("Ca qua đêm 22:00–06:00: 23:00 cùng ngày ca -> có; 07:00 hôm sau -> không")
    void overnightShiftBoundaries() {
        User a = guard("A");
        givenCheckedInShifts(shift(a, TODAY, "22:00", "06:00", ShiftStatus.CHECKED_IN));
        assertEquals(List.of(a), resolver.findOnDutyGuards(vnTime(TODAY, "23:00")));

        givenCheckedInShifts(shift(a, YESTERDAY, "22:00", "06:00", ShiftStatus.CHECKED_IN));
        assertTrue(resolver.findOnDutyGuards(vnTime(TODAY, "07:00")).isEmpty());
    }

    @Test
    @DisplayName("2 ca trùng của cùng bảo vệ -> 1 lần")
    void overlappingShiftsOfSameGuard_once() {
        User a = guard("A");
        User b = guard("B");
        givenCheckedInShifts(
                shift(a, TODAY, "06:00", "14:00", ShiftStatus.CHECKED_IN),
                shift(b, TODAY, "08:00", "16:00", ShiftStatus.CHECKED_IN),
                shift(a, TODAY, "09:00", "17:00", ShiftStatus.CHECKED_IN));

        assertEquals(List.of(a, b), resolver.findOnDutyGuards(vnTime(TODAY, "10:00")));
    }
}
