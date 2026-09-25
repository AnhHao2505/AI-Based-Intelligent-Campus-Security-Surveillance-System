package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaAccessRulesUpdateRequest;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fa26se040.icss.AbstractIntegrationTest;

import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;

class AccessControlAuditLogIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.fa26se040.icss.security.JwtTokenProvider jwtTokenProvider;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private FloorRepository floorRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private AreaService areaService;

    @SpyBean
    private AccessControlAuditService auditService;

    private Floor getOrCreateTestFloor() {
        Building b = buildingRepository.findByCodeIgnoreCase("TOA_ALPHA")
                .orElseGet(() -> buildingRepository.save(Building.builder().code("TOA_ALPHA").name("Tòa Alpha").build()));
        return floorRepository.findByBuildingCodeIgnoreCaseAndFloorCodeIgnoreCase("TOA_ALPHA", "1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(b).floorCode("1").name("Tầng 1").floorOrder(1).build()));
    }

    @Test
    @Transactional
    @DisplayName("E.3: Trigger trg_access_control_audit_logs_append_only chặn UPDATE trên bảng audit log")
    void testAppendOnlyTrigger_BlocksUpdate() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User actor = User.builder()
                .userCode("ACT-" + uniqueSuffix)
                .fullName("Actor Trigger Test")
                .email("act-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        actor = userRepository.save(actor);

        UUID logId = UUID.randomUUID();
        entityManager.createNativeQuery(
                "INSERT INTO access_control_audit_logs (id, target_type, target_id, action, changed_by, reason, changed_at) " +
                        "VALUES (:id, 'LEVEL_PRESET', 'PUBLIC', 'UPDATE', :changedBy, 'Initial reason', NOW())"
        ).setParameter("id", logId).setParameter("changedBy", actor.getId()).executeUpdate();
        entityManager.flush();

        PersistenceException ex = assertThrows(PersistenceException.class, () -> {
            entityManager.createNativeQuery(
                    "UPDATE access_control_audit_logs SET reason = 'Tampered reason' WHERE id = :id"
            ).setParameter("id", logId).executeUpdate();
            entityManager.flush();
        });

        assertTrue(ex.getMessage().contains("append-only") || ex.getCause().getMessage().contains("append-only"),
                "Thông báo lỗi phải chứa thông điệp từ trigger chặn UPDATE");
    }

    @Test
    @Transactional
    @DisplayName("E.3: Trigger trg_access_control_audit_logs_append_only chặn DELETE trên bảng audit log")
    void testAppendOnlyTrigger_BlocksDelete() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User actor = User.builder()
                .userCode("ACT-" + uniqueSuffix)
                .fullName("Actor Trigger Test")
                .email("act-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        actor = userRepository.save(actor);

        UUID logId = UUID.randomUUID();
        entityManager.createNativeQuery(
                "INSERT INTO access_control_audit_logs (id, target_type, target_id, action, changed_by, reason, changed_at) " +
                        "VALUES (:id, 'LEVEL_PRESET', 'PUBLIC', 'UPDATE', :changedBy, 'Initial reason', NOW())"
        ).setParameter("id", logId).setParameter("changedBy", actor.getId()).executeUpdate();
        entityManager.flush();

        PersistenceException ex = assertThrows(PersistenceException.class, () -> {
            entityManager.createNativeQuery(
                    "DELETE FROM access_control_audit_logs WHERE id = :id"
            ).setParameter("id", logId).executeUpdate();
            entityManager.flush();
        });

        assertTrue(ex.getMessage().contains("append-only") || ex.getCause().getMessage().contains("append-only"),
                "Thông báo lỗi phải chứa thông điệp từ trigger chặn DELETE");
    }

    @Test
    @DisplayName("E.2: Rollback khi ghi log lỗi — lỗi trong quá trình ghi log khiến toàn bộ transaction rollback")
    void testTransactionRollback_WhenAuditRecordingFails_RollsBackBusinessChange() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM Integration Test")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);

        User targetUser = User.builder()
                .userCode("USR-" + uniqueSuffix)
                .fullName("User Integration Test")
                .email("usr-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();
        targetUser = userRepository.save(targetUser);

        final UUID targetId = targetUser.getId();
        final String fmEmail = fmActor.getEmail();

        try {
            // Giả lập lỗi khi ghi audit log
            doThrow(new RuntimeException("Simulated audit log write failure"))
                    .when(auditService).record(any(), any(), any(), any(), any(), any(), any(), any(), any());

            assertThrows(RuntimeException.class, () -> {
                userService.updateAccessLevel(targetId, 3, "Lý do cập nhật", fmEmail);
            });

            // Sau khi rollback, access level của targetUser trong DB vẫn phải là 1 (không bị sửa thành 3)
            User refreshed = userRepository.findById(targetId).orElseThrow();
            assertEquals(1, refreshed.getAccessLevel(),
                    "Transaction phải rollback toàn bộ, cấp độ của user phải giữ nguyên là 1");

        } finally {
            // Dọn dẹp: vì transaction rollback nên không có dòng audit nào tham chiếu đến fmActor hoặc targetUser
            // Xoá an toàn không vướng FK RESTRICT
            userRepository.deleteById(targetId);
            userRepository.deleteById(fmActor.getId());
        }
    }

    @Test
    @Transactional
    @DisplayName("E.8: Tính toán động cờ differsFromPreset (true khi khác preset, false khi khớp preset)")
    void testDiffersFromPreset_DynamicCalculation() {
        Floor floor = getOrCreateTestFloor();
        String uniqueCode = "AREA-" + UUID.randomUUID().toString().substring(0, 8);
        Area area = Area.builder()
                .name("Khu vực test differsFromPreset " + uniqueCode)
                .building("TOA_ALPHA")
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        area = areaRepository.save(area);

        // Ban đầu khớp với preset của PUBLIC (1, false) -> differsFromPreset = false
        AreaResponse resp1 = areaService.getAreaById(area.getId());
        assertFalse(resp1.differsFromPreset(), "Khớp preset thì differsFromPreset phải là false");

        // Thay đổi quy tắc khác preset (ví dụ đặt level = 2) -> differsFromPreset = true
        area.setAreaAccessLevel(2);
        areaRepository.save(area);

        AreaResponse resp2 = areaService.getAreaById(area.getId());
        assertTrue(resp2.differsFromPreset(), "Khác preset thì differsFromPreset phải là true");
    }

    @Test
    @Transactional
    @DisplayName("BR-AL-03: Gửi request qua MockMvc với lý do trống/null -> 400, dữ liệu DB không đổi và không có audit log")
    void testMissingOrBlankReason_ViaMockMvc_DoesNotChangeDataAndRecordsNoAuditLog() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM MockMvc Reason Test")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);
        String token = "Bearer " + jwtTokenProvider.generateToken(fmActor);

        User targetUser = User.builder()
                .userCode("USR-" + uniqueSuffix)
                .fullName("Target User Reason Test")
                .email("usr-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();
        targetUser = userRepository.save(targetUser);

        Floor floor = getOrCreateTestFloor();
        Area testArea = Area.builder()
                .name("Area Reason Test " + uniqueSuffix)
                .building("TOA_ALPHA")
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        testArea = areaRepository.save(testArea);
        entityManager.flush();

        // 1. Thử cập nhật User access level với reason = "" qua MockMvc -> 400
        mockMvc.perform(patch("/api/users/{id}/access-level", targetUser.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessLevel\": 3, \"reason\": \"   \"}"))
                .andExpect(status().isBadRequest());

        // 2. Thử cập nhật Area access rules với reason = null qua MockMvc -> 400
        mockMvc.perform(patch("/api/areas/{id}/access-rules", testArea.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"areaAccessLevel\": 3, \"explicitAuthorizationRequired\": true, \"reason\": null}"))
                .andExpect(status().isBadRequest());

        // 3. Thử cập nhật Preset với reason = 501 chars qua MockMvc -> 400
        mockMvc.perform(put("/api/access-control/level-presets/{areaLevel}", AreaLevel.PUBLIC)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": true, \"reason\": \"" + "a".repeat(501) + "\", \"version\": 0}"))
                .andExpect(status().isBadRequest());

        entityManager.flush();
        entityManager.clear();

        // Kiểm tra trên PostgreSQL thật: dữ liệu User không bị thay đổi
        User refreshedUser = userRepository.findById(targetUser.getId()).orElseThrow();
        assertEquals(1, refreshedUser.getAccessLevel(), "User access level trong DB phải giữ nguyên là 1");

        // Kiểm tra trên PostgreSQL thật: dữ liệu Area không bị thay đổi
        Area refreshedArea = areaRepository.findById(testArea.getId()).orElseThrow();
        assertEquals(1, refreshedArea.getAreaAccessLevel(), "Area access level trong DB phải giữ nguyên là 1");
        assertFalse(refreshedArea.getExplicitAuthorizationRequired(), "explicitAuthorizationRequired phải giữ nguyên là false");

        // Kiểm tra trên PostgreSQL thật: không có bản ghi nào được ghi vào access_control_audit_logs bởi actor này
        Number auditCount = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM access_control_audit_logs WHERE changed_by = :actorId"
        ).setParameter("actorId", fmActor.getId()).getSingleResult();
        assertEquals(0, auditCount.intValue(), "Không có bản ghi audit log nào được ghi vào DB khi request bị từ chối");
    }

    @Test
    @DisplayName("Hồi quy Lỗi 1: GET /api/access-control/audit-logs không filter -> 200 OK")
    void testSearchAuditLogs_WithoutFilter_Returns200() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM NoFilter RegTest")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);
        String token = "Bearer " + jwtTokenProvider.generateToken(fmActor);

        mockMvc.perform(get("/api/access-control/audit-logs")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("Hồi quy Lỗi 1: GET /api/access-control/audit-logs đủ filter -> 200 OK")
    void testSearchAuditLogs_WithAllFilters_Returns200() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM AllFilter RegTest")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);
        String token = "Bearer " + jwtTokenProvider.generateToken(fmActor);

        Floor floor = getOrCreateTestFloor();
        Area testArea = Area.builder()
                .name("Area Filter RegTest " + uniqueSuffix)
                .building("TOA_ALPHA")
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        testArea = areaRepository.save(testArea);

        mockMvc.perform(get("/api/access-control/audit-logs")
                        .header("Authorization", token)
                        .param("targetType", "AREA_ASSIGNMENT")
                        .param("areaId", testArea.getId().toString())
                        .param("subjectUserId", fmActor.getId().toString())
                        .param("changedBy", fmActor.getId().toString())
                        .param("from", "2026-01-01T00:00:00Z")
                        .param("to", "2026-12-31T23:59:59Z"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content").isArray());
    }

    @Test
    @DisplayName("Hồi quy Lỗi 2 — Thao tác 1 (Gán AP): commit thật, dữ liệu nghiệp vụ đổi, 1 dòng log mới, jsonb đúng giá trị")
    void testCommit_AssignPersonnel_CreatesAuditLogAndChangesBusinessData() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM AP Commit")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);
        String token = "Bearer " + jwtTokenProvider.generateToken(fmActor);

        User targetUser = User.builder()
                .userCode("USR-" + uniqueSuffix)
                .fullName("Target AP Commit")
                .email("usr-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();
        targetUser = userRepository.save(targetUser);

        Floor floor = getOrCreateTestFloor();
        Area testArea = Area.builder()
                .name("Area AP Commit " + uniqueSuffix)
                .building("TOA_ALPHA")
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        testArea = areaRepository.save(testArea);

        String createJson = String.format(
                "{\"userId\": \"%s\", \"note\": \"Trực phòng ban\", \"reason\": \"Gán nhân sự phân quyền kiểm thử\"}",
                targetUser.getId()
        );

        org.springframework.test.web.servlet.MvcResult result = mockMvc.perform(post("/api/areas/{areaId}/assigned-personnel", testArea.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson))
                .andExpect(status().isCreated())
                .andReturn();

        com.fasterxml.jackson.databind.JsonNode rootNode = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString());
        UUID apId = UUID.fromString(rootNode.get("id").asText());

        // 1. Kiểm tra trực tiếp trên bảng nghiệp vụ area_assigned_personnel
        Object noteObj = entityManager.createNativeQuery(
                "SELECT note FROM area_assigned_personnel WHERE id = :id"
        ).setParameter("id", apId).getSingleResult();
        assertEquals("Trực phòng ban", noteObj);

        // 2. Kiểm tra trực tiếp trên bảng access_control_audit_logs (1 dòng mới)
        Number count = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM access_control_audit_logs WHERE target_id = :targetId AND action = 'ASSIGN'"
        ).setParameter("targetId", apId.toString()).getSingleResult();
        assertEquals(1, count.intValue());

        // 3. Kiểm tra SQL trực tiếp trên cột jsonb: old_value IS NULL, new_value->>'status' = 'ACTIVE'
        Object oldValObj = entityManager.createNativeQuery(
                "SELECT old_value FROM access_control_audit_logs WHERE target_id = :targetId AND action = 'ASSIGN'"
        ).setParameter("targetId", apId.toString()).getSingleResult();
        assertNull(oldValObj, "Gán mới thì old_value trong DB phải là NULL");

        String statusInJson = (String) entityManager.createNativeQuery(
                "SELECT new_value ->> 'status' FROM access_control_audit_logs WHERE target_id = :targetId AND action = 'ASSIGN'"
        ).setParameter("targetId", apId.toString()).getSingleResult();
        assertEquals("ACTIVE", statusInJson);
    }

    @Test
    @DisplayName("Hồi quy Lỗi 2 — Thao tác 2 (Sửa hạn AP): commit thật, dữ liệu nghiệp vụ đổi, 1 dòng log mới, jsonb đúng giá trị")
    void testCommit_UpdateValidToPersonnel_CreatesAuditLogAndChangesBusinessData() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM AP Update")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);
        String token = "Bearer " + jwtTokenProvider.generateToken(fmActor);

        User targetUser = User.builder()
                .userCode("USR-" + uniqueSuffix)
                .fullName("Target AP Update")
                .email("usr-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();
        targetUser = userRepository.save(targetUser);

        Floor floor = getOrCreateTestFloor();
        Area testArea = Area.builder()
                .name("Area AP Update " + uniqueSuffix)
                .building("TOA_ALPHA")
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        testArea = areaRepository.save(testArea);

        // Tạo AP ban đầu
        org.springframework.test.web.servlet.MvcResult createRes = mockMvc.perform(post("/api/areas/{areaId}/assigned-personnel", testArea.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"userId\": \"%s\", \"reason\": \"Gán để sửa\"}", targetUser.getId())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID apId = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(createRes.getResponse().getContentAsString()).get("id").asText());

        // Sửa hạn
        String updateJson = "{\"validTo\": \"2027-10-01T12:00:00Z\", \"reason\": \"Gia hạn công tác\"}";
        mockMvc.perform(patch("/api/areas/{areaId}/assigned-personnel/{id}", testArea.getId(), apId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk());

        // Kiểm tra log có action = UPDATE_VALIDITY
        Number count = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM access_control_audit_logs WHERE target_id = :targetId AND action = 'UPDATE_VALIDITY'"
        ).setParameter("targetId", apId.toString()).getSingleResult();
        assertEquals(1, count.intValue());

        String validToInJson = (String) entityManager.createNativeQuery(
                "SELECT new_value ->> 'validTo' FROM access_control_audit_logs WHERE target_id = :targetId AND action = 'UPDATE_VALIDITY'"
        ).setParameter("targetId", apId.toString()).getSingleResult();
        assertNotNull(validToInJson);
        assertTrue(validToInJson.contains("2027-10-01"));
    }

    @Test
    @DisplayName("Hồi quy Lỗi 2 — Thao tác 3 (Thu hồi AP): commit thật, dữ liệu nghiệp vụ đổi, 1 dòng log mới, jsonb đúng giá trị")
    void testCommit_RevokePersonnel_CreatesAuditLogAndChangesBusinessData() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM AP Revoke")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);
        String token = "Bearer " + jwtTokenProvider.generateToken(fmActor);

        User targetUser = User.builder()
                .userCode("USR-" + uniqueSuffix)
                .fullName("Target AP Revoke")
                .email("usr-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();
        targetUser = userRepository.save(targetUser);

        Floor floor = getOrCreateTestFloor();
        Area testArea = Area.builder()
                .name("Area AP Revoke " + uniqueSuffix)
                .building("TOA_ALPHA")
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        testArea = areaRepository.save(testArea);

        // Tạo AP ban đầu
        org.springframework.test.web.servlet.MvcResult createRes = mockMvc.perform(post("/api/areas/{areaId}/assigned-personnel", testArea.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(String.format("{\"userId\": \"%s\", \"reason\": \"Gán để thu hồi\"}", targetUser.getId())))
                .andExpect(status().isCreated())
                .andReturn();
        UUID apId = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(createRes.getResponse().getContentAsString()).get("id").asText());

        // Thu hồi
        String revokeJson = "{\"reason\": \"Chuyển công tác khác phòng\"}";
        mockMvc.perform(patch("/api/areas/{areaId}/assigned-personnel/{id}/revoke", testArea.getId(), apId)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeJson))
                .andExpect(status().isOk());

        // 1. Kiểm tra trực tiếp bảng nghiệp vụ
        Object reasonObj = entityManager.createNativeQuery(
                "SELECT revoke_reason FROM area_assigned_personnel WHERE id = :id"
        ).setParameter("id", apId).getSingleResult();
        assertEquals("Chuyển công tác khác phòng", reasonObj);

        // 2. Kiểm tra log có action = REVOKE
        Number count = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM access_control_audit_logs WHERE target_id = :targetId AND action = 'REVOKE'"
        ).setParameter("targetId", apId.toString()).getSingleResult();
        assertEquals(1, count.intValue());

        String statusInJson = (String) entityManager.createNativeQuery(
                "SELECT new_value ->> 'status' FROM access_control_audit_logs WHERE target_id = :targetId AND action = 'REVOKE'"
        ).setParameter("targetId", apId.toString()).getSingleResult();
        assertEquals("REVOKED", statusInJson);
    }

    @Test
    @DisplayName("Hồi quy Lỗi 2 — Thao tác 4 (Cập nhật User Access Level): commit thật, dữ liệu nghiệp vụ đổi, 1 dòng log mới, jsonb đúng giá trị")
    void testCommit_UpdateUserAccessLevel_CreatesAuditLogAndChangesBusinessData() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM UserLevel Commit")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);
        String token = "Bearer " + jwtTokenProvider.generateToken(fmActor);

        User targetUser = User.builder()
                .userCode("USR-" + uniqueSuffix)
                .fullName("Target UserLevel Commit")
                .email("usr-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();
        targetUser = userRepository.save(targetUser);

        String updateJson = "{\"accessLevel\": 3, \"reason\": \"Nâng cấp level 3 phục vụ dự án\"}";
        mockMvc.perform(patch("/api/users/{id}/access-level", targetUser.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk());

        // 1. Kiểm tra trực tiếp bảng users
        Number level = (Number) entityManager.createNativeQuery(
                "SELECT access_level FROM users WHERE id = :id"
        ).setParameter("id", targetUser.getId()).getSingleResult();
        assertEquals(3, level.intValue());

        // 2. Kiểm tra log
        Number count = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM access_control_audit_logs WHERE target_id = :targetId AND target_type = 'USER_ACCESS_LEVEL'"
        ).setParameter("targetId", targetUser.getId().toString()).getSingleResult();
        assertEquals(1, count.intValue());

        // 3. Kiểm tra jsonb: old = 1, new = 3
        String oldLvl = (String) entityManager.createNativeQuery(
                "SELECT old_value ->> 'accessLevel' FROM access_control_audit_logs WHERE target_id = :targetId AND target_type = 'USER_ACCESS_LEVEL'"
        ).setParameter("targetId", targetUser.getId().toString()).getSingleResult();
        String newLvl = (String) entityManager.createNativeQuery(
                "SELECT new_value ->> 'accessLevel' FROM access_control_audit_logs WHERE target_id = :targetId AND target_type = 'USER_ACCESS_LEVEL'"
        ).setParameter("targetId", targetUser.getId().toString()).getSingleResult();
        assertEquals("1", oldLvl);
        assertEquals("3", newLvl);
    }

    @Test
    @DisplayName("Hồi quy Lỗi 2 — Thao tác 5 (Cập nhật Area Access Rules): commit thật, dữ liệu nghiệp vụ đổi, 1 dòng log mới, jsonb đúng giá trị")
    void testCommit_UpdateAreaAccessRules_CreatesAuditLogAndChangesBusinessData() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM AreaRules Commit")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);
        String token = "Bearer " + jwtTokenProvider.generateToken(fmActor);

        Floor floor = getOrCreateTestFloor();
        Area testArea = Area.builder()
                .name("Area Rules Commit " + uniqueSuffix)
                .building("TOA_ALPHA")
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        testArea = areaRepository.save(testArea);

        String updateJson = "{\"areaAccessLevel\": 3, \"explicitAuthorizationRequired\": true, \"reason\": \"Thắt chặt an ninh phòng Server\"}";
        mockMvc.perform(patch("/api/areas/{id}/access-rules", testArea.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk());

        // 1. Kiểm tra trực tiếp bảng areas
        Object[] areaData = (Object[]) entityManager.createNativeQuery(
                "SELECT area_access_level, explicit_authorization_required FROM areas WHERE id = :id"
        ).setParameter("id", testArea.getId()).getSingleResult();
        assertEquals(3, ((Number) areaData[0]).intValue());
        assertEquals(true, areaData[1]);

        // 2. Kiểm tra log
        Number count = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM access_control_audit_logs WHERE target_id = :targetId AND target_type = 'AREA_ACCESS_RULES'"
        ).setParameter("targetId", testArea.getId().toString()).getSingleResult();
        assertEquals(1, count.intValue());

        // 3. Kiểm tra jsonb
        String newLevel = (String) entityManager.createNativeQuery(
                "SELECT new_value ->> 'areaAccessLevel' FROM access_control_audit_logs WHERE target_id = :targetId AND target_type = 'AREA_ACCESS_RULES'"
        ).setParameter("targetId", testArea.getId().toString()).getSingleResult();
        String newExplicit = (String) entityManager.createNativeQuery(
                "SELECT new_value ->> 'explicitAuthorizationRequired' FROM access_control_audit_logs WHERE target_id = :targetId AND target_type = 'AREA_ACCESS_RULES'"
        ).setParameter("targetId", testArea.getId().toString()).getSingleResult();
        assertEquals("3", newLevel);
        assertEquals("true", newExplicit);
    }

    @Test
    @DisplayName("Hồi quy Lỗi 2 — Thao tác 6 (Cập nhật Level Preset): commit thật, dữ liệu nghiệp vụ đổi, 1 dòng log mới, jsonb đúng giá trị")
    void testCommit_UpdateLevelPreset_CreatesAuditLogAndChangesBusinessData() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM Preset Commit")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);
        String token = "Bearer " + jwtTokenProvider.generateToken(fmActor);

        // Lấy trạng thái hiện tại của CONFIDENTIAL_CONTACT_REQUIRED
        Object[] currentData = (Object[]) entityManager.createNativeQuery(
                "SELECT area_access_level, explicit_authorization_required, version FROM area_level_presets WHERE area_level = 'CONFIDENTIAL_CONTACT_REQUIRED'"
        ).getSingleResult();
        int currentLevel = ((Number) currentData[0]).intValue();
        boolean currentExplicit = (Boolean) currentData[1];
        long currentVersion = ((Number) currentData[2]).longValue();

        int targetLevel = (currentLevel == 3) ? 2 : 3;
        boolean targetExplicit = !currentExplicit;
        String reason = "Cập nhật preset " + uniqueSuffix;

        String updateJson = String.format(
                "{\"areaAccessLevel\": %d, \"explicitAuthorizationRequired\": %b, \"reason\": \"%s\", \"version\": %d}",
                targetLevel, targetExplicit, reason, currentVersion
        );

        mockMvc.perform(put("/api/access-control/level-presets/{areaLevel}", AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson))
                .andExpect(status().isOk());

        // 1. Kiểm tra trực tiếp bảng area_level_presets
        Object[] presetData = (Object[]) entityManager.createNativeQuery(
                "SELECT area_access_level, explicit_authorization_required, version FROM area_level_presets WHERE area_level = 'CONFIDENTIAL_CONTACT_REQUIRED'"
        ).getSingleResult();
        assertEquals(targetLevel, ((Number) presetData[0]).intValue());
        assertEquals(targetExplicit, presetData[1]);
        assertEquals(currentVersion + 1, ((Number) presetData[2]).longValue());

        // 2. Kiểm tra log có target_id = 'CONFIDENTIAL_CONTACT_REQUIRED'
        Number count = (Number) entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM access_control_audit_logs WHERE target_id = 'CONFIDENTIAL_CONTACT_REQUIRED' AND target_type = 'LEVEL_PRESET' AND reason = :reason"
        ).setParameter("reason", reason).getSingleResult();
        assertEquals(1, count.intValue());

        // 3. Kiểm tra jsonb
        String explicitInJson = (String) entityManager.createNativeQuery(
                "SELECT new_value ->> 'explicitAuthorizationRequired' FROM access_control_audit_logs WHERE target_id = 'CONFIDENTIAL_CONTACT_REQUIRED' AND target_type = 'LEVEL_PRESET' AND reason = :reason"
        ).setParameter("reason", reason).getSingleResult();
        assertEquals(String.valueOf(targetExplicit), explicitInJson);
    }

    @Test
    @DisplayName("Sau khi có log thật: GET audit-logs trả về đúng dòng vừa tạo với oldValue/newValue là object JSON (không bị escape)")
    void testAuditLogs_ResponseReturnsJsonNodeAsObject() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM JsonVerify")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);
        String token = "Bearer " + jwtTokenProvider.generateToken(fmActor);

        User targetUser = User.builder()
                .userCode("USR-" + uniqueSuffix)
                .fullName("Target JsonVerify")
                .email("usr-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();
        targetUser = userRepository.save(targetUser);

        // Tạo 1 log bằng thao tác đổi access level
        mockMvc.perform(patch("/api/users/{id}/access-level", targetUser.getId())
                        .header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accessLevel\": 2, \"reason\": \"Đổi level kiểm tra JSON response\"}"))
                .andExpect(status().isOk());

        // Gọi GET audit-logs lọc theo targetId của targetUser
        mockMvc.perform(get("/api/access-control/audit-logs")
                        .header("Authorization", token)
                        .param("targetType", "USER_ACCESS_LEVEL")
                        .param("subjectUserId", targetUser.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].newValue").isMap())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].newValue.accessLevel").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].oldValue").isMap())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.content[0].oldValue.accessLevel").value(1));
    }
}
