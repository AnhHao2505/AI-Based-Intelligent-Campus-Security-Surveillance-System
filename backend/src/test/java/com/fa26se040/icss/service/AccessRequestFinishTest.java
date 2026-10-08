package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * BR-RQ-44: chỉ FACILITY_MANAGER chuyển đơn APPROVED sang FINISHED (PATCH /api/access-requests/{id}/finish).
 * Service khớp controller: requester / ADMIN gọi thẳng service cũng bị từ chối. Điều kiện trạng thái APPROVED giữ nguyên.
 */
class AccessRequestFinishTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccessRequestService accessRequestService;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;

    private final List<UUID> createdUsers = new ArrayList<>();
    private String suffix;
    private User admin;
    private User fm;
    private User guard;
    private User requester;
    private User otherUser;
    private Area area;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa RQ44")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa RQ44").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa RQ44", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));

        admin = user("adm", Role.ADMIN);
        fm = user("fm", Role.FACILITY_MANAGER);
        guard = user("grd", Role.GUARD);
        requester = user("req", Role.NORMAL_USER);
        otherUser = user("oth", Role.NORMAL_USER);
        area = areaRepository.save(Area.builder()
                .name("RQ44 contact " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .build());
    }

    @AfterEach
    void deactivateFixtureUsers() {
        for (User u : userRepository.findAllById(createdUsers)) {
            u.setIsActive(false);
            userRepository.save(u);
        }
        createdUsers.clear();
    }

    private User user(String tag, Role role) {
        User u = userRepository.save(User.builder()
                .userCode("RQ44-" + tag.toUpperCase() + "-" + suffix)
                .fullName("RQ44 " + tag + " " + suffix)
                .email("rq44-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(3)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private AccessRequest request(RequestStatus status) {
        OffsetDateTime start = OffsetDateTime.now().minusHours(1).truncatedTo(ChronoUnit.SECONDS);
        return accessRequestRepository.save(AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Đơn RQ44 " + suffix)
                .startTime(start)
                .endTime(start.plusHours(3))
                .status(status)
                .reviewer(status == RequestStatus.PENDING ? null : fm)
                .reviewedAt(status == RequestStatus.PENDING ? null : start.minusHours(1))
                .build());
    }

    private MvcResult finish(User actor, AccessRequest r) throws Exception {
        return mockMvc.perform(patch("/api/access-requests/{id}/finish", r.getId())
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor))).andReturn();
    }

    private String status(AccessRequest r) {
        return jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, r.getId());
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("FM hoàn thành đơn APPROVED -> 200, FINISHED, 1 audit ACCESS_REQUEST / FINISH")
    void fm_finishApproved_ok() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED);

        MvcResult res = finish(fm, r);

        assertEquals(200, res.getResponse().getStatus(), body(res));
        assertEquals("FINISHED", status(r));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = 'FINISH' AND target_id = ?",
                Integer.class, r.getId().toString()));
    }

    @Test
    @DisplayName("Requester / ADMIN / NORMAL_USER khác / GUARD gọi API -> 403, đơn giữ APPROVED")
    void nonFm_api_forbidden() throws Exception {
        AccessRequest r = request(RequestStatus.APPROVED);

        for (User actor : List.of(requester, admin, otherUser, guard)) {
            MvcResult res = finish(actor, r);
            assertEquals(403, res.getResponse().getStatus(), actor.getRole() + ": " + body(res));
        }
        assertEquals("APPROVED", status(r));
    }

    @Test
    @DisplayName("Service khớp controller: requester / ADMIN gọi thẳng service -> AccessDeniedException, đơn giữ APPROVED")
    void nonFm_service_forbidden() {
        AccessRequest r = request(RequestStatus.APPROVED);

        assertThrows(AccessDeniedException.class, () -> accessRequestService.finishRequest(r.getId(), requester.getEmail()));
        assertThrows(AccessDeniedException.class, () -> accessRequestService.finishRequest(r.getId(), admin.getEmail()));
        assertEquals("APPROVED", status(r));
    }

    @Test
    @DisplayName("Điều kiện trạng thái giữ nguyên: FM hoàn thành đơn PENDING -> 400, đơn giữ PENDING")
    void fm_finishPending_badRequest() throws Exception {
        AccessRequest r = request(RequestStatus.PENDING);

        MvcResult res = finish(fm, r);

        assertEquals(400, res.getResponse().getStatus(), body(res));
        assertEquals("PENDING", status(r));
    }
}
