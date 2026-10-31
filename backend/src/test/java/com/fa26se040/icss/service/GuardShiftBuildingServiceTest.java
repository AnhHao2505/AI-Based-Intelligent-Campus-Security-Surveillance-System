package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.guard.BuildingRotationReportResponse;
import com.fa26se040.icss.dto.guard.GuardShiftCreateRequest;
import com.fa26se040.icss.dto.guard.GuardShiftDto;
import com.fa26se040.icss.dto.guard.GuardShiftUpdateRequest;
import com.fa26se040.icss.dto.guard.WizardGenerateShiftsRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.DutyPostType;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.GuardTeam;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.DutyPostTypeRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.GuardShiftRequestRepository;
import com.fa26se040.icss.repository.GuardTeamDispatchRepository;
import com.fa26se040.icss.repository.GuardTeamRepository;
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
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuardShiftBuildingServiceTest {

    @Mock
    private GuardShiftRepository shiftRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private GuardTeamRepository teamRepository;

    @Mock
    private GuardShiftRequestRepository shiftRequestRepository;

    @Mock
    private GuardTeamDispatchRepository dispatchRepository;

    @Mock
    private DutyPostTypeRepository dutyPostTypeRepository;

    @Mock
    private BuildingRepository buildingRepository;

    @InjectMocks
    private GuardScheduleService guardScheduleService;

    private Building buildingAlpha;
    private Building buildingBeta;
    private GuardTeam teamAlpha;
    private GuardTeam teamBeta;
    private User guardAlpha;
    private Floor floorAlpha;
    private Area areaAlpha;
    private Floor floorBeta;
    private Area areaBeta;

    @BeforeEach
    void setUp() {
        buildingAlpha = Building.builder()
                .id(UUID.randomUUID())
                .name("Tòa Alpha")
                .build();

        buildingBeta = Building.builder()
                .id(UUID.randomUUID())
                .name("Tòa Beta")
                .build();

        teamAlpha = GuardTeam.builder()
                .id(UUID.randomUUID())
                .teamName("Đội Alpha")
                .building(buildingAlpha)
                .build();

        teamBeta = GuardTeam.builder()
                .id(UUID.randomUUID())
                .teamName("Đội Beta")
                .building(buildingBeta)
                .build();

        guardAlpha = User.builder()
                .id(UUID.randomUUID())
                .fullName("Nguyễn Văn An")
                .userCode("SEC-001")
                .role(Role.GUARD)
                .team(teamAlpha)
                .build();

        floorAlpha = Floor.builder()
                .id(UUID.randomUUID())
                .building(buildingAlpha)
                .name("Tầng G")
                .build();

        areaAlpha = Area.builder()
                .id(UUID.randomUUID())
                .name("Sảnh Alpha")
                .floorEntity(floorAlpha)
                .building("Tòa Alpha")
                .build();

        floorBeta = Floor.builder()
                .id(UUID.randomUUID())
                .building(buildingBeta)
                .name("Tầng 1")
                .build();

        areaBeta = Area.builder()
                .id(UUID.randomUUID())
                .name("Sảnh Beta")
                .floorEntity(floorBeta)
                .building("Tòa Beta")
                .build();
    }

    @Test
    @DisplayName("P1b (BR-GB-02): Tạo ca tay không truyền buildingId -> lấy tòa theo đội của bảo vệ")
    void testCreateShift_DefaultBuildingFromTeam() {
        GuardShiftCreateRequest request = GuardShiftCreateRequest.builder()
                .guardId(guardAlpha.getId())
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .areaId(areaAlpha.getId())
                .build();

        when(userRepository.findById(guardAlpha.getId())).thenReturn(Optional.of(guardAlpha));
        when(shiftRepository.existsByGuardIdAndShiftDateAndStartTime(any(), any(), any())).thenReturn(false);
        when(areaRepository.findById(areaAlpha.getId())).thenReturn(Optional.of(areaAlpha));
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(inv -> {
            GuardShift s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        GuardShiftDto dto = guardScheduleService.createShift(request);

        assertNotNull(dto);
        assertEquals(buildingAlpha.getId(), dto.getBuildingId());
        assertEquals("Tòa Alpha", dto.getBuildingName());
        assertFalse(dto.getCrossBuilding());
    }

    @Test
    @DisplayName("P1b (BR-GB-03): Tạo ca luân chuyển khác tòa nhà đội mà KHÔNG có crossBuilding=true -> từ chối")
    void testCreateShift_CrossBuildingWithoutConfirmation_Throws() {
        GuardShiftCreateRequest request = GuardShiftCreateRequest.builder()
                .guardId(guardAlpha.getId())
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .buildingId(buildingBeta.getId())
                .crossBuilding(false)
                .build();

        when(userRepository.findById(guardAlpha.getId())).thenReturn(Optional.of(guardAlpha));
        when(shiftRepository.existsByGuardIdAndShiftDateAndStartTime(any(), any(), any())).thenReturn(false);
        when(buildingRepository.findById(buildingBeta.getId())).thenReturn(Optional.of(buildingBeta));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guardScheduleService.createShift(request));
        assertTrue(ex.getMessage().contains("Cần xác nhận luân chuyển bảo vệ (crossBuilding = true)"));
    }

    @Test
    @DisplayName("P1b (BR-GB-03): Tạo ca luân chuyển khác tòa nhà đội CÓ crossBuilding=true -> thành công")
    void testCreateShift_CrossBuildingWithConfirmation_Success() {
        GuardShiftCreateRequest request = GuardShiftCreateRequest.builder()
                .guardId(guardAlpha.getId())
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .buildingId(buildingBeta.getId())
                .crossBuilding(true)
                .crossBuildingReason("Làm quen địa hình Tòa Beta")
                .build();

        when(userRepository.findById(guardAlpha.getId())).thenReturn(Optional.of(guardAlpha));
        when(shiftRepository.existsByGuardIdAndShiftDateAndStartTime(any(), any(), any())).thenReturn(false);
        when(buildingRepository.findById(buildingBeta.getId())).thenReturn(Optional.of(buildingBeta));
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(inv -> {
            GuardShift s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        GuardShiftDto dto = guardScheduleService.createShift(request);

        assertNotNull(dto);
        assertEquals(buildingBeta.getId(), dto.getBuildingId());
        assertEquals("Tòa Beta", dto.getBuildingName());
        assertTrue(dto.getCrossBuilding());
        assertEquals("Làm quen địa hình Tòa Beta", dto.getCrossBuildingReason());
    }

    @Test
    @DisplayName("P1b (BR-GB-04): Gán khu vực không thuộc tòa nhà ca trực -> từ chối")
    void testCreateShift_AreaNotBelongToShiftBuilding_Throws() {
        // Ca ở Tòa Alpha nhưng gán khu vực Sảnh Beta
        GuardShiftCreateRequest request = GuardShiftCreateRequest.builder()
                .guardId(guardAlpha.getId())
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .buildingId(buildingAlpha.getId())
                .areaId(areaBeta.getId())
                .build();

        when(userRepository.findById(guardAlpha.getId())).thenReturn(Optional.of(guardAlpha));
        when(shiftRepository.existsByGuardIdAndShiftDateAndStartTime(any(), any(), any())).thenReturn(false);
        when(buildingRepository.findById(buildingAlpha.getId())).thenReturn(Optional.of(buildingAlpha));
        when(areaRepository.findById(areaBeta.getId())).thenReturn(Optional.of(areaBeta));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guardScheduleService.createShift(request));
        assertTrue(ex.getMessage().contains("không thuộc tòa nhà của ca"));
    }

    @Test
    @DisplayName("P1b (4.2): Sửa ca không gửi buildingId -> giữ nguyên tòa hiện tại của ca")
    void testUpdateShift_KeepExistingBuildingWhenNull() {
        UUID shiftId = UUID.randomUUID();
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardAlpha)
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .building(buildingAlpha)
                .status(ShiftStatus.SCHEDULED)
                .build();

        GuardShiftUpdateRequest updateReq = GuardShiftUpdateRequest.builder()
                .guardId(guardAlpha.getId())
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .buildingId(null) // Không gửi buildingId
                .build();

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findById(guardAlpha.getId())).thenReturn(Optional.of(guardAlpha));
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(inv -> inv.getArgument(0));

        GuardShiftDto dto = guardScheduleService.updateShift(shiftId, updateReq);

        assertNotNull(dto);
        assertEquals(buildingAlpha.getId(), dto.getBuildingId());
        assertEquals("Tòa Alpha", dto.getBuildingName());
    }

    @Test
    @DisplayName("P1b (4.2): Sửa ca đổi sang bảo vệ đội khác dẫn tới khác tòa mà không xác nhận -> từ chối")
    void testUpdateShift_ChangeGuardToDifferentTeamBuildingWithoutCrossBuilding_Throws() {
        UUID shiftId = UUID.randomUUID();
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardAlpha) // Đội Alpha, Tòa Alpha
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .building(buildingAlpha)
                .status(ShiftStatus.SCHEDULED)
                .build();

        User guardBetaUser = User.builder()
                .id(UUID.randomUUID())
                .fullName("Trần Văn Beta")
                .role(Role.GUARD)
                .team(teamBeta) // Đội Beta, Tòa Beta
                .build();

        GuardShiftUpdateRequest updateReq = GuardShiftUpdateRequest.builder()
                .guardId(guardBetaUser.getId())
                .shiftDate(LocalDate.now())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .crossBuilding(false)
                .build();

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findById(guardBetaUser.getId())).thenReturn(Optional.of(guardBetaUser));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guardScheduleService.updateShift(shiftId, updateReq));
        assertTrue(ex.getMessage().contains("Cần xác nhận luân chuyển bảo vệ (crossBuilding = true)"));
    }

    @Test
    @DisplayName("P1b (4.2): Wizard lập lịch khác tòa đội mà KHÔNG confirmCrossBuilding -> từ chối")
    void testWizard_CrossBuildingWithoutConfirmation_Throws() {
        WizardGenerateShiftsRequest req = WizardGenerateShiftsRequest.builder()
                .teamId(teamAlpha.getId())
                .memberGuardIds(List.of(guardAlpha.getId()))
                .startDate(LocalDate.now())
                .morningDemand(2)
                .afternoonDemand(2)
                .nightDemand(2)
                .building("Tòa Beta")
                .confirmCrossBuilding(false)
                .build();

        when(teamRepository.findById(teamAlpha.getId())).thenReturn(Optional.of(teamAlpha));
        when(userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(teamAlpha.getId()))
                .thenReturn(List.of(guardAlpha));
        // Wizard ghi lại team.description rồi gán team = teamRepository.save(team); repository thật luôn trả entity
        when(teamRepository.save(any(GuardTeam.class))).thenAnswer(inv -> inv.getArgument(0));
        when(buildingRepository.findByNameIgnoreCase("Tòa Beta")).thenReturn(Optional.of(buildingBeta));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guardScheduleService.generateShiftsFromWizard(req));
        assertTrue(ex.getMessage().contains("Cần xác nhận luân chuyển tòa nhà (confirmCrossBuilding = true)"));
    }

    @Test
    @DisplayName("P1b (4.6): Báo cáo luân chuyển tòa nhà - Tính đúng ca ngoài tòa đội và cờ hasNeverRotated")
    void testGetBuildingRotationReport() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);

        GuardShift shift1 = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guardAlpha)
                .shiftDate(LocalDate.of(2026, 11, 2))
                .status(ShiftStatus.COMPLETED)
                .building(buildingAlpha) // Tòa nhà đội
                .build();

        GuardShift shift2 = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guardAlpha)
                .shiftDate(LocalDate.of(2026, 11, 5))
                .status(ShiftStatus.COMPLETED)
                .building(buildingBeta) // Ngoài tòa đội
                .isCrossBuilding(true)
                .build();

        when(userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(teamAlpha.getId()))
                .thenReturn(List.of(guardAlpha));
        when(shiftRepository.findShiftsForRotationReport(from, to, teamAlpha.getId()))
                .thenReturn(List.of(shift1, shift2));
        when(buildingRepository.findByIsActiveTrueOrderByNameAsc())
                .thenReturn(List.of(buildingAlpha, buildingBeta));

        BuildingRotationReportResponse report = guardScheduleService.getBuildingRotationReport(from, to, teamAlpha.getId());

        assertNotNull(report);
        assertEquals(1, report.totalGuards());
        assertEquals(1, report.guardsWithCrossBuildingShifts());
        assertEquals(1, report.items().size());

        var item = report.items().get(0);
        assertEquals(guardAlpha.getId(), item.guardId());
        assertEquals(2, item.totalShifts());
        assertEquals(1, item.outsideTeamBuildingShifts());
        assertFalse(item.hasNeverRotated());
        assertEquals(1, item.shiftsByBuildingName().get("Tòa Alpha"));
        assertEquals(1, item.shiftsByBuildingName().get("Tòa Beta"));
    }

    private GuardShift dispatchShiftAlphaGuardInBeta(UUID shiftId) {
        // Ca điều chuyển: bảo vệ đội Alpha trực ở Tòa Beta, khu vực dự phòng nằm ở Tòa Alpha
        return GuardShift.builder()
                .id(shiftId)
                .guard(guardAlpha)
                .shiftDate(LocalDate.now().plusDays(1))
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .area(areaAlpha)
                .building(buildingBeta)
                .isCrossBuilding(true)
                .crossBuildingReason("Điều động sang Đội Beta: Sự kiện")
                .isOvertime(true)
                .status(ShiftStatus.SCHEDULED)
                .build();
    }

    private GuardShiftUpdateRequest.GuardShiftUpdateRequestBuilder legacyUpdateRequest(GuardShift shift) {
        // Request kiểu client cũ: không có buildingId, crossBuilding, crossBuildingReason
        return GuardShiftUpdateRequest.builder()
                .guardId(shift.getGuard().getId())
                .shiftDate(shift.getShiftDate())
                .shiftType(shift.getShiftType())
                .startTime(shift.getStartTime())
                .endTime(shift.getEndTime())
                .notes("Ghi chú mới");
    }

    @Test
    @DisplayName("N1: Sửa ca điều chuyển, request không gửi field mới -> không lỗi, giữ toà, cờ và lý do")
    void testUpdateShift_DispatchShiftWithoutNewFields_KeepsCrossFlagAndReason() {
        UUID shiftId = UUID.randomUUID();
        GuardShift shift = dispatchShiftAlphaGuardInBeta(shiftId);

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findById(guardAlpha.getId())).thenReturn(Optional.of(guardAlpha));
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(inv -> inv.getArgument(0));

        GuardShiftDto dto = guardScheduleService.updateShift(shiftId, legacyUpdateRequest(shift).build());

        assertEquals(buildingBeta.getId(), dto.getBuildingId());
        assertTrue(dto.getCrossBuilding());
        assertEquals("Điều động sang Đội Beta: Sự kiện", dto.getCrossBuildingReason());
        assertTrue(shift.getIsCrossBuilding());
        assertEquals("Ghi chú mới", shift.getNotes());
    }

    @Test
    @DisplayName("N1: Sửa ca điều chuyển, gửi lại khu vực hiện tại (khác toà của ca) -> không lỗi, giữ khu vực và cờ")
    void testUpdateShift_DispatchShiftResendingCurrentArea_NoAreaCheck() {
        UUID shiftId = UUID.randomUUID();
        GuardShift shift = dispatchShiftAlphaGuardInBeta(shiftId);

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findById(guardAlpha.getId())).thenReturn(Optional.of(guardAlpha));
        when(areaRepository.findById(areaAlpha.getId())).thenReturn(Optional.of(areaAlpha));
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(inv -> inv.getArgument(0));

        GuardShiftUpdateRequest req = legacyUpdateRequest(shift)
                .areaId(areaAlpha.getId())
                .buildingId(buildingBeta.getId()) // gửi đúng toà hiện tại = không đổi toà
                .build();
        GuardShiftDto dto = guardScheduleService.updateShift(shiftId, req);

        assertEquals(areaAlpha.getId(), dto.getAreaId());
        assertTrue(dto.getCrossBuilding());
        assertEquals("Điều động sang Đội Beta: Sự kiện", dto.getCrossBuildingReason());
        verify(buildingRepository, never()).findById(any());
    }

    @Test
    @DisplayName("N1: Toà và bảo vệ không đổi, request gửi lý do mới -> cập nhật lý do, giữ cờ")
    void testUpdateShift_SameBuildingAndGuard_UpdatesReasonOnly() {
        UUID shiftId = UUID.randomUUID();
        GuardShift shift = dispatchShiftAlphaGuardInBeta(shiftId);

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findById(guardAlpha.getId())).thenReturn(Optional.of(guardAlpha));
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(inv -> inv.getArgument(0));

        GuardShiftDto dto = guardScheduleService.updateShift(shiftId,
                legacyUpdateRequest(shift).crossBuildingReason("  Làm quen địa hình Tòa Beta  ").build());

        assertTrue(dto.getCrossBuilding());
        assertEquals("Làm quen địa hình Tòa Beta", dto.getCrossBuildingReason());
    }

    @Test
    @DisplayName("N1: Đổi toà của ca về toà của đội -> bỏ cờ luân chuyển và lý do, không cần xác nhận")
    void testUpdateShift_ChangeBuildingBackToTeamBuilding_ClearsCrossFlag() {
        UUID shiftId = UUID.randomUUID();
        GuardShift shift = dispatchShiftAlphaGuardInBeta(shiftId);

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findById(guardAlpha.getId())).thenReturn(Optional.of(guardAlpha));
        when(buildingRepository.findById(buildingAlpha.getId())).thenReturn(Optional.of(buildingAlpha));
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(inv -> inv.getArgument(0));

        GuardShiftDto dto = guardScheduleService.updateShift(shiftId,
                legacyUpdateRequest(shift).buildingId(buildingAlpha.getId()).build());

        assertEquals(buildingAlpha.getId(), dto.getBuildingId());
        assertFalse(dto.getCrossBuilding());
        assertNull(dto.getCrossBuildingReason());
    }

    @Test
    @DisplayName("N1: Đổi toà của ca sang toà khác toà đội mà không crossBuilding -> từ chối")
    void testUpdateShift_ChangeBuildingToOtherWithoutConfirmation_Throws() {
        UUID shiftId = UUID.randomUUID();
        GuardShift shift = GuardShift.builder()
                .id(shiftId)
                .guard(guardAlpha)
                .shiftDate(LocalDate.now().plusDays(1))
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .building(buildingAlpha)
                .status(ShiftStatus.SCHEDULED)
                .build();

        when(shiftRepository.findById(shiftId)).thenReturn(Optional.of(shift));
        when(userRepository.findById(guardAlpha.getId())).thenReturn(Optional.of(guardAlpha));
        when(buildingRepository.findById(buildingBeta.getId())).thenReturn(Optional.of(buildingBeta));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> guardScheduleService.updateShift(shiftId,
                        legacyUpdateRequest(shift).buildingId(buildingBeta.getId()).build()));
        assertTrue(ex.getMessage().contains("crossBuilding = true"));
        verify(shiftRepository, never()).save(any());
    }

    @Test
    @DisplayName("M4: Field building giữ cách tính cũ (khu vực -> team.description); buildingName theo toà của ca")
    void testMapShiftToDto_LegacyBuildingFieldUnchanged() {
        UUID shiftId = UUID.randomUUID();
        teamAlpha.setDescription("Tòa Alpha (mô tả)");
        GuardShift noArea = GuardShift.builder()
                .id(shiftId)
                .guard(guardAlpha)
                .shiftDate(LocalDate.now().plusDays(1))
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .building(buildingBeta)
                .isCrossBuilding(true)
                .status(ShiftStatus.COMPLETED)
                .build();
        Area areaStringOnly = Area.builder().id(UUID.randomUUID()).name("Cổng phụ").building("Khu cũ").build();
        GuardShift withArea = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guardAlpha)
                .shiftDate(LocalDate.now().plusDays(2))
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .area(areaStringOnly)
                .status(ShiftStatus.COMPLETED)
                .build();

        when(shiftRepository.findShifts(any(), any(), any(), any(), any())).thenReturn(List.of(noArea, withArea));
        when(dutyPostTypeRepository.findAll()).thenReturn(List.of(
                DutyPostType.builder().code("PATROL").label("Tuần tra").build()));

        List<GuardShiftDto> dtos = guardScheduleService.getShifts(LocalDate.now(), LocalDate.now().plusDays(3), null, null, null);

        // Ca không khu vực: building = team.description như bản gốc, buildingName = toà của ca
        assertEquals("Tòa Alpha (mô tả)", dtos.get(0).getBuilding());
        assertEquals("Tòa Beta", dtos.get(0).getBuildingName());
        assertEquals(buildingBeta.getId(), dtos.get(0).getBuildingId());
        // Ca có khu vực chỉ có chuỗi toà: building = area.building như bản gốc, buildingName = toà của đội
        assertEquals("Khu cũ", dtos.get(1).getBuilding());
        assertEquals("Tòa Alpha", dtos.get(1).getBuildingName());
        // N2: nhãn vị trí trực lấy từ danh mục nạp một lần cho cả danh sách
        assertEquals("Tuần tra", dtos.get(0).getDutyPostLabel());
        assertEquals("Tuần tra", dtos.get(1).getDutyPostLabel());
        verify(dutyPostTypeRepository, times(1)).findAll();
    }

    @Test
    @DisplayName("N3: Ca không xác định được toà không tính vào ngoài toà đội, đếm riêng")
    void testGetBuildingRotationReport_UnknownBuildingNotCountedAsOutside() {
        LocalDate from = LocalDate.of(2026, 11, 1);
        LocalDate to = LocalDate.of(2026, 11, 30);

        Area areaNoFloor = Area.builder().id(UUID.randomUUID()).name("Bãi xe").building("Tòa Beta").build();
        GuardShift unknownWithArea = GuardShift.builder()
                .id(UUID.randomUUID()).guard(guardAlpha).shiftDate(LocalDate.of(2026, 11, 3))
                .status(ShiftStatus.COMPLETED).area(areaNoFloor).build();
        GuardShift unknownNoArea = GuardShift.builder()
                .id(UUID.randomUUID()).guard(guardAlpha).shiftDate(LocalDate.of(2026, 11, 4))
                .status(ShiftStatus.COMPLETED).build();
        GuardShift home = GuardShift.builder()
                .id(UUID.randomUUID()).guard(guardAlpha).shiftDate(LocalDate.of(2026, 11, 5))
                .status(ShiftStatus.COMPLETED).area(areaAlpha).build();

        when(userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(teamAlpha.getId()))
                .thenReturn(List.of(guardAlpha));
        when(shiftRepository.findShiftsForRotationReport(from, to, teamAlpha.getId()))
                .thenReturn(List.of(unknownWithArea, unknownNoArea, home));
        when(buildingRepository.findByIsActiveTrueOrderByNameAsc())
                .thenReturn(List.of(buildingAlpha, buildingBeta));

        BuildingRotationReportResponse report = guardScheduleService.getBuildingRotationReport(from, to, teamAlpha.getId());

        var item = report.items().get(0);
        assertEquals(3, item.totalShifts());
        assertEquals(0, item.outsideTeamBuildingShifts());
        assertEquals(2, item.unknownBuildingShifts());
        assertTrue(item.hasNeverRotated());
        assertEquals(0, report.guardsWithCrossBuildingShifts());
        assertEquals(2, item.shiftsByBuildingName().get("Chưa xác định"));
        assertEquals(1, item.shiftsByBuildingName().get("Tòa Alpha"));
    }

    private WizardGenerateShiftsRequest.WizardGenerateShiftsRequestBuilder crossWizardRequest() {
        return WizardGenerateShiftsRequest.builder()
                .teamId(teamAlpha.getId())
                .memberGuardIds(List.of(guardAlpha.getId()))
                .startDate(LocalDate.now().plusDays(7))
                .endDate(LocalDate.now().plusDays(8))
                .morningDemand(2)
                .afternoonDemand(0)
                .nightDemand(0)
                .building("Tòa Beta")
                .confirmCrossBuilding(true);
    }

    private void stubWizardTeamAlpha() {
        when(teamRepository.findById(teamAlpha.getId())).thenReturn(Optional.of(teamAlpha));
        when(userRepository.findByTeamIdAndDeletedAtIsNullAndIsActiveTrue(teamAlpha.getId()))
                .thenReturn(List.of(guardAlpha));
        when(teamRepository.save(any(GuardTeam.class))).thenAnswer(inv -> inv.getArgument(0));
        when(shiftRepository.save(any(GuardShift.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void assertCrossShiftsInBetaAndTeamStaysAlpha() {
        org.mockito.ArgumentCaptor<GuardShift> captor = org.mockito.ArgumentCaptor.forClass(GuardShift.class);
        verify(shiftRepository, atLeastOnce()).save(captor.capture());
        List<GuardShift> saved = captor.getAllValues();
        assertFalse(saved.isEmpty());
        for (GuardShift s : saved) {
            assertSame(buildingBeta, s.getBuilding());
            assertTrue(s.getIsCrossBuilding());
            assertNull(s.getCrossBuildingReason());
        }
        // Toà phụ trách (FK) của đội không đổi; description bị wizard ghi đè như hành vi cũ
        assertSame(buildingAlpha, teamAlpha.getBuilding());
        assertEquals("Tòa Beta", teamAlpha.getDescription());
    }

    @Test
    @DisplayName("F6: Wizard luân chuyển chỉ gửi tên toà đích -> ca ở Tòa Beta, cờ luân chuyển, lý do null; đội vẫn Alpha")
    void testWizard_CrossBuildingByNameOnly_ShiftsInTargetBuilding() {
        stubWizardTeamAlpha();
        when(buildingRepository.findByNameIgnoreCase("Tòa Beta")).thenReturn(Optional.of(buildingBeta));

        guardScheduleService.generateShiftsFromWizard(crossWizardRequest().build());

        assertCrossShiftsInBetaAndTeamStaysAlpha();
        verify(buildingRepository, never()).findById(any());
    }

    @Test
    @DisplayName("F6: Wizard luân chuyển gửi buildingId toà đích -> ca ở Tòa Beta, cờ luân chuyển, lý do null; đội vẫn Alpha")
    void testWizard_CrossBuildingWithTargetBuildingId_ShiftsInTargetBuilding() {
        stubWizardTeamAlpha();
        when(buildingRepository.findById(buildingBeta.getId())).thenReturn(Optional.of(buildingBeta));

        guardScheduleService.generateShiftsFromWizard(crossWizardRequest().buildingId(buildingBeta.getId()).build());

        assertCrossShiftsInBetaAndTeamStaysAlpha();
        verify(buildingRepository, never()).findByNameIgnoreCase(any());
    }
}
