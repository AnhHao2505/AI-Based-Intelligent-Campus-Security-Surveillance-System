package com.fa26se040.icss.security;

import com.fa26se040.icss.dto.accesscontrol.LevelPresetResponse;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.dto.user.UserSearchResponse;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.service.AccessControlAuditService;
import com.fa26se040.icss.service.AreaLevelPresetService;
import com.fa26se040.icss.service.AreaService;
import com.fa26se040.icss.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AccessControlSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private AreaLevelPresetService presetService;

    @MockBean
    private AccessControlAuditService auditService;

    @MockBean
    private UserService userService;

    @MockBean
    private AreaService areaService;

    private final UUID sampleId = UUID.randomUUID();

    private String tokenFor(Role role) {
        User u = User.builder()
                .id(UUID.randomUUID())
                .userCode("TEST-" + role.name().substring(0, 3))
                .email(role.name().toLowerCase() + "@fpt.edu.vn")
                .fullName("Test " + role.name())
                .role(role)
                .isActive(true)
                .build();
        return "Bearer " + jwtTokenProvider.generateToken(u);
    }

    // =========================================================================
    // 1. PUT /api/access-control/level-presets/{areaLevel}
    // =========================================================================
    @Nested
    @DisplayName("PUT /api/access-control/level-presets/{areaLevel} Security")
    class PutLevelPresetSecurity {

        private final String putBody = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": \"Bảo mật cao hơn\", \"version\": 0}";

        @Test
        @DisplayName("PUT /level-presets: FM được phép -> 200 OK")
        void putPreset_FacilityManager_Returns200() throws Exception {
            when(presetService.updatePreset(eq(AreaLevel.PUBLIC), any(), any()))
                    .thenReturn(new LevelPresetResponse(AreaLevel.PUBLIC, 2, false, OffsetDateTime.now(), "FM", "FM01", 1L));

            mockMvc.perform(put("/api/access-control/level-presets/{areaLevel}", AreaLevel.PUBLIC)
                            .header("Authorization", tokenFor(Role.FACILITY_MANAGER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(putBody))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("PUT /level-presets: ADMIN bị cấm -> 403 Forbidden")
        void putPreset_Admin_Returns403() throws Exception {
            mockMvc.perform(put("/api/access-control/level-presets/{areaLevel}", AreaLevel.PUBLIC)
                            .header("Authorization", tokenFor(Role.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(putBody))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PUT /level-presets: NORMAL_USER bị cấm -> 403 Forbidden")
        void putPreset_NormalUser_Returns403() throws Exception {
            mockMvc.perform(put("/api/access-control/level-presets/{areaLevel}", AreaLevel.PUBLIC)
                            .header("Authorization", tokenFor(Role.NORMAL_USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(putBody))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PUT /level-presets: GUARD bị cấm -> 403 Forbidden")
        void putPreset_Guard_Returns403() throws Exception {
            mockMvc.perform(put("/api/access-control/level-presets/{areaLevel}", AreaLevel.PUBLIC)
                            .header("Authorization", tokenFor(Role.GUARD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(putBody))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // 2. GET /api/access-control/level-presets
    // =========================================================================
    @Nested
    @DisplayName("GET /api/access-control/level-presets Security")
    class GetLevelPresetsSecurity {

        @Test
        @DisplayName("GET /level-presets: ADMIN được phép -> 200 OK")
        void getPresets_Admin_Returns200() throws Exception {
            when(presetService.getAllPresets()).thenReturn(List.of());
            mockMvc.perform(get("/api/access-control/level-presets")
                            .header("Authorization", tokenFor(Role.ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /level-presets: FM được phép -> 200 OK")
        void getPresets_FacilityManager_Returns200() throws Exception {
            when(presetService.getAllPresets()).thenReturn(List.of());
            mockMvc.perform(get("/api/access-control/level-presets")
                            .header("Authorization", tokenFor(Role.FACILITY_MANAGER)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("GET /level-presets: NORMAL_USER bị cấm -> 403 Forbidden")
        void getPresets_NormalUser_Returns403() throws Exception {
            mockMvc.perform(get("/api/access-control/level-presets")
                            .header("Authorization", tokenFor(Role.NORMAL_USER)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("GET /level-presets: GUARD bị cấm -> 403 Forbidden")
        void getPresets_Guard_Returns403() throws Exception {
            mockMvc.perform(get("/api/access-control/level-presets")
                            .header("Authorization", tokenFor(Role.GUARD)))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // 3. GET /api/access-control/audit-logs (BR-AL-08)
    // =========================================================================
    @Nested
    @DisplayName("GET /api/access-control/audit-logs Security (BR-AL-08)")
    class GetAuditLogsSecurity {

        @Test
        @DisplayName("BR-AL-08: GET /audit-logs: ADMIN được phép -> 200 OK")
        void getAuditLogs_Admin_Returns200() throws Exception {
            when(auditService.getAuditLogs(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of()));
            mockMvc.perform(get("/api/access-control/audit-logs")
                            .header("Authorization", tokenFor(Role.ADMIN)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("BR-AL-08: GET /audit-logs: FM được phép -> 200 OK")
        void getAuditLogs_FacilityManager_Returns200() throws Exception {
            when(auditService.getAuditLogs(any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of()));
            mockMvc.perform(get("/api/access-control/audit-logs")
                            .header("Authorization", tokenFor(Role.FACILITY_MANAGER)))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("BR-AL-08: GET /audit-logs: NORMAL_USER bị cấm -> 403 Forbidden")
        void getAuditLogs_NormalUser_Returns403() throws Exception {
            mockMvc.perform(get("/api/access-control/audit-logs")
                            .header("Authorization", tokenFor(Role.NORMAL_USER)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("BR-AL-08: GET /audit-logs: GUARD bị cấm -> 403 Forbidden")
        void getAuditLogs_Guard_Returns403() throws Exception {
            mockMvc.perform(get("/api/access-control/audit-logs")
                            .header("Authorization", tokenFor(Role.GUARD)))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // 4. PATCH /api/users/{id}/access-level
    // =========================================================================
    @Nested
    @DisplayName("PATCH /api/users/{id}/access-level Security")
    class PatchUserAccessLevelSecurity {

        private final String userLevelBody = "{\"accessLevel\": 2, \"reason\": \"Điều chỉnh cấp độ nhân viên\"}";

        @Test
        @DisplayName("PATCH /users/{id}/access-level: FM được phép -> 200 OK")
        void patchUserLevel_FacilityManager_Returns200() throws Exception {
            when(userService.updateAccessLevel(eq(sampleId), eq(2), any(), any()))
                    .thenReturn(new UserSearchResponse(sampleId, "NV01", "Nhân Viên", Role.NORMAL_USER, 2));

            mockMvc.perform(patch("/api/users/{id}/access-level", sampleId)
                            .header("Authorization", tokenFor(Role.FACILITY_MANAGER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(userLevelBody))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("PATCH /users/{id}/access-level: ADMIN bị cấm -> 403 Forbidden")
        void patchUserLevel_Admin_Returns403() throws Exception {
            mockMvc.perform(patch("/api/users/{id}/access-level", sampleId)
                            .header("Authorization", tokenFor(Role.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(userLevelBody))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PATCH /users/{id}/access-level: NORMAL_USER bị cấm -> 403 Forbidden")
        void patchUserLevel_NormalUser_Returns403() throws Exception {
            mockMvc.perform(patch("/api/users/{id}/access-level", sampleId)
                            .header("Authorization", tokenFor(Role.NORMAL_USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(userLevelBody))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PATCH /users/{id}/access-level: GUARD bị cấm -> 403 Forbidden")
        void patchUserLevel_Guard_Returns403() throws Exception {
            mockMvc.perform(patch("/api/users/{id}/access-level", sampleId)
                            .header("Authorization", tokenFor(Role.GUARD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(userLevelBody))
                    .andExpect(status().isForbidden());
        }
    }

    // =========================================================================
    // 5. PATCH /api/areas/{id}/access-rules
    // =========================================================================
    @Nested
    @DisplayName("PATCH /api/areas/{id}/access-rules Security")
    class PatchAreaAccessRulesSecurity {

        private final String areaRulesBody = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": \"Điều chỉnh quy tắc khu vực\"}";

        @Test
        @DisplayName("PATCH /areas/{id}/access-rules: FM được phép -> 200 OK")
        void patchAreaRules_FacilityManager_Returns200() throws Exception {
            when(areaService.updateAccessRules(eq(sampleId), any(), any()))
                    .thenReturn(new AreaResponse(sampleId, "A01", "Khu vực 1", AreaLevel.PUBLIC, 2, false,
                            "Tòa A", "Tầng 1", "Mô tả", null, true, OffsetDateTime.now(), OffsetDateTime.now()));

            mockMvc.perform(patch("/api/areas/{id}/access-rules", sampleId)
                            .header("Authorization", tokenFor(Role.FACILITY_MANAGER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(areaRulesBody))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("PATCH /areas/{id}/access-rules: ADMIN bị cấm -> 403 Forbidden")
        void patchAreaRules_Admin_Returns403() throws Exception {
            mockMvc.perform(patch("/api/areas/{id}/access-rules", sampleId)
                            .header("Authorization", tokenFor(Role.ADMIN))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(areaRulesBody))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PATCH /areas/{id}/access-rules: NORMAL_USER bị cấm -> 403 Forbidden")
        void patchAreaRules_NormalUser_Returns403() throws Exception {
            mockMvc.perform(patch("/api/areas/{id}/access-rules", sampleId)
                            .header("Authorization", tokenFor(Role.NORMAL_USER))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(areaRulesBody))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("PATCH /areas/{id}/access-rules: GUARD bị cấm -> 403 Forbidden")
        void patchAreaRules_Guard_Returns403() throws Exception {
            mockMvc.perform(patch("/api/areas/{id}/access-rules", sampleId)
                            .header("Authorization", tokenFor(Role.GUARD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(areaRulesBody))
                    .andExpect(status().isForbidden());
        }
    }
}
