package com.fa26se040.icss.service;

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
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
                .code("ALPHA-SR")
                .name("Phòng bảo vệ Tòa Alpha")
                .building("TOA_ALPHA")
                .build();

        gateArea = Area.builder()
                .id(UUID.randomUUID())
                .code("ALPHA-GATE")
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
        LocalTime futureTime = LocalTime.now().plusHours(2);
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardUser)
                .shiftDate(LocalDate.now())
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
        LocalTime pastTime = LocalTime.now().minusHours(1);
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardUser)
                .shiftDate(LocalDate.now())
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
}
