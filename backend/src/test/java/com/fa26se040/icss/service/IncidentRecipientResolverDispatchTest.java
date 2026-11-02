package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Building;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** V93 (BR-DSP-02, BR-DSP-13, BR-GB-07): IncidentRecipientResolver.findDispatchCandidates. */
@ExtendWith(MockitoExtension.class)
class IncidentRecipientResolverDispatchTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);
    private static final OffsetDateTime NOW = OffsetDateTime.of(TODAY, LocalTime.of(10, 0), ZoneOffset.ofHours(7));
    private static final Building ALPHA = Building.builder().id(UUID.randomUUID()).name("Tòa Alpha").build();
    private static final Building BETA = Building.builder().id(UUID.randomUUID()).name("Tòa Beta").build();
    private static final Set<String> DISPATCHABLE = Set.of("PATROL");
    private static final Predicate<UUID> ALL_MOBILE = id -> true;

    @Mock
    private OnDutyGuardQueryRepository onDutyGuardQueryRepository;

    @InjectMocks
    private IncidentRecipientResolver resolver;

    private static User guard(String code) {
        return User.builder().id(UUID.randomUUID()).userCode(code).fullName("Bảo vệ " + code).role(Role.GUARD).build();
    }

    private static GuardShift shift(User guard, Building building, String post) {
        return GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard)
                .building(building)
                .dutyPostCode(post)
                .shiftDate(TODAY)
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(8, 0))
                .endTime(LocalTime.of(12, 0))
                .status(ShiftStatus.CHECKED_IN)
                .build();
    }

    private void given(GuardShift... shifts) {
        when(onDutyGuardQueryRepository.findByStatusAndShiftDateBetween(eq(ShiftStatus.CHECKED_IN), any(), any()))
                .thenReturn(List.of(shifts));
    }

    @Test
    @DisplayName("BR-GB-07: chỉ bảo vệ có ca ở toà của sự cố; ca không có toà bị loại")
    void onlySameBuilding() {
        User a = guard("A");
        User b = guard("B");
        User noBuilding = guard("C");
        given(shift(a, ALPHA, "PATROL"), shift(b, BETA, "PATROL"), shift(noBuilding, null, "PATROL"));

        List<User> result = resolver.findDispatchCandidates(NOW, ALPHA.getId(), null, Set.of(), ALL_MOBILE, DISPATCHABLE);

        assertEquals(List.of(a), result);
    }

    @Test
    @DisplayName("Toà của sự cố null (khu không gắn tầng) -> không có ứng viên, không truy vấn ca")
    void nullIncidentBuilding_empty() {
        List<User> result = resolver.findDispatchCandidates(NOW, null, null, Set.of(), ALL_MOBILE, DISPATCHABLE);
        assertTrue(result.isEmpty());
        verifyNoInteractions(onDutyGuardQueryRepository);
    }

    @Test
    @DisplayName("X10: chỉ bảo vệ có phiên mobile")
    void onlyMobileSession() {
        User mobile = guard("M");
        User webOnly = guard("W");
        given(shift(mobile, ALPHA, "PATROL"), shift(webOnly, ALPHA, "PATROL"));

        List<User> result = resolver.findDispatchCandidates(NOW, ALPHA.getId(), null, Set.of(),
                id -> id.equals(mobile.getId()), DISPATCHABLE);

        assertEquals(List.of(mobile), result);
    }

    @Test
    @DisplayName("BR-DSP-02: loại người bận / có lời mời chờ (excluded) và người bị nhận diện trong sự cố")
    void excludesBusyAndSubject() {
        User free = guard("FREE");
        User busy = guard("BUSY");
        User subject = guard("nv001");
        given(shift(free, ALPHA, "PATROL"), shift(busy, ALPHA, "PATROL"), shift(subject, ALPHA, "PATROL"));

        List<User> result = resolver.findDispatchCandidates(NOW, ALPHA.getId(), " NV001 ", Set.of(busy.getId()),
                ALL_MOBILE, DISPATCHABLE);

        assertEquals(List.of(free), result);
    }

    @Test
    @DisplayName("BR-DSP-13: ưu tiên vị trí được mời; người ở cổng / phòng camera bị loại khi còn người tuần tra")
    void prefersDispatchablePost() {
        User patrol = guard("P");
        User gate = guard("G");
        given(shift(patrol, ALPHA, "PATROL"), shift(gate, ALPHA, "GATE"));

        List<User> result = resolver.findDispatchCandidates(NOW, ALPHA.getId(), null, Set.of(), ALL_MOBILE, DISPATCHABLE);

        assertEquals(List.of(patrol), result);
    }

    @Test
    @DisplayName("E11: mọi ứng viên đều ở vị trí không được mời -> bỏ điều kiện vị trí")
    void fallbackWhenNoDispatchablePost() {
        User gate = guard("G");
        User cameraRoom = guard("CR");
        given(shift(gate, ALPHA, "GATE"), shift(cameraRoom, ALPHA, "CAMERA_ROOM"));

        List<User> result = resolver.findDispatchCandidates(NOW, ALPHA.getId(), null, Set.of(), ALL_MOBILE, DISPATCHABLE);

        assertEquals(List.of(gate, cameraRoom), result);
    }

    @Test
    @DisplayName("Ca đã quá giờ hoặc tài khoản bị vô hiệu hoá -> loại như findOnDutyGuards")
    void offShiftOrInactive_excluded() {
        User onDuty = guard("ON");
        User inactive = guard("OFF");
        inactive.setIsActive(false);
        GuardShift ended = shift(guard("END"), ALPHA, "PATROL");
        ended.setStartTime(LocalTime.of(6, 0));
        ended.setEndTime(LocalTime.of(9, 0));
        given(shift(onDuty, ALPHA, "PATROL"), shift(inactive, ALPHA, "PATROL"), ended);

        List<User> result = resolver.findDispatchCandidates(NOW, ALPHA.getId(), null, Set.of(), ALL_MOBILE, DISPATCHABLE);

        assertEquals(List.of(onDuty), result);
    }
}
