package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * BR-RQ-33: endpoint tạo đơn (cá nhân + nhóm) dùng CHUNG tiêu chí với danh sách available-areas
 * (AreaService.isAvailableForRequest). V83: không còn phân biệt Nội bộ / Liên hệ trước (cùng là Bảo mật, cho bảo lãnh).
 * Không @Transactional: thông báo NEW_REQUEST_PENDING chạy REQUIRES_NEW, cập nhật cấu hình ghi cache sau commit.
 */
class AccessRequestAreaEligibilityTest extends AbstractIntegrationTest {

    private static final String SPONSOR_KEY = ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.getKey();
    private static final String SPONSOR_DEFAULT = ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.getDefaultValue();
    private static final String GROUP_HC_KEY = ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE.getKey();

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;

    private final List<UUID> createdUsers = new ArrayList<>();
    private String suffix;
    private String buildingName;
    private Floor floor;
    private User admin;
    private User requester;
    private User member;
    private User memberL1;
    private Area contactArea;
    private Area internalArea;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa RQ33")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa RQ33").build()));
        buildingName = building.getName();
        floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa RQ33", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));

        admin = user("adm", Role.ADMIN, 3);
        requester = user("req", Role.NORMAL_USER, 3);
        member = user("mem", Role.NORMAL_USER, 3);
        memberL1 = user("m1", Role.NORMAL_USER, 1);
        contactArea = area("contact", AreaLevel.CONFIDENTIAL);
        internalArea = area("internal", AreaLevel.CONFIDENTIAL);
    }

    @AfterEach
    void tearDown() {
        // DB test dùng chung: trả cấu hình bảo lãnh về mặc định, vô hiệu hoá user fixture
        if (!SPONSOR_DEFAULT.equals(systemConfigService.getString(ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES))) {
            systemConfigService.update(SPONSOR_KEY, SPONSOR_DEFAULT, "Trả về mặc định sau test BR-RQ-33", admin.getEmail());
        }
        if (systemConfigService.getBoolean(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE)) {
            systemConfigService.update(GROUP_HC_KEY, "false", "Trả về mặc định sau test A-06", admin.getEmail());
        }
        for (User u : userRepository.findAllById(createdUsers)) {
            u.setIsActive(false);
            userRepository.save(u);
        }
        createdUsers.clear();
    }

    private User user(String tag, Role role, int level) {
        User u = userRepository.save(User.builder()
                .userCode("RQ33-" + tag.toUpperCase() + "-" + suffix)
                .fullName("RQ33 " + tag + " " + suffix)
                .email("rq33-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(level)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    /** V83: cấp của khu theo loại. */
    private Area area(String tag, AreaLevel level) {
        return areaRepository.save(Area.builder()
                .name("RQ33 " + tag + " " + suffix)
                .building(buildingName)
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(level)
                .isActive(true)
                .build());
    }

    private MvcResult create(String path, Area area, List<String> memberCodes) throws Exception {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("areaId", area.getId());
        body.put("startTime", start);
        body.put("endTime", start.plusHours(2));
        body.put("purpose", "Kiểm tra BR-RQ-33 " + suffix);
        if (memberCodes != null) {
            body.put("memberUserCodes", memberCodes);
        }
        return mockMvc.perform(post(path)
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(requester))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn();
    }

    /** Cờ groupRequestAllowed của một khu vực trong available-areas (null nếu khu vực không có trong danh sách). */
    private Boolean groupRequestAllowed(User caller, Area area) throws Exception {
        for (var node : objectMapper.readTree(availableAreas(caller)).path("data")) {
            if (area.getId().toString().equals(node.path("id").asText())) {
                return node.path("groupRequestAllowed").isBoolean() ? node.path("groupRequestAllowed").asBoolean() : null;
            }
        }
        return null;
    }

    private String availableAreas(User caller) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/access-requests/available-areas")
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(caller))).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        return body(r);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("V83: khu Bảo mật (Nội bộ cũ) có trong available-areas; đơn nhóm có thành viên L1 -> 201, L1 được bảo lãnh (cấu hình mặc định CONFIDENTIAL)")
    void confidentialArea_listedAndGroupWithSponsoredMemberAccepted() throws Exception {
        assertEquals(java.util.Set.of(AreaLevel.CONFIDENTIAL), systemConfigService.getSponsorAllowedAreaLevels());
        String list = availableAreas(requester);
        assertTrue(list.contains(internalArea.getId().toString()), "khu Bảo mật (Nội bộ cũ) có trong danh sách");
        assertTrue(list.contains(contactArea.getId().toString()), "khu Bảo mật (Liên hệ trước cũ) có trong danh sách");

        MvcResult group = create("/api/access-requests/group", internalArea, List.of(memberL1.getUserCode()));
        assertEquals(201, group.getResponse().getStatus(), body(group));
        assertTrue(objectMapper.readTree(body(group)).path("data").path("members").get(0).path("sponsored").asBoolean(),
                "thành viên L1 được bảo lãnh");
    }

    @Test
    @DisplayName("Khu vực đang không hoạt động (is_active = false, chưa xoá mềm): tạo đơn -> 400")
    void inactiveArea_createRejected() throws Exception {
        contactArea.setIsActive(false);
        areaRepository.save(contactArea);

        assertFalse(availableAreas(requester).contains(contactArea.getId().toString()));
        MvcResult individual = create("/api/access-requests/individual", contactArea, null);
        assertEquals(400, individual.getResponse().getStatus(), body(individual));
        assertTrue(body(individual).contains(AccessRequestService.AREA_NOT_REQUESTABLE_MESSAGE), body(individual));
    }

    @Test
    @DisplayName("Khu vực Bảo mật có trong available-areas: tạo đơn cá nhân / nhóm -> 201")
    void contactArea_inAvailableList_createAccepted() throws Exception {
        MvcResult individual = create("/api/access-requests/individual", contactArea, null);
        assertEquals(201, individual.getResponse().getStatus(), body(individual));

        Area otherContact = area("contact2", AreaLevel.CONFIDENTIAL);
        MvcResult group = create("/api/access-requests/group", otherContact, List.of(member.getUserCode()));
        assertEquals(201, group.getResponse().getStatus(), body(group));
    }

    @Test
    @DisplayName("A-06 (BR-RQ-28): available-areas trả groupRequestAllowed theo ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE; bật cấu hình -> đơn nhóm Tuyệt mật 201")
    void highlyConfidential_groupFlagFollowsConfig() throws Exception {
        Area hcArea = area("hc", AreaLevel.HIGHLY_CONFIDENTIAL);

        assertEquals(Boolean.FALSE, groupRequestAllowed(requester, hcArea), "mặc định: Tuyệt mật không nhận đơn nhóm");
        assertEquals(Boolean.TRUE, groupRequestAllowed(requester, contactArea), "Bảo mật luôn nhận đơn nhóm");
        MvcResult rejected = create("/api/access-requests/group", hcArea, List.of(member.getUserCode()));
        assertEquals(400, rejected.getResponse().getStatus(), body(rejected));

        systemConfigService.update(GROUP_HC_KEY, "true", "Bật đơn nhóm Tuyệt mật cho test A-06", admin.getEmail());

        assertEquals(Boolean.TRUE, groupRequestAllowed(requester, hcArea), "bật cấu hình: Tuyệt mật nhận đơn nhóm");
        MvcResult accepted = create("/api/access-requests/group", hcArea, List.of(member.getUserCode()));
        assertEquals(201, accepted.getResponse().getStatus(), body(accepted));
    }
}
