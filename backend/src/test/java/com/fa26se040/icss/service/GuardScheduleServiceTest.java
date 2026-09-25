package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.guard.BulkClearShiftsRequest;
import com.fa26se040.icss.dto.guard.BulkClearShiftsResponse;
import com.fa26se040.icss.dto.guard.GenerateShiftsRequest;
import com.fa26se040.icss.dto.guard.GenerateShiftsResponse;
import com.fa26se040.icss.dto.guard.GuardScheduleTemplateCreateRequest;
import com.fa26se040.icss.dto.guard.GuardScheduleTemplateDto;
import com.fa26se040.icss.dto.guard.GuardShiftDto;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.GuardScheduleTemplate;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.GuardScheduleTemplateRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuardScheduleServiceTest {

    @Mock
    private GuardScheduleTemplateRepository templateRepository;

    @Mock
    private GuardShiftRepository shiftRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private com.fa26se040.icss.repository.GuardTeamRepository teamRepository;

    @Mock
    private com.fa26se040.icss.repository.GuardShiftRequestRepository shiftRequestRepository;

    @Mock
    private com.fa26se040.icss.repository.GuardTeamDispatchRepository dispatchRepository;

    @InjectMocks
    private GuardScheduleService guardScheduleService;

    private User guardUser;
    private Area securityRoomArea;
    private Area gateArea;

    @BeforeEach
    void setUp() {
        guardUser = User.builder()
                .id(UUID.randomUUID())
                .fullName("Nguyễn Văn An")
                .userCode("SEC-001")
                .role(Role.GUARD)
                .email("guard.an@fpt.edu.vn")
                .build();

        securityRoomArea = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng bảo vệ Tòa Alpha")
                .building("TOA_ALPHA")
                .build();

        gateArea = Area.builder()
                .id(UUID.randomUUID())
                .name("Sảnh cổng chính")
                .building("TOA_ALPHA")
                .build();
    }

    @Test
    @DisplayName("Tạo lịch mẫu thành công cho nhân viên GUARD")
    void testCreateTemplate_Success() {
        GuardScheduleTemplateCreateRequest request = GuardScheduleTemplateCreateRequest.builder()
                .guardId(guardUser.getId())
                .dayOfWeek(2) // Thứ Hai
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .areaId(securityRoomArea.getId())
                .radioChannel("Kênh 2")
                .notes("Trực phòng camera")
                .build();

        when(userRepository.findById(guardUser.getId())).thenReturn(Optional.of(guardUser));
        when(areaRepository.findById(securityRoomArea.getId())).thenReturn(Optional.of(securityRoomArea));
        when(templateRepository.existsByGuardIdAndDayOfWeekAndStartTimeAndIsActiveTrue(any(), any(), any()))
                .thenReturn(false);

        GuardScheduleTemplate savedTemplate = GuardScheduleTemplate.builder()
                .id(UUID.randomUUID())
                .guard(guardUser)
                .dayOfWeek(2)
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .area(securityRoomArea)
                .radioChannel("Kênh 2")
                .notes("Trực phòng camera")
                .isActive(true)
                .build();

        when(templateRepository.save(any())).thenReturn(savedTemplate);

        GuardScheduleTemplateDto result = guardScheduleService.createTemplate(request);

        assertNotNull(result);
        assertEquals("Nguyễn Văn An", result.getGuardName());
        assertEquals(ShiftType.SHIFT_MORNING, result.getShiftType());
        assertEquals("Phòng bảo vệ Tòa Alpha", result.getAreaName());
    }

    @Test
    @DisplayName("Tạo lịch mẫu thất bại khi trùng khung giờ của bảo vệ")
    void testCreateTemplate_Fail_DuplicateSlot() {
        GuardScheduleTemplateCreateRequest request = GuardScheduleTemplateCreateRequest.builder()
                .guardId(guardUser.getId())
                .dayOfWeek(2)
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .build();

        when(userRepository.findById(guardUser.getId())).thenReturn(Optional.of(guardUser));
        when(templateRepository.existsByGuardIdAndDayOfWeekAndStartTimeAndIsActiveTrue(any(), any(), any()))
                .thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> guardScheduleService.createTemplate(request));
    }

    @Test
    @DisplayName("Sinh lịch thực tế từ template phát hiện cảnh báo nếu thiếu người trực phòng bảo vệ")
    void testGenerateShifts_WarningWhenNoSecurityRoom() {
        // Template chỉ phân công chốt Sảnh cổng chính, không có Phòng bảo vệ
        GuardScheduleTemplate gateTemplate = GuardScheduleTemplate.builder()
                .id(UUID.randomUUID())
                .guard(guardUser)
                .dayOfWeek(2) // Thứ Hai
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .area(gateArea)
                .radioChannel("Kênh 2")
                .isActive(true)
                .build();

        when(templateRepository.findActiveTemplatesByBuilding("TOA_ALPHA"))
                .thenReturn(List.of(gateTemplate));
        when(shiftRepository.existsByGuardIdAndShiftDateAndStartTime(any(), any(), any()))
                .thenReturn(false);

        // 2026-09-21 là Thứ Hai (DayOfWeek = MONDAY)
        LocalDate monday = LocalDate.of(2026, 9, 21);
        GenerateShiftsRequest req = GenerateShiftsRequest.builder()
                .startDate(monday)
                .endDate(monday)
                .building("TOA_ALPHA")
                .build();

        GenerateShiftsResponse response = guardScheduleService.generateShifts(req);

        assertEquals(1, response.getTotalGenerated());
        assertFalse(response.getWarnings().isEmpty());
        assertTrue(response.getWarnings().get(0).contains("Phòng bảo vệ/Camera"));
        verify(shiftRepository, times(1)).save(any());
    }

    @Test
    @DisplayName("Bảo vệ điểm danh Check-in thành công trong khung giờ cho phép")
    void testCheckIn_Success() {
        UUID shiftId = UUID.randomUUID();
        LocalTime nowTime = LocalTime.now();
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardUser)
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(nowTime)
                .endTime(nowTime.plusHours(8))
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));
        when(shiftRepository.findByGuardIdAndShiftDateAndStartTimeLessThanOrderByStartTimeAsc(any(), any(), any()))
                .thenReturn(List.of());
        when(shiftRepository.save(any())).thenReturn(shift);

        GuardShiftDto result = guardScheduleService.checkIn(shiftId, guardUser.getEmail());

        assertNotNull(result);
        assertEquals(ShiftStatus.CHECKED_IN, shift.getStatus());
        assertNotNull(shift.getCheckInAt());
    }

    @Test
    @DisplayName("Bảo vệ điểm danh thất bại khi chưa đến khung giờ nhận ca (trước hơn 5 phút)")
    void testCheckIn_Fail_TooEarly() {
        UUID shiftId = UUID.randomUUID();
        LocalDateTime futureDateTime = LocalDateTime.now().plusHours(2);
        LocalTime futureTime = futureDateTime.toLocalTime();
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardUser)
                .shiftDate(futureDateTime.toLocalDate())
                .shiftType(ShiftType.SHIFT_AFTERNOON)
                .startTime(futureTime)
                .endTime(futureTime.plusHours(8))
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));
        when(shiftRepository.findByGuardIdAndShiftDateAndStartTimeLessThanOrderByStartTimeAsc(any(), any(), any()))
                .thenReturn(List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guardScheduleService.checkIn(shiftId, guardUser.getEmail()));
        assertTrue(ex.getMessage().contains("Chưa đến giờ nhận ca"));
    }

    @Test
    @DisplayName("Bảo vệ điểm danh thất bại và bị đánh vắng khi quá 5 phút sau giờ bắt đầu ca")
    void testCheckIn_Fail_Overdue_MarksAbsent() {
        UUID shiftId = UUID.randomUUID();
        LocalDateTime pastDateTime = LocalDateTime.now().minusHours(1);
        LocalTime pastTime = pastDateTime.toLocalTime();
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardUser)
                .shiftDate(pastDateTime.toLocalDate())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(pastTime)
                .endTime(pastTime.plusHours(8))
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));
        when(shiftRepository.findByGuardIdAndShiftDateAndStartTimeLessThanOrderByStartTimeAsc(any(), any(), any()))
                .thenReturn(List.of());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guardScheduleService.checkIn(shiftId, guardUser.getEmail()));
        assertTrue(ex.getMessage().contains("VẮNG MẶT"));
        assertEquals(ShiftStatus.ABSENT, shift.getStatus());
        verify(shiftRepository, times(1)).save(shift);
    }

    @Test
    @DisplayName("Bảo vệ không được nhận ca sau nếu ca trước đó chưa hoàn thành")
    void testCheckIn_Fail_PriorShiftNotCompleted() {
        UUID shiftId = UUID.randomUUID();
        LocalTime nowTime = LocalTime.now();
        GuardShift morningShift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guardUser)
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(nowTime.minusHours(4))
                .endTime(nowTime.minusHours(1))
                .status(ShiftStatus.CHECKED_IN)
                .build();

        GuardShift afternoonShift = GuardShift.builder()
                .id(shiftId)
                .guard(guardUser)
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_AFTERNOON)
                .startTime(nowTime)
                .endTime(nowTime.plusHours(8))
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(afternoonShift));
        when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));
        when(shiftRepository.findByGuardIdAndShiftDateAndStartTimeLessThanOrderByStartTimeAsc(any(), any(), any()))
                .thenReturn(List.of(morningShift));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guardScheduleService.checkIn(shiftId, guardUser.getEmail()));
        assertTrue(ex.getMessage().contains("Bạn cần hoàn thành ca trực trước"));
    }

    @Test
    @DisplayName("Bảo vệ điểm danh thất bại nếu check-in cho ca của người khác")
    void testCheckIn_Fail_WrongGuard() {
        UUID shiftId = UUID.randomUUID();
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardUser)
                .build();

        User otherGuard = User.builder()
                .id(UUID.randomUUID())
                .email("other@fpt.edu.vn")
                .build();

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findByEmail("other@fpt.edu.vn")).thenReturn(Optional.of(otherGuard));

        assertThrows(IllegalArgumentException.class,
                () -> guardScheduleService.checkIn(shiftId, "other@fpt.edu.vn"));
    }

    @Test
    @DisplayName("Bảo vệ check-out thành công trong khung giờ [endTime, endTime + 5min]")
    void testCheckOut_Success() {
        UUID shiftId = UUID.randomUUID();
        LocalTime nowTime = LocalTime.now();
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardUser)
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(nowTime.minusHours(8))
                .endTime(nowTime)
                .status(ShiftStatus.CHECKED_IN)
                .build();

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));
        when(shiftRepository.save(any())).thenReturn(shift);

        GuardShiftDto result = guardScheduleService.checkOut(shiftId, guardUser.getEmail());

        assertNotNull(result);
        assertEquals(ShiftStatus.COMPLETED, shift.getStatus());
        assertNotNull(shift.getCheckOutAt());
    }

    @Test
    @DisplayName("Bảo vệ check-out thất bại khi chưa đến giờ kết thúc ca")
    void testCheckOut_Fail_TooEarly() {
        UUID shiftId = UUID.randomUUID();
        LocalTime nowTime = LocalTime.now();
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardUser)
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(nowTime.minusHours(4))
                .endTime(nowTime.plusHours(4))
                .status(ShiftStatus.CHECKED_IN)
                .build();

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guardScheduleService.checkOut(shiftId, guardUser.getEmail()));
        assertTrue(ex.getMessage().contains("Chưa đến giờ kết thúc ca trực"));
    }

    @Test
    @DisplayName("Bảo vệ check-out thất bại khi quá 5 phút sau giờ kết thúc ca")
    void testCheckOut_Fail_TooLate() {
        UUID shiftId = UUID.randomUUID();
        LocalTime nowTime = LocalTime.now();
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardUser)
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(nowTime.minusHours(9))
                .endTime(nowTime.minusMinutes(10))
                .status(ShiftStatus.CHECKED_IN)
                .build();

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guardScheduleService.checkOut(shiftId, guardUser.getEmail()));
        assertTrue(ex.getMessage().contains("Đã quá thời gian kết thúc ca cho phép"));
    }

    @Test
    @DisplayName("Xóa hàng loạt lịch ca trực - Thành công xóa các ca SCHEDULED và bảo vệ ca đang thực hiện / có yêu cầu")
    void testBulkClearShifts_Success() {
        LocalDate startDate = LocalDate.of(2026, 9, 21);
        LocalDate endDate = LocalDate.of(2026, 9, 27);

        GuardShift scheduledShift1 = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guardUser)
                .shiftDate(startDate)
                .shiftType(ShiftType.SHIFT_MORNING)
                .status(ShiftStatus.SCHEDULED)
                .build();

        GuardShift scheduledShiftWithRequest = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guardUser)
                .shiftDate(startDate.plusDays(1))
                .shiftType(ShiftType.SHIFT_AFTERNOON)
                .status(ShiftStatus.SCHEDULED)
                .build();

        GuardShift checkedInShift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guardUser)
                .shiftDate(startDate.plusDays(2))
                .shiftType(ShiftType.SHIFT_NIGHT)
                .status(ShiftStatus.CHECKED_IN)
                .build();

        when(shiftRepository.findShifts(startDate, endDate, null, null, ShiftStatus.SCHEDULED))
                .thenReturn(List.of(scheduledShift1, scheduledShiftWithRequest, checkedInShift));
        when(shiftRequestRepository.existsByShiftId(scheduledShift1.getId())).thenReturn(false);
        when(shiftRequestRepository.existsByShiftId(scheduledShiftWithRequest.getId())).thenReturn(true);

        BulkClearShiftsRequest request = BulkClearShiftsRequest.builder()
                .startDate(startDate)
                .endDate(endDate)
                .build();

        BulkClearShiftsResponse response = guardScheduleService.bulkClearShifts(request);

        assertNotNull(response);
        assertEquals(1, response.getClearedCount());
        assertNotNull(response.getMessage());
        verify(shiftRepository, times(1)).deleteAll(List.of(scheduledShift1));
    }

    @Test
    @DisplayName("Tự động sinh ca từ Wizard - Phân bổ công bằng đều 5 ca/tuần cho 12 bảo vệ (tổng 60 ca)")
    void testGenerateShiftsFromWizard_FairLoadBalancing() {
        LocalDate startDate = LocalDate.of(2026, 9, 28); // Monday
        LocalDate endDate = LocalDate.of(2026, 10, 4);   // Sunday

        List<User> guards = new java.util.ArrayList<>();
        List<UUID> guardIds = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            UUID gId = UUID.randomUUID();
            guardIds.add(gId);
            guards.add(User.builder()
                    .id(gId)
                    .fullName("Guard " + i)
                    .email("guard" + i + "@fpt.edu.vn")
                    .role(Role.GUARD)
                    .build());
        }

        when(userRepository.findAllById(guardIds)).thenReturn(guards);
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.fa26se040.icss.dto.guard.WizardGenerateShiftsRequest request = com.fa26se040.icss.dto.guard.WizardGenerateShiftsRequest.builder()
                .startDate(startDate)
                .endDate(endDate)
                .memberGuardIds(guardIds)
                .morningDemand(3)
                .afternoonDemand(4)
                .nightDemand(2)
                .hasSundayCustom(true)
                .sundayMorningDemand(2)
                .sundayAfternoonDemand(2)
                .sundayNightDemand(2)
                .build();

        List<GuardShiftDto> results = guardScheduleService.generateShiftsFromWizard(request);

        // Tổng số ca sinh ra phải đúng bằng 60 (6 ngày x 9 + 1 ngày x 6)
        assertEquals(60, results.size());

        // Đếm số ca của từng bảo vệ
        java.util.Map<UUID, Long> guardShiftCounts = results.stream()
                .collect(Collectors.groupingBy(GuardShiftDto::getGuardId, Collectors.counting()));

        assertEquals(12, guardShiftCounts.size());
        for (UUID gId : guardIds) {
            // Mỗi người phải được phân đúng 5 ca!
            assertEquals(5L, guardShiftCounts.get(gId).longValue(), "Bảo vệ " + gId + " phải có đúng 5 ca");
        }

        // Kiểm tra không có ai bị xếp > 1 ca trong cùng 1 ngày
        java.util.Map<String, Long> guardDayCounts = results.stream()
                .collect(Collectors.groupingBy(s -> s.getGuardId() + "_" + s.getShiftDate(), Collectors.counting()));
        for (Long count : guardDayCounts.values()) {
            assertEquals(1L, count.longValue(), "Mỗi bảo vệ chỉ được trực tối đa 1 ca/ngày");
        }
    }

    @Test
    @DisplayName("Tự động sinh ca - Đảm bảo ca Đêm luôn có người trực thay (người nghỉ hôm nay không bị dồn hết vào sáng mai)")
    void testGenerateShiftsFromWizard_NightShiftHasAvailableSubstitutes() {
        LocalDate startDate = LocalDate.of(2026, 9, 28); // Monday
        LocalDate endDate = LocalDate.of(2026, 10, 4);   // Sunday

        // 15 guards (tương đương Đội 2)
        List<User> guards = new java.util.ArrayList<>();
        List<UUID> guardIds = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            UUID gId = UUID.randomUUID();
            guardIds.add(gId);
            guards.add(User.builder()
                    .id(gId)
                    .fullName("Guard " + i)
                    .email("guard" + i + "@fpt.edu.vn")
                    .role(Role.GUARD)
                    .build());
        }

        when(userRepository.findAllById(guardIds)).thenReturn(guards);
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(invocation -> invocation.getArgument(0));

        com.fa26se040.icss.dto.guard.WizardGenerateShiftsRequest request = com.fa26se040.icss.dto.guard.WizardGenerateShiftsRequest.builder()
                .startDate(startDate)
                .endDate(endDate)
                .memberGuardIds(guardIds)
                .morningDemand(4)
                .afternoonDemand(4)
                .nightDemand(3)
                .hasSundayCustom(true)
                .sundayMorningDemand(2)
                .sundayAfternoonDemand(3)
                .sundayNightDemand(2)
                .build();

        List<GuardShiftDto> results = guardScheduleService.generateShiftsFromWizard(request);
        assertFalse(results.isEmpty());

        // Xét ngày Thứ 3 (Tuesday, 2026-09-29)
        LocalDate tuesday = startDate.plusDays(1);
        LocalDate wednesday = startDate.plusDays(2);

        // Danh sách bảo vệ trực Thứ 3
        java.util.Set<UUID> tuesdayWorkingGuards = results.stream()
                .filter(s -> s.getShiftDate().equals(tuesday))
                .map(GuardShiftDto::getGuardId)
                .collect(Collectors.toSet());

        // Những bảo vệ ĐƯỢC NGHỈ vào Thứ 3:
        List<UUID> tuesdayRestingGuards = guardIds.stream()
                .filter(id -> !tuesdayWorkingGuards.contains(id))
                .collect(Collectors.toList());

        assertFalse(tuesdayRestingGuards.isEmpty(), "Thứ 3 phải có người được nghỉ luân phiên");

        // Trong số những người nghỉ Thứ 3, tìm xem có ai KHÔNG bị xếp ca Sáng vào Thứ 4 (để có thể thế ca Đêm Thứ 3)
        java.util.Set<UUID> wednesdayMorningGuards = results.stream()
                .filter(s -> s.getShiftDate().equals(wednesday) && s.getShiftType() == ShiftType.SHIFT_MORNING)
                .map(GuardShiftDto::getGuardId)
                .collect(Collectors.toSet());

        List<UUID> eligibleSubstitutesForTuesdayNight = tuesdayRestingGuards.stream()
                .filter(id -> !wednesdayMorningGuards.contains(id))
                .collect(Collectors.toList());

        // PHẢI CÓ ÍT NHẤT 1 BẢO VỆ ĐỦ ĐIỀU KIỆN TRỰC THAY CA ĐÊM THỨ 3
        assertTrue(eligibleSubstitutesForTuesdayNight.size() >= 1,
                "Phải có ít nhất 1 người nghỉ Thứ 3 mà sáng Thứ 4 không trực để có thể thế ca Đêm Thứ 3!");
    }
}
