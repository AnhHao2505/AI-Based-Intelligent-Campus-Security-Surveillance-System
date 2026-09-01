package com.fa26se040.icss.controller;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaLevel;
import com.fa26se040.icss.entity.AreaTemporaryUsage;
import com.fa26se040.icss.entity.AreaTemporaryUsageChangeLog;
import com.fa26se040.icss.entity.Role;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.repository.AreaChangeLogRepository;
import com.fa26se040.icss.repository.AreaLevelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.AreaTemporaryUsageChangeLogRepository;
import com.fa26se040.icss.repository.AreaTemporaryUsageRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AreaTemporaryUsageControllerIntegrationTest {

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
    private AreaTemporaryUsageRepository temporaryUsageRepository;

    @Autowired
    private AreaTemporaryUsageChangeLogRepository temporaryUsageChangeLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String adminToken;
    private String fmToken;
    private String guardToken;
    private User adminUser;
    private Area defaultArea;

    @BeforeEach
    void setUp() {
        temporaryUsageChangeLogRepository.deleteAll();
        temporaryUsageRepository.deleteAll();
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

        User fmUser = userRepository.findByEmail("fm_it@test.local").orElseGet(() ->
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

        AreaLevel level = areaLevelRepository.findById((short) 2).orElseThrow();
        defaultArea = areaRepository.save(Area.builder()
                .code("HALL-A01")
                .name("Hội trường A01")
                .areaLevel(level)
                .building("A")
                .floor("1")
                .isActive(true)
                .createdBy(adminUser.getId())
                .updatedBy(adminUser.getId())
                .build());
    }

    // =========================================================================
    // CREATE TESTS (C1 - C16)
    // =========================================================================

    @Test
    @DisplayName("C1: ADMIN tạo valid temporary usage -> 201 Created")
    void c1_shouldAllowAdminToCreateValidTemporaryUsage() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime start = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end = base.withHour(12).withMinute(0).withSecond(0).withNano(0);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Seminar AI Security");
        req.put("startTime", start.toString());
        req.put("endTime", end.toString());
        req.put("reason", "Cho phép sử dụng phòng phục vụ seminar");

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.areaId", is(defaultArea.getId().toString())))
                .andExpect(jsonPath("$.eventName", is("Seminar AI Security")))
                .andExpect(jsonPath("$.reason", is("Cho phép sử dụng phòng phục vụ seminar")))
                .andExpect(jsonPath("$.createdBy", is(adminUser.getId().toString())));
    }

    @Test
    @DisplayName("C2: Boundary adjacent (existing 08:00–10:00, new 10:00–12:00) -> 201 Created")
    void c2_shouldAllowAdjacentBoundaryInterval() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime start1 = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end1 = base.withHour(10).withMinute(0).withSecond(0).withNano(0);

        createUsageInDb(defaultArea, "Event 1", start1, end1, "Reason 1");

        OffsetDateTime start2 = base.withHour(10).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end2 = base.withHour(12).withMinute(0).withSecond(0).withNano(0);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event 2 Liền kề");
        req.put("startTime", start2.toString());
        req.put("endTime", end2.toString());
        req.put("reason", "Event tiếp nối liền kề");

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventName", is("Event 2 Liền kề")));
    }

    @Test
    @DisplayName("C3: Area không tồn tại -> 404 ERR_AREA_002")
    void c3_shouldRejectCreateForNonExistentArea() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event");
        req.put("startTime", base.withHour(8).toString());
        req.put("endTime", base.withHour(10).toString());

        mockMvc.perform(post("/api/areas/" + UUID.randomUUID() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ERR_AREA_002")));
    }

    @Test
    @DisplayName("C4: Area đã deactivate -> 404 ERR_AREA_002")
    void c4_shouldRejectCreateForDeactivatedArea() throws Exception {
        defaultArea.setIsActive(false);
        defaultArea.setDeletedAt(OffsetDateTime.now());
        areaRepository.save(defaultArea);

        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event");
        req.put("startTime", base.withHour(8).toString());
        req.put("endTime", base.withHour(10).toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ERR_AREA_002")));
    }

    @Test
    @DisplayName("C5: eventName null/blank -> 400 Bad Request")
    void c5_shouldRejectCreateWithBlankEventName() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "   ");
        req.put("startTime", base.withHour(8).toString());
        req.put("endTime", base.withHour(10).toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("C6: eventName > 150 chars -> 400 Bad Request")
    void c6_shouldRejectCreateWithTooLongEventName() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "A".repeat(151));
        req.put("startTime", base.withHour(8).toString());
        req.put("endTime", base.withHour(10).toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("C7: endTime == startTime -> 400 ERR_TEMP_USAGE_001")
    void c7_shouldRejectCreateWhenEndTimeEqualsStartTime() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime time = base.withHour(8).withMinute(0).withSecond(0).withNano(0);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event");
        req.put("startTime", time.toString());
        req.put("endTime", time.toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_001")));
    }

    @Test
    @DisplayName("C8: endTime < startTime -> 400 ERR_TEMP_USAGE_001")
    void c8_shouldRejectCreateWhenEndTimeIsBeforeStartTime() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event");
        req.put("startTime", base.withHour(10).toString());
        req.put("endTime", base.withHour(8).toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_001")));
    }

    @Test
    @DisplayName("C9: endTime <= current time -> 400 ERR_TEMP_USAGE_002")
    void c9_shouldRejectCreateWhenEndTimeIsInThePast() throws Exception {
        OffsetDateTime pastStart = OffsetDateTime.now().minusHours(4).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime pastEnd = OffsetDateTime.now().minusHours(1).truncatedTo(ChronoUnit.SECONDS);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event Quá Khứ");
        req.put("startTime", pastStart.toString());
        req.put("endTime", pastEnd.toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_002")));
    }

    @Test
    @DisplayName("C10: new interval nằm hoàn toàn trong existing (existing 08:00–12:00, new 09:00–10:00) -> 409")
    void c10_shouldRejectWhenNewIsInsideExisting() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        createUsageInDb(defaultArea, "Event Lớn", base.withHour(8), base.withHour(12), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event Con");
        req.put("startTime", base.withHour(9).toString());
        req.put("endTime", base.withHour(10).toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_003")));
    }

    @Test
    @DisplayName("C11: new interval bao phủ existing (existing 09:00–10:00, new 08:00–12:00) -> 409")
    void c11_shouldRejectWhenNewEnclosesExisting() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        createUsageInDb(defaultArea, "Event Nhỏ", base.withHour(9), base.withHour(10), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event Bao Phủ");
        req.put("startTime", base.withHour(8).toString());
        req.put("endTime", base.withHour(12).toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_003")));
    }

    @Test
    @DisplayName("C12: overlap phần đầu (existing 10:00–12:00, new 09:00–11:00) -> 409")
    void c12_shouldRejectWhenNewOverlapsStartOfExisting() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        createUsageInDb(defaultArea, "Event Sau", base.withHour(10), base.withHour(12), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event Trùng Đầu");
        req.put("startTime", base.withHour(9).toString());
        req.put("endTime", base.withHour(11).toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_003")));
    }

    @Test
    @DisplayName("C13: overlap phần cuối (existing 08:00–10:00, new 09:00–11:00) -> 409")
    void c13_shouldRejectWhenNewOverlapsEndOfExisting() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        createUsageInDb(defaultArea, "Event Trước", base.withHour(8), base.withHour(10), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event Trùng Cuối");
        req.put("startTime", base.withHour(9).toString());
        req.put("endTime", base.withHour(11).toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_003")));
    }

    @Test
    @DisplayName("C14: FACILITY_MANAGER POST -> 403 Forbidden")
    void c14_shouldDenyFacilityManagerFromCreatingUsage() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event FM");
        req.put("startTime", base.withHour(8).toString());
        req.put("endTime", base.withHour(10).toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + fmToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("C15: INTERNAL_GUARD POST -> 403 Forbidden")
    void c15_shouldDenyInternalGuardFromCreatingUsage() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event Guard");
        req.put("startTime", base.withHour(8).toString());
        req.put("endTime", base.withHour(10).toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .header("Authorization", "Bearer " + guardToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("C16: no JWT -> 401 Unauthorized")
    void c16_shouldDenyUnauthenticatedRequest() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);

        Map<String, Object> req = new HashMap<>();
        req.put("eventName", "Event No Auth");
        req.put("startTime", base.withHour(8).toString());
        req.put("endTime", base.withHour(10).toString());

        mockMvc.perform(post("/api/areas/" + defaultArea.getId() + "/temporary-usages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // EXTEND TESTS (E1 - E21)
    // =========================================================================

    @Test
    @DisplayName("E1: ADMIN extend thành công: current 08:00–10:00, newEndTime 11:00 -> 200 OK")
    void e1_shouldAllowAdminToExtendTemporaryUsage() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime startTime = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime endTime = base.withHour(10).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime newEndTime = base.withHour(11).withMinute(0).withSecond(0).withNano(0);

        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Hội thảo Công nghệ", startTime, endTime, "Lý do ban đầu");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", newEndTime.toString());
        req.put("reason", "Hội thảo kéo dài thêm 1 tiếng do Q&A");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(usage.getId().toString())))
                .andExpect(jsonPath("$.areaId", is(defaultArea.getId().toString())));
    }

    @Test
    @DisplayName("E2: Verify fields không đổi: id, areaId, eventName, original reason, startTime không đổi; endTime đổi; updatedBy = current ADMIN")
    void e2_shouldVerifyFieldsAfterExtension() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime startTime = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime endTime = base.withHour(10).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime newEndTime = base.withHour(11).withMinute(0).withSecond(0).withNano(0);

        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Seminar AI", startTime, endTime, "Lý do khởi tạo ban đầu");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", newEndTime.toString());
        req.put("reason", "Lý do gia hạn thêm giờ");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(usage.getId().toString())))
                .andExpect(jsonPath("$.areaId", is(defaultArea.getId().toString())))
                .andExpect(jsonPath("$.eventName", is("Seminar AI")))
                .andExpect(jsonPath("$.reason", is("Lý do khởi tạo ban đầu")))
                .andExpect(jsonPath("$.startTime").exists())
                .andExpect(jsonPath("$.endTime").exists())
                .andExpect(jsonPath("$.updatedBy", is(adminUser.getId().toString())));

        AreaTemporaryUsage updated = temporaryUsageRepository.findById(usage.getId()).orElseThrow();
        assertEquals(usage.getId(), updated.getId());
        assertEquals(defaultArea.getId(), updated.getArea().getId());
        assertEquals("Seminar AI", updated.getEventName());
        assertEquals("Lý do khởi tạo ban đầu", updated.getReason());
        assertEquals(startTime.toInstant(), updated.getStartTime().toInstant());
        assertEquals(newEndTime.toInstant(), updated.getEndTime().toInstant());
        assertEquals(adminUser.getId(), updated.getUpdatedBy());
    }

    @Test
    @DisplayName("E3: Boundary adjacent: current 08:00–10:00, other 12:00–14:00, extend current 12:00 -> 200 OK")
    void e3_shouldAllowExtensionTouchingNextEventBoundary() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime start1 = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end1 = base.withHour(10).withMinute(0).withSecond(0).withNano(0);

        OffsetDateTime start2 = base.withHour(12).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end2 = base.withHour(14).withMinute(0).withSecond(0).withNano(0);

        AreaTemporaryUsage currentUsage = createUsageInDb(defaultArea, "Event 1", start1, end1, "Reason 1");
        createUsageInDb(defaultArea, "Event 2", start2, end2, "Reason 2");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", start2.toString()); // Gia hạn đúng 12:00
        req.put("reason", "Lý do gia hạn tới sát giờ");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + currentUsage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endTime").exists());

        AreaTemporaryUsage updated = temporaryUsageRepository.findById(currentUsage.getId()).orElseThrow();
        assertEquals(start2.toInstant(), updated.getEndTime().toInstant());
    }

    @Test
    @DisplayName("E4: temporaryUsageId không tồn tại -> 404 ERR_TEMP_USAGE_004")
    void e4_shouldReturn404WhenTemporaryUsageNotFound() throws Exception {
        OffsetDateTime newEndTime = OffsetDateTime.now().plusDays(2);
        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", newEndTime.toString());
        req.put("reason", "Lý do gia hạn kiểm thử 404");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + UUID.randomUUID() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_004")));
    }

    @Test
    @DisplayName("E5: usage thuộc Area khác -> 404 ERR_TEMP_USAGE_004")
    void e5_shouldReturn404WhenUsageBelongsToAnotherArea() throws Exception {
        AreaLevel level = areaLevelRepository.findById((short) 1).orElseThrow();
        Area otherArea = areaRepository.save(Area.builder()
                .code("ROOM-B02")
                .name("Phòng học B02")
                .areaLevel(level)
                .isActive(true)
                .createdBy(adminUser.getId())
                .updatedBy(adminUser.getId())
                .build());

        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime startTime = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime endTime = base.withHour(10).withMinute(0).withSecond(0).withNano(0);

        AreaTemporaryUsage usageInOtherArea = createUsageInDb(otherArea, "Event ở B02", startTime, endTime, "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", base.withHour(11).toString());
        req.put("reason", "Lý do gia hạn thử chéo Area");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usageInOtherArea.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_004")));
    }

    @Test
    @DisplayName("E6: Area đã deactivate -> 404 ERR_AREA_002")
    void e6_shouldRejectExtensionWhenAreaIsDeactivated() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", base.withHour(8), base.withHour(10), "Reason");

        // Deactivate Area
        defaultArea.setIsActive(false);
        defaultArea.setDeletedAt(OffsetDateTime.now());
        areaRepository.save(defaultArea);

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", base.withHour(11).toString());
        req.put("reason", "Lý do gia hạn area đã xóa");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("ERR_AREA_002")));
    }

    @Test
    @DisplayName("E7: newEndTime == currentEndTime -> 400 ERR_TEMP_USAGE_005")
    void e7_shouldRejectExtensionWhenNewEndTimeEqualsCurrentEndTime() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime startTime = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime endTime = base.withHour(10).withMinute(0).withSecond(0).withNano(0);

        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", startTime, endTime, "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", endTime.toString()); // Bằng đúng endTime cũ
        req.put("reason", "Lý do gia hạn không đổi giờ");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_005")));
    }

    @Test
    @DisplayName("E8: newEndTime < currentEndTime -> 400 ERR_TEMP_USAGE_005")
    void e8_shouldRejectExtensionWhenNewEndTimeIsBeforeCurrentEndTime() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime startTime = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime endTime = base.withHour(10).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime newEndTime = base.withHour(9).withMinute(0).withSecond(0).withNano(0);

        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", startTime, endTime, "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", newEndTime.toString()); // Nhỏ hơn endTime cũ
        req.put("reason", "Lý do rút ngắn thời gian");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_005")));
    }

    @Test
    @DisplayName("E9: current temporary usage đã hết hạn -> 409 ERR_TEMP_USAGE_006")
    void e9_shouldRejectExtensionWhenUsageAlreadyExpired() throws Exception {
        OffsetDateTime startTime = OffsetDateTime.now().minusHours(4).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime endTime = OffsetDateTime.now().minusHours(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime newEndTime = OffsetDateTime.now().plusHours(1).truncatedTo(ChronoUnit.SECONDS);

        AreaTemporaryUsage expiredUsage = createUsageInDb(defaultArea, "Event đã qua", startTime, endTime, "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", newEndTime.toString());
        req.put("reason", "Lý do gia hạn event đã hết hạn");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + expiredUsage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_006")));
    }

    @Test
    @DisplayName("E10: Extend vào event tiếp theo (12:01) -> 409 ERR_TEMP_USAGE_003")
    void e10_shouldRejectExtensionOverlappingWithNextEvent() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime start1 = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end1 = base.withHour(10).withMinute(0).withSecond(0).withNano(0);

        OffsetDateTime start2 = base.withHour(12).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end2 = base.withHour(14).withMinute(0).withSecond(0).withNano(0);

        AreaTemporaryUsage currentUsage = createUsageInDb(defaultArea, "Event 1", start1, end1, "Reason 1");
        createUsageInDb(defaultArea, "Event 2", start2, end2, "Reason 2");

        OffsetDateTime newEndTime = base.withHour(12).withMinute(1).withSecond(0).withNano(0); // 12:01

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", newEndTime.toString());
        req.put("reason", "Lý do gia hạn chạm vào event 2");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + currentUsage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_003")));
    }

    @Test
    @DisplayName("E11: Verify chính record hiện tại được exclude khỏi overlap query")
    void e11_shouldExcludeSelfFromOverlapQuery() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime start1 = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end1 = base.withHour(10).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime newEndTime = base.withHour(11).withMinute(0).withSecond(0).withNano(0);

        AreaTemporaryUsage currentUsage = createUsageInDb(defaultArea, "Event duy nhất", start1, end1, "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", newEndTime.toString());
        req.put("reason", "Lý do gia hạn event duy nhất");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + currentUsage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("E12: reason null -> 400 ERR_TEMP_USAGE_007")
    void e12_shouldRejectExtendWithNullReason() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", base.withHour(8), base.withHour(10), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", base.withHour(11).toString());
        req.put("reason", null);

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_007")));
    }

    @Test
    @DisplayName("E13a: reason = \"\" (empty) -> 400 ERR_TEMP_USAGE_007")
    void e13a_shouldRejectExtendWithEmptyReason() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", base.withHour(8), base.withHour(10), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", base.withHour(11).toString());
        req.put("reason", "");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_007")));
    }

    @Test
    @DisplayName("E13b: reason = \"     \" (blank whitespace) -> 400 ERR_TEMP_USAGE_007")
    void e13b_shouldRejectExtendWithWhitespaceReason() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", base.withHour(8), base.withHour(10), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", base.withHour(11).toString());
        req.put("reason", "     ");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_007")));
    }

    @Test
    @DisplayName("E14: reason < 10 chars -> 400 ERR_TEMP_USAGE_007")
    void e14_shouldRejectExtendWithTooShortReason() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", base.withHour(8), base.withHour(10), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", base.withHour(11).toString());
        req.put("reason", "123456789"); // 9 chars

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_007")));
    }

    @Test
    @DisplayName("E15: reason > 255 chars -> 400 ERR_TEMP_USAGE_007")
    void e15_shouldRejectExtendWithTooLongReason() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", base.withHour(8), base.withHour(10), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", base.withHour(11).toString());
        req.put("reason", "A".repeat(256));

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_TEMP_USAGE_007")));
    }

    @Test
    @DisplayName("E16: FACILITY_MANAGER -> 403 Forbidden")
    void e16_shouldDenyFacilityManagerFromExtendingUsage() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", base.withHour(8), base.withHour(10), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", base.withHour(11).toString());
        req.put("reason", "FM cố gắng gia hạn");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + fmToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E17: INTERNAL_GUARD -> 403 Forbidden")
    void e17_shouldDenyInternalGuardFromExtendingUsage() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", base.withHour(8), base.withHour(10), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", base.withHour(11).toString());
        req.put("reason", "Guard cố gắng gia hạn");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + guardToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("E18: no JWT -> 401 Unauthorized")
    void e18_shouldDenyUnauthenticatedExtendRequest() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", base.withHour(8), base.withHour(10), "Reason");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", base.withHour(11).toString());
        req.put("reason", "Không có token gia hạn");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("E19: Extend thành công tạo đúng 1 audit log")
    void e19_shouldCreateExactlyOneAuditLogOnExtension() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime start = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end = base.withHour(10).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime newEnd = base.withHour(11).withMinute(0).withSecond(0).withNano(0);

        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", start, end, "Reason gốc");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", newEnd.toString());
        req.put("reason", "Lý do gia hạn kiểm tra audit");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        List<AreaTemporaryUsageChangeLog> logs = temporaryUsageChangeLogRepository.findByTemporaryUsageIdOrderByCreatedAtDesc(usage.getId());
        assertEquals(1, logs.size());
    }

    @Test
    @DisplayName("E20: Audit log có: action = EXTEND, temporaryUsageId đúng, actorId đúng, oldEndTime đúng, newEndTime đúng, reason đúng")
    void e20_shouldRecordCorrectDetailsInAuditLog() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime start = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end = base.withHour(10).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime newEnd = base.withHour(11).withMinute(0).withSecond(0).withNano(0);

        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", start, end, "Reason gốc");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", newEnd.toString());
        req.put("reason", "Gia hạn hội thảo chuyên đề");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        List<AreaTemporaryUsageChangeLog> logs = temporaryUsageChangeLogRepository.findByTemporaryUsageIdOrderByCreatedAtDesc(usage.getId());
        assertEquals(1, logs.size());
        AreaTemporaryUsageChangeLog log = logs.get(0);
        assertEquals("EXTEND", log.getAction());
        assertEquals(usage.getId(), log.getTemporaryUsageId());
        assertEquals(adminUser.getId(), log.getActorId());
        assertEquals(end.toInstant(), log.getOldEndTime().toInstant());
        assertEquals(newEnd.toInstant(), log.getNewEndTime().toInstant());
        assertEquals("Gia hạn hội thảo chuyên đề", log.getReason());
        assertNotNull(log.getCreatedAt());
    }

    @Test
    @DisplayName("E21: Verify original temporary usage reason không bị overwrite bởi extend reason")
    void e21_shouldNotOverwriteOriginalReasonWhenExtending() throws Exception {
        OffsetDateTime base = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.SECONDS);
        OffsetDateTime start = base.withHour(8).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime end = base.withHour(10).withMinute(0).withSecond(0).withNano(0);
        OffsetDateTime newEnd = base.withHour(11).withMinute(0).withSecond(0).withNano(0);

        AreaTemporaryUsage usage = createUsageInDb(defaultArea, "Event", start, end, "Lý do ban đầu không đổi");

        Map<String, Object> req = new HashMap<>();
        req.put("newEndTime", newEnd.toString());
        req.put("reason", "Lý do gia hạn hoàn toàn khác");

        mockMvc.perform(patch("/api/areas/" + defaultArea.getId() + "/temporary-usages/" + usage.getId() + "/extend")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason", is("Lý do ban đầu không đổi")));

        AreaTemporaryUsage updated = temporaryUsageRepository.findById(usage.getId()).orElseThrow();
        assertEquals("Lý do ban đầu không đổi", updated.getReason());
    }

    // =========================================================================
    // HELPER
    // =========================================================================

    private AreaTemporaryUsage createUsageInDb(Area area, String eventName, OffsetDateTime start, OffsetDateTime end, String reason) {
        AreaTemporaryUsage usage = AreaTemporaryUsage.builder()
                .area(area)
                .eventName(eventName)
                .reason(reason)
                .startTime(start)
                .endTime(end)
                .createdBy(adminUser.getId())
                .updatedBy(adminUser.getId())
                .build();
        return temporaryUsageRepository.save(usage);
    }
}
