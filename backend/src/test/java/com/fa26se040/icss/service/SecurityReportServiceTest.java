package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.report.SecurityReportSummaryDto;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.repository.AccessLogStatsQueryRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.GuestVisitRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SecurityReportServiceTest {

    @Mock
    private SecurityIncidentRepository securityIncidentRepository;

    @Mock
    private AccessLogStatsQueryRepository accessLogStatsQueryRepository;

    @Mock
    private GuardShiftRepository guardShiftRepository;

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private GuestVisitRepository guestVisitRepository;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private SecurityReportService securityReportService;

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private LocalDate startDate;
    private LocalDate endDate;

    @BeforeEach
    void setUp() {
        startDate = LocalDate.of(2026, 11, 1);
        endDate = LocalDate.of(2026, 11, 2);
    }

    @Test
    @DisplayName("generateReport - Tổng hợp chính xác toàn bộ dữ liệu thực tế từ các Repository")
    void generateReport_WithFullData_Success() {
        // 1. Mock Admin User
        User admin = User.builder()
                .id(UUID.randomUUID())
                .fullName("Trưởng Ban An Ninh")
                .email("admin@fpt.edu.vn")
                .build();
        when(userRepository.findByEmail("admin@fpt.edu.vn")).thenReturn(Optional.of(admin));

        // 2. Mock Guards & Areas
        User guard1 = User.builder()
                .id(UUID.randomUUID())
                .fullName("Nguyễn Văn An (Bảo Vệ)")
                .userCode("NV-BV01")
                .build();

        Area areaLab = Area.builder()
                .id(UUID.randomUUID())
                .name("Phòng Thí nghiệm AI")
                .build();

        OffsetDateTime t1 = startDate.atTime(8, 0).atZone(VN_ZONE).toOffsetDateTime();
        OffsetDateTime tClaimed1 = t1.plusSeconds(30);
        OffsetDateTime tResolved1 = tClaimed1.plusSeconds(120);

        SecurityIncident inc1 = SecurityIncident.builder()
                .id(UUID.randomUUID())
                .detectedAt(t1)
                .claimedAt(tClaimed1)
                .resolvedAt(tResolved1)
                .cameraCode("CAM-01")
                .area(areaLab)
                .building("Tòa Alpha")
                .eventType("UNAUTHORIZED_ENTRY")
                .severity(IncidentSeverity.HIGH)
                .status(IncidentStatus.RESOLVED_VERIFIED)
                .outcome(IncidentOutcome.VERIFIED)
                .resolutionCategory(IncidentResolutionCategory.REPORT_FILED)
                .resolutionNotes("Đã lập biên bản vi phạm")
                .claimedBy(guard1)
                .resolvedBy(guard1)
                .build();

        SecurityIncident inc2 = SecurityIncident.builder()
                .id(UUID.randomUUID())
                .detectedAt(t1.plusHours(2))
                .cameraCode("CAM-02")
                .building("Tòa Beta")
                .eventType("UNKNOWN_PERSON")
                .severity(IncidentSeverity.MEDIUM)
                .status(IncidentStatus.NEW)
                .build();

        when(securityIncidentRepository.findIncidentsInPeriod(any(), any()))
                .thenReturn(List.of(inc1, inc2));

        // 3. Mock Access Logs
        AccessLogStatsQueryRepository.DayResultCount logRow1 = new AccessLogStatsQueryRepository.DayResultCount() {
            @Override
            public String getDay() { return "2026-11-01"; }
            @Override
            public String getAccessResult() { return "AUTHORIZED_ACCESS"; }
            @Override
            public Long getTotal() { return 150L; }
        };

        AccessLogStatsQueryRepository.DayResultCount logRow2 = new AccessLogStatsQueryRepository.DayResultCount() {
            @Override
            public String getDay() { return "2026-11-01"; }
            @Override
            public String getAccessResult() { return "UNAUTHORIZED_ACCESS"; }
            @Override
            public Long getTotal() { return 10L; }
        };

        when(accessLogStatsQueryRepository.countByDayAndResult(any(), any(), eq(VN_ZONE.getId())))
                .thenReturn(List.of(logRow1, logRow2));

        // 4. Mock Guard Shifts
        GuardShift shift1 = GuardShift.builder().status(ShiftStatus.COMPLETED).shiftDate(startDate).build();
        GuardShift shift2 = GuardShift.builder().status(ShiftStatus.SCHEDULED).shiftDate(endDate).build();
        when(guardShiftRepository.findByShiftDateBetween(startDate, endDate))
                .thenReturn(List.of(shift1, shift2));

        // 5. Mock Cameras
        Camera cam1 = Camera.builder().operationalStatus(OperationalStatus.ONLINE).build();
        Camera cam2 = Camera.builder().operationalStatus(OperationalStatus.OFFLINE).build();
        when(cameraRepository.findAll()).thenReturn(List.of(cam1, cam2));

        // 6. Mock Guest Visits
        when(guestVisitRepository.countVisitsInPeriod(any(), any())).thenReturn(8L);
        when(guestVisitRepository.countVisitsInPeriodWithStatuses(any(), any(), any())).thenReturn(7L);

        // ACT
        SecurityReportSummaryDto report = securityReportService.generateReport(startDate, endDate, "admin@fpt.edu.vn");

        // ASSERT
        assertThat(report).isNotNull();
        assertThat(report.generatedByName()).isEqualTo("Trưởng Ban An Ninh");
        assertThat(report.generatedByEmail()).isEqualTo("admin@fpt.edu.vn");

        // KPI checks
        assertThat(report.totalIncidents()).isEqualTo(2);
        assertThat(report.verifiedIncidents()).isEqualTo(1);
        assertThat(report.dismissedIncidents()).isEqualTo(0);
        assertThat(report.pendingIncidents()).isEqualTo(1);
        assertThat(report.avgResponseTimeSeconds()).isEqualTo(30);
        assertThat(report.avgResolutionTimeSeconds()).isEqualTo(120);

        // Access logs checks
        assertThat(report.totalAccessScans()).isEqualTo(160);
        assertThat(report.authorizedAccessCount()).isEqualTo(150);
        assertThat(report.violationAccessCount()).isEqualTo(10);
        assertThat(report.dailyAccessTrends()).hasSize(2); // 2026-11-01 and 2026-11-02

        // Camera checks
        assertThat(report.activeCamerasCount()).isEqualTo(2);
        assertThat(report.onlineCamerasCount()).isEqualTo(1);

        // Shift checks
        assertThat(report.scheduledShiftsCount()).isEqualTo(2);
        assertThat(report.completedShiftsCount()).isEqualTo(1);
        assertThat(report.shiftFulfillmentRate()).isEqualTo(0.5);

        // Guest visits
        assertThat(report.totalGuestVisits()).isEqualTo(8);
        assertThat(report.approvedGuestVisits()).isEqualTo(7);

        // Top guards
        assertThat(report.topGuards()).hasSize(1);
        assertThat(report.topGuards().get(0).fullName()).isEqualTo("Nguyễn Văn An (Bảo Vệ)");
        assertThat(report.topGuards().get(0).resolvedCount()).isEqualTo(1);

        // Top incident areas
        assertThat(report.topIncidentAreas()).hasSize(2);
    }

    @Test
    @DisplayName("generateReport - Không có dữ liệu trong khoảng thời gian (Empty Period) - Không bị lỗi chia cho 0 hay NullPointerException")
    void generateReport_WithEmptyData_HandlesGracefully() {
        when(securityIncidentRepository.findIncidentsInPeriod(any(), any())).thenReturn(List.of());
        when(accessLogStatsQueryRepository.countByDayAndResult(any(), any(), any())).thenReturn(List.of());
        when(guardShiftRepository.findByShiftDateBetween(any(), any())).thenReturn(List.of());
        when(cameraRepository.findAll()).thenReturn(List.of());
        when(guestVisitRepository.countVisitsInPeriod(any(), any())).thenReturn(0L);
        when(guestVisitRepository.countVisitsInPeriodWithStatuses(any(), any(), any())).thenReturn(0L);

        SecurityReportSummaryDto report = securityReportService.generateReport(startDate, endDate, null);

        assertThat(report).isNotNull();
        assertThat(report.totalIncidents()).isZero();
        assertThat(report.verifiedIncidents()).isZero();
        assertThat(report.dismissedIncidents()).isZero();
        assertThat(report.pendingIncidents()).isZero();
        assertThat(report.falseAlarmRate()).isEqualTo(0.0);
        assertThat(report.avgResponseTimeSeconds()).isNull();
        assertThat(report.avgResolutionTimeSeconds()).isNull();
        assertThat(report.totalAccessScans()).isZero();
        assertThat(report.authorizedAccessCount()).isZero();
        assertThat(report.violationAccessCount()).isZero();
        assertThat(report.scheduledShiftsCount()).isZero();
        assertThat(report.completedShiftsCount()).isZero();
        assertThat(report.shiftFulfillmentRate()).isEqualTo(1.0);
        assertThat(report.totalGuestVisits()).isZero();
        assertThat(report.approvedGuestVisits()).isZero();
        assertThat(report.topIncidentAreas()).isEmpty();
        assertThat(report.topGuards()).isEmpty();
        assertThat(report.significantIncidents()).isEmpty();
    }
}
