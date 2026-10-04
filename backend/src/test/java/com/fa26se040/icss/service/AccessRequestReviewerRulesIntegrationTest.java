package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * BR-RQ-06 (TC-AR-08): người duyệt đơn phải khác người tạo đơn — áp cho cả APPROVED và REJECTED, đơn giữ PENDING.
 * BR-RQ-07: chỉ NORMAL_USER được tạo đơn (cá nhân, nhóm) — ADMIN / FACILITY_MANAGER / GUARD nhận 403.
 * Gọi qua HTTP (MockMvc) để kiểm cả mã HTTP và mã lỗi. @Transactional: dữ liệu fixture rollback sau mỗi test.
 */
@Transactional
class AccessRequestReviewerRulesIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;

    private String suffix;
    private User admin;
    private User fmOther;
    private User guard;
    private User requester;
    private User member;
    private Area contactArea;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa RQ rules")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa RQ rules").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa RQ rules", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));

        admin = user("adm", Role.ADMIN, 3);
        fmOther = user("fmo", Role.FACILITY_MANAGER, 3);
        guard = user("grd", Role.GUARD, 2);
        requester = user("req", Role.NORMAL_USER, 2);
        member = user("mem", Role.NORMAL_USER, 2);

        contactArea = areaRepository.save(Area.builder()
                .name("RQ rules contact " + suffix)
                .building("TOA_RQ_RULES")
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
    }

    private User user(String tag, Role role, int level) {
        return userRepository.save(User.builder()
                .userCode("RQR-" + tag.toUpperCase() + "-" + suffix)
                .fullName("RQ rules " + tag + " " + suffix)
                .email("rqr-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(level)
                .isActive(true)
                .build());
    }

    private String bearer(User u) {
        return "Bearer " + jwtTokenProvider.generateToken(u);
    }

    private MvcResult send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, User actor, Object body) throws Exception {
        b.header("Authorization", bearer(actor));
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

    private Map<String, Object> individualBody() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(2).truncatedTo(ChronoUnit.MINUTES);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("areaId", contactArea.getId());
        b.put("startTime", start.toString());
        b.put("endTime", start.plusHours(1).toString());
        b.put("purpose", "Đơn thử quy tắc người duyệt " + suffix);
        return b;
    }

    private Map<String, Object> groupBody() {
        Map<String, Object> b = individualBody();
        b.put("memberUserCodes", List.of(member.getUserCode()));
        return b;
    }

    private Map<String, Object> review(RequestStatus status) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("status", status.name());
        if (status == RequestStatus.REJECTED) {
            b.put("rejectionReason", "Người tạo tự từ chối đơn của mình để thử");
        }
        return b;
    }

    private RequestStatus statusOf(UUID id) {
        return accessRequestRepository.findById(id).map(AccessRequest::getStatus).orElseThrow();
    }

    // ================================================================== BR-RQ-06

    @Test
    @DisplayName("TC-AR-08 (BR-RQ-06): người tạo đơn (sau đó thành FM) tự duyệt / tự từ chối -> 403 ERR_AC_006, đơn vẫn PENDING; FM khác duyệt -> APPROVED")
    void tc_ar_08_reviewerMustDifferFromRequester() throws Exception {
        MvcResult created = send(post("/api/access-requests/individual"), requester, individualBody());
        assertThat(created.getResponse().getStatus()).as(describe(created)).isEqualTo(201);
        UUID requestId = UUID.fromString(json(created).path("data").path("id").asText());

        // Người tạo đơn được đổi role sang FACILITY_MANAGER (qua repository), token mới mang role mới
        requester.setRole(Role.FACILITY_MANAGER);
        requester = userRepository.saveAndFlush(requester);

        for (RequestStatus decision : List.of(RequestStatus.APPROVED, RequestStatus.REJECTED)) {
            MvcResult self = send(patch("/api/access-requests/{id}/review", requestId), requester, review(decision));
            assertThat(self.getResponse().getStatus()).as(decision + ": " + describe(self)).isEqualTo(403);
            assertThat(json(self).path("code").asText()).as(describe(self)).isEqualTo("ERR_AC_006");
            assertThat(json(self).path("message").asText()).as(describe(self)).isNotBlank();
            assertThat(statusOf(requestId)).as("đơn phải giữ PENDING sau khi tự " + decision).isEqualTo(RequestStatus.PENDING);
        }

        MvcResult other = send(patch("/api/access-requests/{id}/review", requestId), fmOther, review(RequestStatus.APPROVED));
        assertThat(other.getResponse().getStatus()).as(describe(other)).isEqualTo(200);
        assertThat(statusOf(requestId)).isEqualTo(RequestStatus.APPROVED);
    }

    // ================================================================== BR-RQ-07

    @Test
    @DisplayName("BR-RQ-07: ADMIN / FACILITY_MANAGER / GUARD tạo đơn cá nhân hoặc đơn nhóm -> 403, không tạo đơn nào")
    void br_rq_07_onlyNormalUserCanCreateRequests() throws Exception {
        long before = accessRequestRepository.count();
        for (User actor : List.of(admin, fmOther, guard)) {
            MvcResult ind = send(post("/api/access-requests/individual"), actor, individualBody());
            assertThat(ind.getResponse().getStatus()).as(actor.getRole() + " /individual: " + describe(ind)).isEqualTo(403);
            MvcResult grp = send(post("/api/access-requests/group"), actor, groupBody());
            assertThat(grp.getResponse().getStatus()).as(actor.getRole() + " /group: " + describe(grp)).isEqualTo(403);
        }
        assertThat(accessRequestRepository.count()).as("không đơn nào được tạo").isEqualTo(before);

        // Đối chứng: NORMAL_USER tạo được cả hai loại
        assertThat(send(post("/api/access-requests/individual"), requester, individualBody()).getResponse().getStatus()).isEqualTo(201);
        Map<String, Object> laterGroup = groupBody();
        OffsetDateTime start = OffsetDateTime.now().plusHours(5).truncatedTo(ChronoUnit.MINUTES);
        laterGroup.put("startTime", start.toString());
        laterGroup.put("endTime", start.plusHours(1).toString());
        MvcResult grpOk = send(post("/api/access-requests/group"), requester, laterGroup);
        assertThat(grpOk.getResponse().getStatus()).as(describe(grpOk)).isEqualTo(201);
    }
}
