package com.fa26se040.security.controller;

import com.fa26se040.security.entity.Area;
import com.fa26se040.security.entity.AreaChangeLog;
import com.fa26se040.security.entity.AreaLevel;
import com.fa26se040.security.entity.Role;
import com.fa26se040.security.entity.User;
import com.fa26se040.security.repository.AreaChangeLogRepository;
import com.fa26se040.security.repository.AreaLevelRepository;
import com.fa26se040.security.repository.AreaRepository;
import com.fa26se040.security.repository.UserRepository;
import com.fa26se040.security.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AreaControllerIntegrationTest {

    @BeforeAll
    static void setupFlyway(@Autowired Flyway flyway) {
        flyway.clean();
        flyway.migrate();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private AreaLevelRepository areaLevelRepository;

    @Autowired
    private AreaChangeLogRepository areaChangeLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String adminToken;
    private String fmToken;
    private String guardToken;
    private User adminUser;
    private User fmUser;

    @BeforeEach
    void setUp() {
        areaChangeLogRepository.deleteAll();
        areaRepository.deleteAll();

        adminUser = userRepository.findByEmail("admin_it@test.local").orElseGet(() ->
                userRepository.save(User.builder()
                        .fullName("Admin IT")
                        .userCode("ADM_IT")
                        .role(Role.ADMIN)
                        .email("admin_it@test.local")
                        .isActive(true)
                        .build())
        );

        fmUser = userRepository.findByEmail("fm_it@test.local").orElseGet(() ->
                userRepository.save(User.builder()
                        .fullName("FM IT")
                        .userCode("FM_IT")
                        .role(Role.FACILITY_MANAGER)
                        .email("fm_it@test.local")
                        .isActive(true)
                        .build())
        );

        User guardUser = userRepository.findByEmail("guard_it@test.local").orElseGet(() ->
                userRepository.save(User.builder()
                        .fullName("Guard IT")
                        .userCode("GUARD_IT")
                        .role(Role.INTERNAL_GUARD)
                        .email("guard_it@test.local")
                        .isActive(true)
                        .build())
        );

        adminToken = jwtTokenProvider.generateToken(adminUser);
        fmToken = jwtTokenProvider.generateToken(fmUser);
        guardToken = jwtTokenProvider.generateToken(guardUser);
    }

    @Test
    @DisplayName("I1: ADMIN tạo khu vực -> 201 Created, body có id")
    void shouldAllowAdminToCreateArea() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("code", "server-b01");
        req.put("name", "Phòng server toà B tầng 1");
        req.put("areaLevel", 3);
        req.put("building", "B");
        req.put("floor", "1");
        req.put("description", "Chứa tủ rack");
        req.put("mapX", 320.50);
        req.put("mapY", 210.00);

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.code", is("SERVER-B01")));
    }

    @Test
    @DisplayName("I2: Tạo trùng code đang hoạt động -> 409 ERR_AREA_001")
    void shouldRejectDuplicateActiveAreaCode() throws Exception {
        createAreaInDb("SERVER-B01", "Phòng server", (short) 3);

        Map<String, Object> req = new HashMap<>();
        req.put("code", "SERVER-B01");
        req.put("name", "Phòng server trùng mã");
        req.put("areaLevel", 3);

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_AREA_001")));
    }

    @Test
    @DisplayName("I3: Ngừng khu vực rồi tạo lại đúng code đó -> 201 Created (bắt lỗi thiếu partial index)")
    void shouldAllowRecreatingAreaWithSameCodeAfterDeactivation() throws Exception {
        Area area = createAreaInDb("SERVER-B01", "Phòng server cũ", (short) 3);

        // Deactivate area
        mockMvc.perform(delete("/api/areas/" + area.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Recreate with exact same code
        Map<String, Object> req = new HashMap<>();
        req.put("code", "SERVER-B01");
        req.put("name", "Phòng server mới");
        req.put("areaLevel", 3);

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", is("SERVER-B01")));
    }

    @Test
    @DisplayName("I4: FACILITY_MANAGER gọi POST /api/areas -> 403 Forbidden")
    void shouldDenyFacilityManagerFromCreatingArea() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("code", "SERVER-FM");
        req.put("name", "Phòng FM tạo");
        req.put("areaLevel", 2);

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", "Bearer " + fmToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("I5: FACILITY_MANAGER gọi GET /api/areas -> 200 OK")
    void shouldAllowFacilityManagerToGetAreas() throws Exception {
        createAreaInDb("SERVER-B01", "Phòng server", (short) 3);

        mockMvc.perform(get("/api/areas")
                        .header("Authorization", "Bearer " + fmToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("I6: INTERNAL_GUARD gọi GET /api/areas -> 403 Forbidden")
    void shouldDenyInternalGuardFromGettingAreas() throws Exception {
        mockMvc.perform(get("/api/areas")
                        .header("Authorization", "Bearer " + guardToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @Disabled("Chờ M01 bổ sung authenticationEntryPoint — xem M06_KNOWN_ISSUES.md")
    @DisplayName("I7: Không có token -> 401 Unauthorized")
    void shouldDenyUnauthenticatedRequest() throws Exception {
        mockMvc.perform(get("/api/areas"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("I8: PUT gửi code khác -> 400 ERR_AREA_007")
    void shouldRejectPutWithDifferentCode() throws Exception {
        Area area = createAreaInDb("SERVER-B01", "Phòng server", (short) 3);

        Map<String, Object> req = new HashMap<>();
        req.put("code", "KHAC-MA");
        req.put("name", "Phòng server đổi tên");
        req.put("areaLevel", 3);

        mockMvc.perform(put("/api/areas/" + area.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_AREA_007")));
    }

    @Test
    @DisplayName("I9: PUT gửi code trùng khác hoa thường -> 200 OK, code không đổi")
    void shouldAllowPutWithSameCodeDifferentCase() throws Exception {
        Area area = createAreaInDb("SERVER-B01", "Phòng server", (short) 3);

        Map<String, Object> req = new HashMap<>();
        req.put("code", "server-b01");
        req.put("name", "Phòng server cập nhật");
        req.put("areaLevel", 3);

        mockMvc.perform(put("/api/areas/" + area.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("SERVER-B01")))
                .andExpect(jsonPath("$.name", is("Phòng server cập nhật")));
    }

    @Test
    @DisplayName("I10: Ngừng khu vực -> 204 No Content; GET mặc định không thấy, ?isActive=false thấy")
    void shouldDeactivateAreaAndFilterInGet() throws Exception {
        Area area = createAreaInDb("SERVER-B01", "Phòng server", (short) 3);

        mockMvc.perform(delete("/api/areas/" + area.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Default GET (isActive=true) should be empty
        mockMvc.perform(get("/api/areas")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));

        // GET with isActive=false should return deactivated area
        mockMvc.perform(get("/api/areas?isActive=false")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].isActive", is(false)));
    }

    @Test
    @DisplayName("I11: Ngừng khu vực đã ngừng -> 404 ERR_AREA_002")
    void shouldReturn404WhenDeactivatingAlreadyDeactivatedArea() throws Exception {
        Area area = createAreaInDb("SERVER-B01", "Phòng server", (short) 3);

        // Deactivate once
        mockMvc.perform(delete("/api/areas/" + area.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());

        // Deactivate second time
        mockMvc.perform(delete("/api/areas/" + area.getId())
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ERR_AREA_002")));
    }

    @Test
    @DisplayName("I12: Hạ cấp có reason hợp lệ -> 200 OK, area_change_logs có dòng UPDATE chứa reason")
    void shouldLogReasonWhenDowngradingAreaLevel() throws Exception {
        Area area = createAreaInDb("SERVER-B01", "Phòng server", (short) 3);

        Map<String, Object> req = new HashMap<>();
        req.put("name", "Phòng học thông thường");
        req.put("areaLevel", 1);
        req.put("reason", "Chuyển đổi mục đích sử dụng làm phòng học");

        mockMvc.perform(put("/api/areas/" + area.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.level.level", is(1)));

        List<AreaChangeLog> logs = areaChangeLogRepository.findAll();
        assertEquals(1, logs.size());
        assertEquals("UPDATE", logs.get(0).getAction());
        assertEquals("Chuyển đổi mục đích sử dụng làm phòng học", logs.get(0).getReason());
    }

    @Test
    @DisplayName("I13: ?keyword=server -> Khớp cả code và name, bỏ qua hoa thường")
    void shouldSearchByKeywordMatchingCodeAndNameCaseInsensitive() throws Exception {
        createAreaInDb("SERVER-B01", "Phòng máy tính", (short) 3);
        createAreaInDb("LAB-A01", "Phòng Server phụ", (short) 2);
        createAreaInDb("ROOM-101", "Phòng học thường", (short) 1);

        mockMvc.perform(get("/api/areas?keyword=server")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
    }

    @Test
    @DisplayName("I14: POST với areaLevel = 9 -> 400 ERR_AREA_003")
    void shouldRejectCreateWithInvalidAreaLevel() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("code", "SERVER-B09");
        req.put("name", "Phòng server sai level");
        req.put("areaLevel", 9);

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_AREA_003")));
    }

    @Test
    @DisplayName("I15: Mọi thao tác ghi -> area_change_logs có đúng 1 dòng mới")
    void shouldCreateExactlyOneChangeLogEntryPerWriteOperation() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("code", "SERVER-B01");
        req.put("name", "Phòng server");
        req.put("areaLevel", 3);

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

        List<AreaChangeLog> logs = areaChangeLogRepository.findAll();
        assertEquals(1, logs.size());
        assertEquals("CREATE", logs.get(0).getAction());
        assertNotNull(logs.get(0).getActorId());
    }

    private Area createAreaInDb(String code, String name, Short levelNum) {
        AreaLevel level = areaLevelRepository.findById(levelNum).orElseThrow();
        Area area = Area.builder()
                .code(code)
                .name(name)
                .areaLevel(level)
                .building("A")
                .floor("1")
                .isActive(true)
                .createdBy(adminUser.getId())
                .updatedBy(adminUser.getId())
                .build();
        return areaRepository.save(area);
    }
}
