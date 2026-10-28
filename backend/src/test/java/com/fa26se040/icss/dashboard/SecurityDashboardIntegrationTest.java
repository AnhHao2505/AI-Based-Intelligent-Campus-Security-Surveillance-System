package com.fa26se040.icss.dashboard;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.dashboard.SecurityStatsResponse;
import com.fa26se040.icss.dto.dashboard.SecurityStatsResponse.AreaIncidentCount;
import com.fa26se040.icss.dto.dashboard.SecurityStatsResponse.DailyAccessResults;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.SecurityStatsRange;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.CameraAccessLogRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fa26se040.icss.service.SecurityDashboardService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Dashboard thống kê an ninh (GET /api/dashboard/security-stats, SecurityDashboardService).
 * - Phân quyền: FM, ADMIN 200; GUARD, NORMAL_USER 403; không token 401; range sai 400.
 * - Số liệu: gọi thẳng service với mốc now cố định tháng 03/2047 — khoảng thời gian không test nào khác dùng,
 *   nên đếm toàn hệ thống vẫn chỉ ra dữ liệu của fixture trên DB test dùng chung.
 * @Transactional: fixture (người, khu, sự cố, nhật ký) rollback sau mỗi test.
 */
@Transactional
class SecurityDashboardIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneOffset VN = ZoneOffset.ofHours(7);
    /** Thứ Sáu 15/03/2047 10:00 giờ VN. LAST_7_DAYS = 09/03 00:00 → now; TODAY = 15/03 00:00 → now. */
    private static final OffsetDateTime NOW = OffsetDateTime.of(2047, 3, 15, 10, 0, 0, 0, VN)
            .truncatedTo(ChronoUnit.MICROS);

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SecurityDashboardService securityDashboardService;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private SecurityIncidentRepository securityIncidentRepository;
    @Autowired private CameraAccessLogRepository cameraAccessLogRepository;

    private String suffix;
    private Building building;
    private Floor floor;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        building = buildingRepository.findByNameIgnoreCase("Tòa thống kê an ninh")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa thống kê an ninh").build()));
        floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa thống kê an ninh", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
    }

    private User user(String tag, Role role) {
        return userRepository.save(User.builder()
                .userCode("DB-" + tag.toUpperCase() + "-" + suffix)
                .fullName("Thống kê " + tag + " " + suffix)
                .email("db-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(2)
                .isActive(true)
                .build());
    }

    private Area area(String tag) {
        return areaRepository.save(Area.builder()
                .name("DB khu " + tag + " " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
    }

    private void incident(Area a, IncidentStatus status, OffsetDateTime detectedAt) {
        securityIncidentRepository.saveAndFlush(SecurityIncident.builder()
                .cameraCode("DB-CAM-" + suffix)
                .area(a)
                .building(building.getName())
                .eventType("INTRUSION")
                .status(status)
                .detectedAt(detectedAt.truncatedTo(ChronoUnit.MICROS))
                .build());
    }

    private void incidents(Area a, IncidentStatus status, OffsetDateTime detectedAt, int n) {
        for (int i = 0; i < n; i++) {
            incident(a, status, detectedAt.plusMinutes(i));
        }
    }

    private void accessLog(Area a, AccessResult result, OffsetDateTime at) {
        cameraAccessLogRepository.saveAndFlush(CameraAccessLog.builder()
                .cameraCode("DB-CAM-" + suffix)
                .area(a)
                .userCode("UNKNOWN")
                .accessResult(result)
                .reasonDetails("Fixture " + result)
                .loggedAt(at.truncatedTo(ChronoUnit.MICROS))
                .build());
    }

    private static OffsetDateTime vn(int month, int day, int hour, int minute) {
        return OffsetDateTime.of(2047, month, day, hour, minute, 0, 0, VN);
    }

    private MvcResult call(MockHttpServletRequestBuilder b, User actor) throws Exception {
        if (actor != null) {
            b.header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor));
        }
        return mockMvc.perform(b).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ T-01, T-06: API

    @Test
    @DisplayName("T-01: FM, ADMIN 200; GUARD, NORMAL_USER 403; không token 401")
    void authorization() throws Exception {
        assertThat(call(get("/api/dashboard/security-stats"), user("fm", Role.FACILITY_MANAGER))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get("/api/dashboard/security-stats"), user("adm", Role.ADMIN))
                .getResponse().getStatus()).isEqualTo(200);
        assertThat(call(get("/api/dashboard/security-stats"), user("grd", Role.GUARD))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get("/api/dashboard/security-stats"), user("nu", Role.NORMAL_USER))
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get("/api/dashboard/security-stats"), null)
                .getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("Mặc định LAST_7_DAYS: đủ 4 trạng thái, 7 ngày, mỗi ngày đủ 5 kết quả")
    void defaultRange_shape() throws Exception {
        MvcResult r = call(get("/api/dashboard/security-stats"), user("fm", Role.FACILITY_MANAGER));

        JsonNode data = json(r).path("data");
        assertThat(data.path("range").asText()).isEqualTo("LAST_7_DAYS");
        assertThat(data.path("incidentsByStatus").size()).isEqualTo(4);
        for (IncidentStatus s : IncidentStatus.values()) {
            assertThat(data.path("incidentsByStatus").has(s.name())).isTrue();
        }
        assertThat(data.path("accessResultsByDay").size()).isEqualTo(7);
        JsonNode firstDay = data.path("accessResultsByDay").get(0);
        assertThat(firstDay.path("date").asText()).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(firstDay.path("counts").size()).isEqualTo(5);
        assertThat(data.has("dismissedRate")).isTrue();
        assertThat(data.path("topAreas").isArray()).isTrue();
    }

    @Test
    @DisplayName("T-06: range sai → 400 qua handler tham số sai chung; TODAY và LAST_30_DAYS → 200")
    void invalidRange_badRequest() throws Exception {
        User fm = user("fm", Role.FACILITY_MANAGER);

        MvcResult bad = call(get("/api/dashboard/security-stats").param("range", "LAST_YEAR"), fm);
        assertThat(bad.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(bad).path("message").asText()).contains("LAST_YEAR").contains("range");

        MvcResult today = call(get("/api/dashboard/security-stats").param("range", "TODAY"), fm);
        assertThat(today.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(today).path("data").path("accessResultsByDay").size()).isEqualTo(1);

        MvcResult month = call(get("/api/dashboard/security-stats").param("range", "LAST_30_DAYS"), fm);
        assertThat(month.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(month).path("data").path("accessResultsByDay").size()).isEqualTo(30);
    }

    // ------------------------------------------------------------------ T-02, T-03: sự cố theo trạng thái

    @Test
    @DisplayName("T-02: chỉ đếm sự cố có detectedAt trong khoảng, đủ 4 khoá, trạng thái thiếu = 0")
    void countByStatus_onlyInsideRange() {
        Area a = area("T02");
        // Trong LAST_7_DAYS (09/03 00:00 → 15/03 10:00)
        incident(a, IncidentStatus.NEW, vn(3, 9, 0, 0));          // đúng mốc đầu khoảng: tính
        incidents(a, IncidentStatus.NEW, vn(3, 15, 9, 0), 1);
        incidents(a, IncidentStatus.CLAIMED, vn(3, 12, 8, 0), 3);
        incidents(a, IncidentStatus.RESOLVED_VERIFIED, vn(3, 10, 14, 0), 2);
        incidents(a, IncidentStatus.RESOLVED_DISMISSED, vn(3, 14, 23, 0), 1);
        // Ngoài khoảng: trước 09/03 00:00 và sau now
        incident(a, IncidentStatus.NEW, vn(3, 8, 23, 59));
        incident(a, IncidentStatus.CLAIMED, vn(3, 1, 10, 0));
        incident(a, IncidentStatus.RESOLVED_VERIFIED, vn(3, 8, 12, 0));
        incident(a, IncidentStatus.RESOLVED_DISMISSED, vn(3, 15, 10, 1));

        SecurityStatsResponse week = securityDashboardService.getStats(SecurityStatsRange.LAST_7_DAYS, NOW);
        assertThat(week.from()).isEqualTo(vn(3, 9, 0, 0));
        assertThat(week.to()).isEqualTo(NOW);
        assertThat(week.incidentsByStatus()).containsExactly(
                Map.entry(IncidentStatus.NEW, 2L),
                Map.entry(IncidentStatus.CLAIMED, 3L),
                Map.entry(IncidentStatus.RESOLVED_VERIFIED, 2L),
                Map.entry(IncidentStatus.RESOLVED_DISMISSED, 1L));

        // TODAY: chỉ sự cố NEW lúc 09:00 hôm nay; 3 trạng thái còn lại vẫn có khoá với giá trị 0
        SecurityStatsResponse today = securityDashboardService.getStats(SecurityStatsRange.TODAY, NOW);
        assertThat(today.from()).isEqualTo(vn(3, 15, 0, 0));
        assertThat(today.incidentsByStatus()).containsExactly(
                Map.entry(IncidentStatus.NEW, 1L),
                Map.entry(IncidentStatus.CLAIMED, 0L),
                Map.entry(IncidentStatus.RESOLVED_VERIFIED, 0L),
                Map.entry(IncidentStatus.RESOLVED_DISMISSED, 0L));

        // LAST_30_DAYS (14/02 00:00 → now) lấy thêm 3 sự cố đầu tháng 3, vẫn bỏ sự cố sau now
        SecurityStatsResponse month = securityDashboardService.getStats(SecurityStatsRange.LAST_30_DAYS, NOW);
        assertThat(month.from()).isEqualTo(vn(2, 14, 0, 0));
        assertThat(month.incidentsByStatus()).containsExactly(
                Map.entry(IncidentStatus.NEW, 3L),
                Map.entry(IncidentStatus.CLAIMED, 4L),
                Map.entry(IncidentStatus.RESOLVED_VERIFIED, 3L),
                Map.entry(IncidentStatus.RESOLVED_DISMISSED, 1L));
    }

    @Test
    @DisplayName("T-03: 3 verified + 1 dismissed → dismissedRate 0.25, resolvedCount 4")
    void dismissedRate_quarter() {
        Area a = area("T03");
        incidents(a, IncidentStatus.RESOLVED_VERIFIED, vn(3, 13, 8, 0), 3);
        incident(a, IncidentStatus.RESOLVED_DISMISSED, vn(3, 14, 8, 0));
        incidents(a, IncidentStatus.NEW, vn(3, 14, 9, 0), 2); // chưa kết luận: không vào mẫu số

        SecurityStatsResponse stats = securityDashboardService.getStats(SecurityStatsRange.LAST_7_DAYS, NOW);
        assertThat(stats.resolvedCount()).isEqualTo(4);
        assertThat(stats.dismissedRate()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("T-03: không có sự cố đã kết luận → dismissedRate null, resolvedCount 0")
    void dismissedRate_nullWhenNothingResolved() {
        Area a = area("T03N");
        incident(a, IncidentStatus.NEW, vn(3, 15, 8, 0));
        incident(a, IncidentStatus.CLAIMED, vn(3, 15, 8, 30));
        incident(a, IncidentStatus.RESOLVED_DISMISSED, vn(3, 14, 8, 0)); // hôm qua: ngoài TODAY

        SecurityStatsResponse stats = securityDashboardService.getStats(SecurityStatsRange.TODAY, NOW);
        assertThat(stats.resolvedCount()).isZero();
        assertThat(stats.dismissedRate()).isNull();
    }

    // ------------------------------------------------------------------ T-04: top khu

    @Test
    @DisplayName("T-04: 6 khu có sự cố → 5 khu, giảm dần, hoà thì theo tên; sự cố ngoài khoảng không tính")
    void topAreas_fiveDescendingTieByName() {
        Area a5 = area("A");
        Area d3 = area("D"); // tạo D trước C để chứng minh hoà sắp theo tên, không theo thứ tự tạo
        Area c3 = area("C");
        Area b4 = area("B");
        Area e2 = area("E");
        Area f1 = area("F");
        incidents(a5, IncidentStatus.NEW, vn(3, 10, 8, 0), 5);
        incidents(b4, IncidentStatus.CLAIMED, vn(3, 11, 8, 0), 4);
        incidents(d3, IncidentStatus.RESOLVED_VERIFIED, vn(3, 12, 8, 0), 3);
        incidents(c3, IncidentStatus.RESOLVED_DISMISSED, vn(3, 12, 9, 0), 3);
        incidents(e2, IncidentStatus.NEW, vn(3, 13, 8, 0), 2);
        incidents(f1, IncidentStatus.NEW, vn(3, 14, 8, 0), 1);
        // F có thêm 9 sự cố ngoài khoảng: nếu đếm nhầm F sẽ lên đầu
        incidents(f1, IncidentStatus.NEW, vn(3, 1, 8, 0), 9);

        List<AreaIncidentCount> top = securityDashboardService.getStats(SecurityStatsRange.LAST_7_DAYS, NOW).topAreas();

        assertThat(top).extracting(AreaIncidentCount::areaId)
                .containsExactly(a5.getId(), b4.getId(), c3.getId(), d3.getId(), e2.getId());
        assertThat(top).extracting(AreaIncidentCount::count).containsExactly(5L, 4L, 3L, 3L, 2L);
        assertThat(top.get(0).areaName()).isEqualTo(a5.getName());
    }

    // ------------------------------------------------------------------ T-05: lượt ra vào theo ngày

    @Test
    @DisplayName("T-05: ngày cắt theo giờ VN (23:59 và 00:01), ngày không có lượt vẫn có dòng toàn 0")
    void accessResultsByDay_vnDayBoundaries() {
        Area a = area("T05");
        accessLog(a, AccessResult.AUTHORIZED_ACCESS, vn(3, 14, 23, 59));   // 16:59Z 14/03 → ngày 14/03
        accessLog(a, AccessResult.UNAUTHORIZED_ACCESS, vn(3, 15, 0, 1));   // 17:01Z 14/03 → ngày 15/03 (giờ VN)
        accessLog(a, AccessResult.UNKNOWN_PERSON, vn(3, 15, 0, 1));
        accessLog(a, AccessResult.INTRUSION, vn(3, 9, 0, 0));              // mốc đầu khoảng: tính
        accessLog(a, AccessResult.ACCESS_OUT_OF_TIME, vn(3, 12, 7, 0));
        accessLog(a, AccessResult.ACCESS_OUT_OF_TIME, vn(3, 12, 18, 0));
        accessLog(a, AccessResult.AUTHORIZED_ACCESS, vn(3, 8, 23, 59));    // trước khoảng
        accessLog(a, AccessResult.AUTHORIZED_ACCESS, vn(3, 15, 10, 1));    // sau now

        List<DailyAccessResults> days = securityDashboardService.getStats(SecurityStatsRange.LAST_7_DAYS, NOW)
                .accessResultsByDay();

        assertThat(days).extracting(DailyAccessResults::date).containsExactly(
                LocalDate.of(2047, 3, 9), LocalDate.of(2047, 3, 10), LocalDate.of(2047, 3, 11),
                LocalDate.of(2047, 3, 12), LocalDate.of(2047, 3, 13), LocalDate.of(2047, 3, 14),
                LocalDate.of(2047, 3, 15));
        assertThat(days).allSatisfy(d -> assertThat(d.counts()).containsOnlyKeys(AccessResult.values()));

        assertThat(days.get(0).counts()).containsEntry(AccessResult.INTRUSION, 1L);
        assertThat(total(days.get(0))).isEqualTo(1);
        assertThat(total(days.get(1))).isZero();
        assertThat(total(days.get(2))).isZero();
        assertThat(days.get(3).counts()).containsEntry(AccessResult.ACCESS_OUT_OF_TIME, 2L);
        assertThat(total(days.get(3))).isEqualTo(2);
        assertThat(total(days.get(4))).isZero();
        assertThat(days.get(5).counts()).containsEntry(AccessResult.AUTHORIZED_ACCESS, 1L);
        assertThat(total(days.get(5))).isEqualTo(1);
        assertThat(days.get(6).counts())
                .containsEntry(AccessResult.UNAUTHORIZED_ACCESS, 1L)
                .containsEntry(AccessResult.UNKNOWN_PERSON, 1L)
                .containsEntry(AccessResult.AUTHORIZED_ACCESS, 0L);
        assertThat(total(days.get(6))).isEqualTo(2);

        List<DailyAccessResults> today = securityDashboardService.getStats(SecurityStatsRange.TODAY, NOW)
                .accessResultsByDay();
        assertThat(today).hasSize(1);
        assertThat(today.get(0).date()).isEqualTo(LocalDate.of(2047, 3, 15));
        assertThat(total(today.get(0))).isEqualTo(2);
    }

    private static long total(DailyAccessResults d) {
        return d.counts().values().stream().mapToLong(Long::longValue).sum();
    }
}
