package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * D-3 / E-3: mỗi lần cấp quyền chỉ định (AP) là một bản ghi, không sửa validTo.
 * BR-AP-28: validFrom không ở quá khứ (cho lệch đồng hồ 5 phút) -> ERR_AP_013.
 * BR-AP-30: chỉ thu hồi AP ACTIVE / UPCOMING; AP EXPIRED -> 409 ERR_AP_012, không đổi bản ghi, không audit.
 * Cấp tiếp = gán đợt mới nối liền đợt cũ (khoảng nửa mở [validFrom, validTo) nên không chồng lấn).
 * @Transactional: dữ liệu fixture rollback sau mỗi test.
 */
@Transactional
class AssignedPersonnelGrantRulesTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/areas/{areaId}/assigned-personnel";

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AreaAssignedPersonnelRepository assignedPersonnelRepository;

    private String suffix;
    private User fm;
    private User target;
    private Area area;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa AP grant")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa AP grant").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa AP grant", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        fm = user("fm", Role.FACILITY_MANAGER);
        target = user("usr", Role.NORMAL_USER);
        area = areaRepository.save(Area.builder()
                .name("AP grant " + suffix)
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

    private User user(String tag, Role role) {
        return userRepository.save(User.builder()
                .userCode("APG-" + tag.toUpperCase() + "-" + suffix)
                .fullName("AP grant " + tag + " " + suffix)
                .email("apg-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
    }

    private MvcResult send(MockHttpServletRequestBuilder b, Object body) throws Exception {
        b.header("Authorization", "Bearer " + jwtTokenProvider.generateToken(fm));
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(b).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        String c = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return c.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(c);
    }

    private String describe(MvcResult r) throws Exception {
        return "HTTP " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private MvcResult assign(OffsetDateTime validFrom, OffsetDateTime validTo) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("userId", target.getId());
        if (validFrom != null) {
            b.put("validFrom", validFrom.toString());
        }
        if (validTo != null) {
            b.put("validTo", validTo.toString());
        }
        b.put("reason", "Gán quyền chỉ định để thử E-3");
        return send(post(BASE, area.getId()), b);
    }

    private MvcResult revoke(UUID apId) throws Exception {
        return send(patch(BASE + "/{id}/revoke", area.getId(), apId), Map.of("reason", "Kết thúc sớm đợt cấp quyền"));
    }

    private AreaAssignedPersonnel existing(OffsetDateTime from, OffsetDateTime to) {
        return assignedPersonnelRepository.save(AreaAssignedPersonnel.builder()
                .area(area)
                .user(target)
                .validFrom(from)
                .validTo(to)
                .createdBy(fm)
                .build());
    }

    private int revokeAudits(UUID apId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_type = 'AREA_ASSIGNMENT' AND action = 'REVOKE' AND target_id = ?",
                Integer.class, apId.toString());
    }

    @Test
    @DisplayName("P2-01: endpoint sửa thời hạn PATCH /assigned-personnel/{id} không còn route -> 404/405, bản ghi không đổi")
    void validityEditEndpoint_removed() throws Exception {
        OffsetDateTime to = OffsetDateTime.now().plusDays(3).truncatedTo(ChronoUnit.SECONDS);
        AreaAssignedPersonnel ap = existing(OffsetDateTime.now().minusHours(1), to);

        MvcResult r = send(patch(BASE + "/{id}", area.getId(), ap.getId()),
                Map.of("validTo", to.plusDays(30).toString(), "reason", "Gia hạn quyền chỉ định thêm 30 ngày"));

        assertThat(r.getResponse().getStatus()).as(describe(r)).isIn(404, 405);
        assertThat(assignedPersonnelRepository.findById(ap.getId()).orElseThrow().getValidTo().toInstant())
                .isEqualTo(to.toInstant());
    }

    @Test
    @DisplayName("BR-AP-28: validFrom = now − 1 ngày -> 400 ERR_AP_013, không tạo bản ghi")
    void assign_validFromYesterday_rejected() throws Exception {
        MvcResult r = assign(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1));

        assertThat(r.getResponse().getStatus()).as(describe(r)).isEqualTo(400);
        assertThat(json(r).path("code").asText()).as(describe(r)).isEqualTo("ERR_AP_013");
        assertThat(json(r).path("message").asText()).isEqualTo("Thời điểm bắt đầu không được ở quá khứ");
        assertThat(assignedPersonnelRepository.findByAreaIdWithDetails(area.getId())).isEmpty();
    }

    @Test
    @DisplayName("BR-AP-28: validFrom = now − 2 phút (trong độ lệch đồng hồ) -> 201 ACTIVE")
    void assign_validFromWithinClockSkew_ok() throws Exception {
        MvcResult skew = assign(OffsetDateTime.now().minusMinutes(2), OffsetDateTime.now().plusDays(1));
        assertThat(skew.getResponse().getStatus()).as(describe(skew)).isEqualTo(201);
        assertThat(json(skew).path("data").path("status").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("BR-AP-30: thu hồi AP EXPIRED -> 409 ERR_AP_012, bản ghi không đổi, không audit REVOKE")
    void revoke_expired_conflict() throws Exception {
        AreaAssignedPersonnel ap = existing(OffsetDateTime.now().minusDays(5), OffsetDateTime.now().minusDays(1));

        MvcResult r = revoke(ap.getId());

        assertThat(r.getResponse().getStatus()).as(describe(r)).isEqualTo(409);
        assertThat(json(r).path("code").asText()).as(describe(r)).isEqualTo("ERR_AP_012");
        assertThat(json(r).path("message").asText())
                .isEqualTo("Quyền đã hết hạn, không cần thu hồi. Muốn cấp tiếp, hãy gán đợt mới.");
        AreaAssignedPersonnel after = assignedPersonnelRepository.findById(ap.getId()).orElseThrow();
        assertThat(after.getRevokedAt()).isNull();
        assertThat(after.getRevokedBy()).isNull();
        assertThat(after.getRevokeReason()).isNull();
        assertThat(revokeAudits(ap.getId())).isZero();
    }

    @Test
    @DisplayName("BR-AP-30: thu hồi AP ACTIVE và UPCOMING -> 200 REVOKED, 1 audit mỗi bản ghi; AP đã thu hồi -> 409 ERR_AP_009 như cũ")
    void revoke_activeAndUpcoming_ok() throws Exception {
        AreaAssignedPersonnel active = existing(OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1));
        AreaAssignedPersonnel upcoming = existing(OffsetDateTime.now().plusDays(2), OffsetDateTime.now().plusDays(3));

        for (AreaAssignedPersonnel ap : new AreaAssignedPersonnel[]{active, upcoming}) {
            MvcResult r = revoke(ap.getId());
            assertThat(r.getResponse().getStatus()).as(describe(r)).isEqualTo(200);
            assertThat(json(r).path("data").path("status").asText()).isEqualTo("REVOKED");
            assertThat(revokeAudits(ap.getId())).isEqualTo(1);
        }
        MvcResult again = revoke(active.getId());
        assertThat(again.getResponse().getStatus()).as(describe(again)).isEqualTo(409);
        assertThat(json(again).path("code").asText()).isEqualTo("ERR_AP_009");
    }

    @Test
    @DisplayName("E-3: gán đợt 2 nối liền đợt 1 đang hiệu lực (validFrom = validTo cũ) -> 201, hai bản ghi")
    void assign_nextGrantAdjacent_ok() throws Exception {
        OffsetDateTime firstTo = OffsetDateTime.now().plusHours(2).truncatedTo(ChronoUnit.SECONDS);
        MvcResult first = assign(null, firstTo);
        assertThat(first.getResponse().getStatus()).as(describe(first)).isEqualTo(201);
        assertThat(json(first).path("data").path("status").asText()).isEqualTo("ACTIVE");

        MvcResult second = assign(firstTo, firstTo.plusDays(7));

        assertThat(second.getResponse().getStatus()).as(describe(second)).isEqualTo(201);
        assertThat(json(second).path("data").path("status").asText()).isEqualTo("UPCOMING");
        assertThat(assignedPersonnelRepository.findByAreaIdWithDetails(area.getId())).hasSize(2);
    }

    @Test
    @DisplayName("BR-AP-03: gán đợt 2 chồng đợt 1 một phút -> 409 ERR_AP_004")
    void assign_nextGrantOverlapsOneMinute_conflict() throws Exception {
        OffsetDateTime firstTo = OffsetDateTime.now().plusHours(2).truncatedTo(ChronoUnit.SECONDS);
        assertThat(assign(null, firstTo).getResponse().getStatus()).isEqualTo(201);

        MvcResult second = assign(firstTo.minusMinutes(1), firstTo.plusDays(7));

        assertThat(second.getResponse().getStatus()).as(describe(second)).isEqualTo(409);
        assertThat(json(second).path("code").asText()).isEqualTo("ERR_AP_004");
        assertThat(assignedPersonnelRepository.findByAreaIdWithDetails(area.getId())).hasSize(1);
    }
}
