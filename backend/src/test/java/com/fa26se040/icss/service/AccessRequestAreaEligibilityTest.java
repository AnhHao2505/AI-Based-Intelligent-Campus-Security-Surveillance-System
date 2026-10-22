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
 * BR-RQ-33, BR-RQ-01, BR-RQ-03 (V83): endpoint tạo đơn (cá nhân + nhóm) dùng CHUNG điều kiện với danh sách available-areas
 * (AreaService.requestIneligibility), theo loại đơn:
 * - cá nhân: cấp khu > cấp người tạo, cách tối đa ACCESS_REQUEST_MAX_LEVEL_GAP cấp;
 * - nhóm: người tạo tự vào được khu bằng cấp, thành viên thiếu cấp được bảo lãnh (khu Bảo mật); Tuyệt mật chỉ khi bật cấu hình.
 * Không @Transactional: thông báo NEW_REQUEST_PENDING chạy REQUIRES_NEW, cập nhật cấu hình ghi cache sau commit.
 */
class AccessRequestAreaEligibilityTest extends AbstractIntegrationTest {

    private static final String SPONSOR_KEY = ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.getKey();
    private static final String SPONSOR_DEFAULT = ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES.getDefaultValue();
    private static final String GROUP_HC_KEY = ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE.getKey();
    private static final String GAP_KEY = ConfigKey.ACCESS_REQUEST_MAX_LEVEL_GAP.getKey();

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
    private User userL1;
    private User userL2;
    private Area contactArea;
    private Area internalArea;
    private Area highlyArea;

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
        userL1 = user("u1", Role.NORMAL_USER, 1);
        userL2 = user("u2", Role.NORMAL_USER, 2);
        contactArea = area("contact", AreaLevel.CONFIDENTIAL);
        internalArea = area("internal", AreaLevel.CONFIDENTIAL);
        highlyArea = area("highly", AreaLevel.HIGHLY_CONFIDENTIAL);
    }

    @AfterEach
    void tearDown() {
        // DB test dùng chung: trả cấu hình bảo lãnh về mặc định, vô hiệu hoá user fixture
        if (!SPONSOR_DEFAULT.equals(systemConfigService.getString(ConfigKey.ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES))) {
            systemConfigService.update(SPONSOR_KEY, SPONSOR_DEFAULT, "Trả về mặc định sau test BR-RQ-33", admin.getEmail());
        }
        if (systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_LEVEL_GAP) != 1) {
            systemConfigService.update(GAP_KEY, "1", "Trả về mặc định sau test khoảng cách cấp", admin.getEmail());
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
        return create(requester, path, area, memberCodes);
    }

    private MvcResult create(User creator, String path, Area area, List<String> memberCodes) throws Exception {
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
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(creator))
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
        return availableAreas(caller, null);
    }

    private String availableAreas(User caller, String type) throws Exception {
        var req = get("/api/access-requests/available-areas")
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(caller));
        if (type != null) {
            req = req.param("type", type);
        }
        MvcResult r = mockMvc.perform(req).andReturn();
        assertEquals(200, r.getResponse().getStatus());
        return body(r);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String message(MvcResult r) throws Exception {
        return objectMapper.readTree(body(r)).path("message").asText();
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
    @DisplayName("V83 đơn cá nhân: L1 xin Bảo mật -> 201; L1 xin Tuyệt mật -> 400 vượt khoảng cách cấp; L2 xin Bảo mật -> 400 đã đủ cấp; L3 không xin được khu nào")
    void individual_levelRules() throws Exception {
        assertEquals(201, create(userL1, "/api/access-requests/individual", contactArea, null).getResponse().getStatus());

        MvcResult gap = create(userL1, "/api/access-requests/individual", highlyArea, null);
        assertEquals(400, gap.getResponse().getStatus(), body(gap));
        assertEquals("Chỉ được xin vào khu vực cao hơn cấp của bạn tối đa 1 cấp (khu vực cấp 3, bạn cấp 1)", message(gap));

        MvcResult enough = create(userL2, "/api/access-requests/individual", internalArea, null);
        assertEquals(400, enough.getResponse().getStatus(), body(enough));
        assertEquals("Bạn đã đủ cấp vào khu vực này, không cần tạo yêu cầu truy cập (khu vực cấp 2, bạn cấp 2)", message(enough));
        assertEquals(201, create(userL2, "/api/access-requests/individual", highlyArea, null).getResponse().getStatus());

        MvcResult l3 = create(requester, "/api/access-requests/individual", highlyArea, null);
        assertEquals(400, l3.getResponse().getStatus(), body(l3));
        assertTrue(message(l3).startsWith("Bạn đã đủ cấp vào khu vực này"), message(l3));
    }

    @Test
    @DisplayName("V83: ADMIN đổi ACCESS_REQUEST_MAX_LEVEL_GAP 1 -> 2: L1 xin Tuyệt mật được ngay (danh sách và tạo đơn), không restart; ngoài 1–2 -> 400")
    void individual_gapConfigAppliesImmediately() throws Exception {
        assertFalse(availableAreas(userL1, "INDIVIDUAL").contains(highlyArea.getId().toString()));
        assertEquals(400, create(userL1, "/api/access-requests/individual", highlyArea, null).getResponse().getStatus());

        systemConfigService.update(GAP_KEY, "2", "Cho cấp 1 xin thẳng khu Tuyệt mật", admin.getEmail());

        assertTrue(availableAreas(userL1, "INDIVIDUAL").contains(highlyArea.getId().toString()));
        MvcResult ok = create(userL1, "/api/access-requests/individual", highlyArea, null);
        assertEquals(201, ok.getResponse().getStatus(), body(ok));

        for (String bad : List.of("0", "3")) {
            assertThrows(IllegalArgumentException.class,
                    () -> systemConfigService.update(GAP_KEY, bad, "Khoảng cách cấp ngoài khoảng", admin.getEmail()), bad);
        }
        assertEquals(2, systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_LEVEL_GAP));
    }

    @Test
    @DisplayName("V83 đơn nhóm: L2 tạo vào Bảo mật kèm L1 -> 201, L1 sponsored; L1 tạo đơn nhóm -> 400; đơn nhóm vào Tuyệt mật -> 400")
    void group_levelRules() throws Exception {
        MvcResult ok = create(userL2, "/api/access-requests/group", contactArea, List.of(memberL1.getUserCode()));
        assertEquals(201, ok.getResponse().getStatus(), body(ok));
        assertTrue(objectMapper.readTree(body(ok)).path("data").path("members").get(0).path("sponsored").asBoolean());

        MvcResult byL1 = create(userL1, "/api/access-requests/group", internalArea, List.of(memberL1.getUserCode()));
        assertEquals(400, byL1.getResponse().getStatus(), body(byL1));
        assertEquals("Người tạo đơn nhóm phải tự vào được khu vực bằng cấp truy cập để bảo lãnh thành viên (khu vực cấp 2, bạn cấp 1)",
                message(byL1));

        MvcResult highly = create(requester, "/api/access-requests/group", highlyArea, List.of(member.getUserCode()));
        assertEquals(400, highly.getResponse().getStatus(), body(highly));
        assertEquals("Khu vực Tuyệt mật (HIGHLY_CONFIDENTIAL) chỉ cho phép đăng ký truy cập cá nhân (INDIVIDUAL)", message(highly));
    }

    @Test
    @DisplayName("V83 available-areas ?type: INDIVIDUAL / GROUP khớp điều kiện tạo đơn; không gửi type -> hợp hai loại kèm cờ individualRequestAllowed / groupRequestAllowed")
    void availableAreas_followRequestType() throws Exception {
        String l1Individual = availableAreas(userL1, "INDIVIDUAL");
        assertTrue(l1Individual.contains(contactArea.getId().toString()));
        assertFalse(l1Individual.contains(highlyArea.getId().toString()));
        assertFalse(availableAreas(userL1, "GROUP").contains(contactArea.getId().toString()));

        String l2Individual = availableAreas(userL2, "INDIVIDUAL");
        assertTrue(l2Individual.contains(highlyArea.getId().toString()));
        assertFalse(l2Individual.contains(contactArea.getId().toString()));
        String l2Group = availableAreas(userL2, "GROUP");
        assertTrue(l2Group.contains(contactArea.getId().toString()));
        assertFalse(l2Group.contains(highlyArea.getId().toString()));

        for (var node : objectMapper.readTree(availableAreas(userL2)).path("data")) {
            String id = node.path("id").asText();
            if (id.equals(contactArea.getId().toString())) {
                assertFalse(node.path("individualRequestAllowed").asBoolean());
                assertTrue(node.path("groupRequestAllowed").asBoolean());
            }
            if (id.equals(highlyArea.getId().toString())) {
                assertTrue(node.path("individualRequestAllowed").asBoolean());
                assertFalse(node.path("groupRequestAllowed").asBoolean());
            }
        }
        assertEquals(400, mockMvc.perform(get("/api/access-requests/available-areas").param("type", "OTHER")
                .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(userL2))).andReturn().getResponse().getStatus());
    }

    @Test
    @DisplayName("A-06 (BR-RQ-28): đơn nhóm Tuyệt mật theo ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE: mặc định không có trong danh sách của L3 và tạo -> 400; bật cấu hình -> có, groupRequestAllowed true, tạo -> 201")
    void highlyConfidential_groupFlagFollowsConfig() throws Exception {
        assertNull(groupRequestAllowed(requester, highlyArea), "mặc định: L3 không xin được gì ở Tuyệt mật");
        assertEquals(Boolean.TRUE, groupRequestAllowed(requester, contactArea), "Bảo mật luôn nhận đơn nhóm");
        MvcResult rejected = create("/api/access-requests/group", highlyArea, List.of(member.getUserCode()));
        assertEquals(400, rejected.getResponse().getStatus(), body(rejected));

        systemConfigService.update(GROUP_HC_KEY, "true", "Bật đơn nhóm Tuyệt mật cho test A-06", admin.getEmail());

        assertEquals(Boolean.TRUE, groupRequestAllowed(requester, highlyArea), "bật cấu hình: Tuyệt mật nhận đơn nhóm");
        MvcResult accepted = create("/api/access-requests/group", highlyArea, List.of(member.getUserCode()));
        assertEquals(201, accepted.getResponse().getStatus(), body(accepted));
    }
}
