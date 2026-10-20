package com.fa26se040.icss.accesshistory;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.CameraAccessLogRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
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
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Lịch sử ra vào (GET /api/access-history/my, GET /api/access-history).
 * - NORMAL_USER chỉ thấy log của chính mình; API tổng chỉ FACILITY_MANAGER / ADMIN; GUARD bị 403 ở cả hai.
 * - Lọc theo khu vực / kết quả / thời gian / mã người; from > to → 400 ERR_AC_010; mặc định 7 ngày gần nhất.
 * - Không trả user_access_level.
 * @Transactional: fixture (người, khu vực, log) rollback sau mỗi test; mọi đếm đều giới hạn theo fixture riêng.
 */
@Transactional
class AccessHistoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private CameraAccessLogRepository cameraAccessLogRepository;

    private String suffix;
    private OffsetDateTime now;
    private User student1;
    private User student2;
    private User fm;
    private User admin;
    private User guard;
    private Area areaA;
    private Area areaB;

    private CameraAccessLog s1AllowedA;
    private CameraAccessLog s1DeniedB;
    private CameraAccessLog s2DeniedA;
    private CameraAccessLog s1OldA;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa lịch sử ra vào")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa lịch sử ra vào").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa lịch sử ra vào", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));

        student1 = user("s1", Role.NORMAL_USER);
        student2 = user("s2", Role.NORMAL_USER);
        fm = user("fm", Role.FACILITY_MANAGER);
        admin = user("adm", Role.ADMIN);
        guard = user("grd", Role.GUARD);
        areaA = area("A", building, floor);
        areaB = area("B", building, floor);

        s1AllowedA = log(student1, areaA, AccessResult.AUTHORIZED_ACCESS, now.minusHours(1));
        s1DeniedB = log(student1, areaB, AccessResult.UNAUTHORIZED_ACCESS, now.minusHours(2));
        s2DeniedA = log(student2, areaA, AccessResult.UNAUTHORIZED_ACCESS, now.minusMinutes(30));
        // Ngoài khoảng mặc định 7 ngày
        s1OldA = log(student1, areaA, AccessResult.ACCESS_OUT_OF_TIME, now.minusDays(10));
    }

    private User user(String tag, Role role) {
        return userRepository.save(User.builder()
                .userCode("AH-" + tag.toUpperCase() + "-" + suffix)
                .fullName("Lịch sử " + tag + " " + suffix)
                .email("ah-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(2)
                .isActive(true)
                .build());
    }

    private Area area(String tag, Building building, Floor floor) {
        return areaRepository.save(Area.builder()
                .name("AH khu " + tag + " " + suffix)
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

    private CameraAccessLog log(User u, Area a, AccessResult result, OffsetDateTime at) {
        return cameraAccessLogRepository.saveAndFlush(CameraAccessLog.builder()
                .cameraCode("AH-CAM-" + suffix)
                .area(a)
                .user(u)
                .userCode(u != null ? u.getUserCode() : "UNKNOWN")
                .fullName(u != null ? u.getFullName() : null)
                .userAccessLevel(u != null ? u.getAccessLevel() : null)
                .accessResult(result)
                .reasonDetails("Fixture " + result)
                .loggedAt(at)
                .build());
    }

    private MvcResult call(MockHttpServletRequestBuilder b, User actor) throws Exception {
        b.header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor));
        return mockMvc.perform(b).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Set<String> ids(MvcResult r) throws Exception {
        Set<String> ids = new HashSet<>();
        json(r).path("data").path("content").forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }

    private static String id(CameraAccessLog l) {
        return l.getId().toString();
    }

    // ------------------------------------------------------------------ /my

    @Test
    @DisplayName("/my: NORMAL_USER chỉ thấy log của chính mình (cả ALLOWED và DENIED), mặc định 7 ngày, không trả cấp truy cập")
    void my_onlyOwnLogs_default7Days() throws Exception {
        MvcResult r = call(get("/api/access-history/my"), student1);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(r)).containsExactlyInAnyOrder(id(s1AllowedA), id(s1DeniedB));
        assertThat(json(r).path("data").path("totalElements").asLong()).isEqualTo(2);
        JsonNode first = json(r).path("data").path("content").get(0);
        assertThat(first.path("id").asText()).isEqualTo(id(s1AllowedA)); // loggedAt giảm dần
        assertThat(first.has("userAccessLevel")).isFalse();
        assertThat(first.path("areaName").asText()).isEqualTo(areaA.getName());

        MvcResult r2 = call(get("/api/access-history/my"), student2);
        assertThat(ids(r2)).containsExactly(id(s2DeniedA));
    }

    @Test
    @DisplayName("/my: khoảng from/to tuỳ chọn lấy được log cũ hơn 7 ngày; không có tham số nào để xem người khác")
    void my_customRange_andIgnoresOtherUserParams() throws Exception {
        MvcResult r = call(get("/api/access-history/my")
                .param("from", now.minusDays(11).toString())
                .param("to", now.toString())
                .param("userId", student2.getId().toString())
                .param("userCode", student2.getUserCode()), student1);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(r)).containsExactlyInAnyOrder(id(s1AllowedA), id(s1DeniedB), id(s1OldA));
    }

    @Test
    @DisplayName("from > to → 400 ERR_AC_010 (cả /my và API tổng)")
    void fromAfterTo_badRequest() throws Exception {
        MvcResult my = call(get("/api/access-history/my")
                .param("from", now.toString())
                .param("to", now.minusDays(1).toString()), student1);
        assertThat(my.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(my).path("errorCode").asText()).isEqualTo("ERR_AC_010");

        MvcResult all = call(get("/api/access-history")
                .param("from", now.toString())
                .param("to", now.minusDays(1).toString()), fm);
        assertThat(all.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(all).path("errorCode").asText()).isEqualTo("ERR_AC_010");
    }

    // ------------------------------------------------------------------ phân quyền

    @Test
    @DisplayName("NORMAL_USER gọi API tổng → 403; GUARD gọi /my và API tổng → 403")
    void forbiddenRoles() throws Exception {
        assertThat(call(get("/api/access-history"), student1).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get("/api/access-history/my"), guard).getResponse().getStatus()).isEqualTo(403);
        assertThat(call(get("/api/access-history"), guard).getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("FACILITY_MANAGER và ADMIN gọi được /my (log của chính họ — ở đây rỗng)")
    void staffCanCallMy() throws Exception {
        MvcResult fmMy = call(get("/api/access-history/my"), fm);
        assertThat(fmMy.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(fmMy)).isEmpty();
        assertThat(call(get("/api/access-history/my"), admin).getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ API tổng + lọc

    @Test
    @DisplayName("FM lọc theo khu vực: chỉ log của khu đó, mọi người; có mã + họ tên, không có cấp truy cập")
    void fm_filterByArea() throws Exception {
        MvcResult r = call(get("/api/access-history").param("areaId", areaA.getId().toString()), fm);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(r)).containsExactlyInAnyOrder(id(s1AllowedA), id(s2DeniedA));
        JsonNode first = json(r).path("data").path("content").get(0);
        assertThat(first.path("id").asText()).isEqualTo(id(s2DeniedA)); // mới nhất trước
        assertThat(first.path("userCode").asText()).isEqualTo(student2.getUserCode());
        assertThat(first.path("fullName").asText()).isEqualTo(student2.getFullName());
        assertThat(first.has("userAccessLevel")).isFalse();
    }

    @Test
    @DisplayName("FM lọc theo kết quả + khu vực")
    void fm_filterByResult() throws Exception {
        MvcResult denied = call(get("/api/access-history")
                .param("areaId", areaA.getId().toString())
                .param("accessResult", "UNAUTHORIZED_ACCESS"), fm);
        assertThat(ids(denied)).containsExactly(id(s2DeniedA));

        MvcResult allowed = call(get("/api/access-history")
                .param("areaId", areaA.getId().toString())
                .param("accessResult", "AUTHORIZED_ACCESS"), fm);
        assertThat(ids(allowed)).containsExactly(id(s1AllowedA));
    }

    @Test
    @DisplayName("FM lọc theo thời gian: mặc định 7 ngày bỏ log 10 ngày trước; khoảng tuỳ chọn lấy đúng log trong khoảng")
    void fm_filterByTime() throws Exception {
        MvcResult def = call(get("/api/access-history").param("userCode", student1.getUserCode()), fm);
        assertThat(ids(def)).containsExactlyInAnyOrder(id(s1AllowedA), id(s1DeniedB));

        MvcResult old = call(get("/api/access-history")
                .param("userCode", student1.getUserCode().toLowerCase())
                .param("from", now.minusDays(11).toString())
                .param("to", now.minusDays(9).toString()), fm);
        assertThat(ids(old)).containsExactly(id(s1OldA));

        MvcResult window = call(get("/api/access-history")
                .param("cameraCode", "AH-CAM-" + suffix)
                .param("from", now.minusMinutes(90).toString())
                .param("to", now.toString()), admin);
        assertThat(window.getResponse().getStatus()).isEqualTo(200);
        assertThat(ids(window)).containsExactlyInAnyOrder(id(s1AllowedA), id(s2DeniedA));
    }
}
