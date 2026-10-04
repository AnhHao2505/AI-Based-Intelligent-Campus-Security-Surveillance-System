package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Set;
import java.util.EnumSet;
import java.util.Arrays;
import java.util.function.Supplier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T3: Test phân quyền (403/401) cho endpoint khu vực.
 * Khóa chặt hành vi phân quyền của AreaController & AreaAssignedPersonnelController.
 */
public class AreaEndpointAuthorizationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String adminToken;
    private String fmToken;
    private String userToken;
    private String guardToken;

    private final UUID randomAreaId = UUID.randomUUID();
    private final UUID randomScheduleId = UUID.randomUUID();
    private final UUID randomApId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        adminToken = bearer(Role.ADMIN);
        fmToken = bearer(Role.FACILITY_MANAGER);
        userToken = bearer(Role.NORMAL_USER);
        guardToken = bearer(Role.GUARD);
    }

    private String bearer(Role role) {
        User user = User.builder()
                .id(UUID.randomUUID())
                .email("auth.test." + role.name().toLowerCase() + "@fpt.edu.vn")
                .fullName("Auth Test " + role.name())
                .role(role)
                .accessLevel(3)
                .isActive(true)
                .build();
        return "Bearer " + jwtTokenProvider.generateToken(user);
    }

    private String token(Role role) {
        return switch (role) {
            case ADMIN -> adminToken;
            case FACILITY_MANAGER -> fmToken;
            case NORMAL_USER -> userToken;
            case GUARD -> guardToken;
        };
    }

    /**
     * Không token -> 401; mỗi role KHÔNG nằm trong allowed -> 403. Mỗi lần gọi dựng request MỚI:
     * dùng lại một builder thì header Authorization bị cộng dồn và filter chỉ đọc header đầu tiên.
     */
    private void assertOnly(Supplier<MockHttpServletRequestBuilder> request, Role... allowed) throws Exception {
        mockMvc.perform(request.get()).andExpect(status().isUnauthorized());
        Set<Role> ok = EnumSet.noneOf(Role.class);
        ok.addAll(Arrays.asList(allowed));
        for (Role role : Role.values()) {
            if (!ok.contains(role)) {
                mockMvc.perform(request.get().header("Authorization", token(role)))
                        .andExpect(result -> assertEquals(403, result.getResponse().getStatus(),
                                role + " phải bị 403 ở " + result.getRequest().getMethod() + " " + result.getRequest().getRequestURI()));
            }
        }
    }

    private void assertAdminOnly(Supplier<MockHttpServletRequestBuilder> request) throws Exception {
        assertOnly(request, Role.ADMIN);
    }

    private void assertFmOnly(Supplier<MockHttpServletRequestBuilder> request) throws Exception {
        assertOnly(request, Role.FACILITY_MANAGER);
    }

    // =========================================================================
    // NHÓM CHỈ ADMIN (8 endpoint)
    // =========================================================================

    @Test
    @DisplayName("T3 [ADMIN ONLY]: POST /api/areas -> 401 không token, 403 cho FM/USER/GUARD")
    void postArea_adminOnly() throws Exception {
        assertAdminOnly(() -> post("/api/areas")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "name": "Test Area Auth",
                            "areaLevel": "INTERNAL_CONFIDENTIAL",
                            "building": "BUILDING_A",
                            "floor": "F1",
                            "centerLatitude": 10.8418,
                            "centerLongitude": 106.8100
                        }
                        """));
    }

    @Test
    @DisplayName("T3 [ADMIN ONLY]: GET /api/areas/{id}/dependencies -> 401 không token, 403 cho FM/USER/GUARD")
    void getDependencies_adminOnly() throws Exception {
        assertAdminOnly(() -> get("/api/areas/{id}/dependencies", randomAreaId));
    }

    @Test
    @DisplayName("T3 [ADMIN ONLY]: GET /api/areas/{id}/type-change-preview -> 401 không token, 403 cho FM/USER/GUARD")
    void previewTypeChange_adminOnly() throws Exception {
        assertAdminOnly(() -> get("/api/areas/{id}/type-change-preview", randomAreaId)
                .param("newAreaLevel", "HIGHLY_CONFIDENTIAL"));
    }

    @Test
    @DisplayName("T3 [ADMIN ONLY]: PUT /api/areas/{id} -> 401 không token, 403 cho FM/USER/GUARD")
    void putArea_adminOnly() throws Exception {
        assertAdminOnly(() -> put("/api/areas/{id}", randomAreaId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "name": "Test Area Auth",
                            "areaLevel": "INTERNAL_CONFIDENTIAL",
                            "building": "BUILDING_A",
                            "floor": "F1",
                            "centerLatitude": 10.8418,
                            "centerLongitude": 106.8100,
                            "version": 0
                        }
                        """));
    }

    @Test
    @DisplayName("T3 [ADMIN ONLY]: PATCH /api/areas/{id}/access-rules -> 401 không token, 403 cho FM/USER/GUARD")
    void updateAccessRules_adminOnly() throws Exception {
        assertAdminOnly(() -> patch("/api/areas/{id}/access-rules", randomAreaId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "areaAccessLevel": 2,
                            "reason": "Điều chỉnh quy tắc truy cập hợp lệ",
                            "version": 0
                        }
                        """));
    }

    @Test
    @DisplayName("T3 [ADMIN ONLY]: POST /api/areas/{id}/deactivate -> 401 không token, 403 cho FM/USER/GUARD (Step 6 thay DELETE)")
    void deactivateArea_adminOnly() throws Exception {
        assertAdminOnly(() -> post("/api/areas/{id}/deactivate", randomAreaId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Ngừng sử dụng khu vực để sửa chữa\", \"version\": 0}"));
    }

    @Test
    @DisplayName("T3 [ADMIN ONLY]: POST /api/areas/{id}/restore -> 401 không token, 403 cho FM/USER/GUARD (Step 6)")
    void restoreArea_adminOnly() throws Exception {
        assertAdminOnly(() -> post("/api/areas/{id}/restore", randomAreaId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\": \"Mở lại khu vực sau khi sửa chữa\", \"version\": 0}"));
    }

    @Test
    @DisplayName("T3 [ADMIN ONLY]: PUT /api/areas/{id}/cameras -> 401 không token, 403 cho FM/USER/GUARD")
    void updateCameras_adminOnly() throws Exception {
        assertAdminOnly(() -> put("/api/areas/{id}/cameras", randomAreaId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    // =========================================================================
    // NHÓM CHỈ FACILITY_MANAGER (7 endpoint)
    // =========================================================================

    @Test
    @DisplayName("T3 [FM ONLY]: PATCH /api/areas/{id}/event-mode -> 401 không token, 403 cho ADMIN/USER/GUARD")
    void updateEventMode_fmOnly() throws Exception {
        assertFmOnly(() -> patch("/api/areas/{id}/event-mode", randomAreaId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    @Test
    @DisplayName("T3 [FM ONLY]: POST /api/areas/{id}/event-schedules -> 401 không token, 403 cho ADMIN/USER/GUARD")
    void createEventSchedule_fmOnly() throws Exception {
        assertFmOnly(() -> post("/api/areas/{id}/event-schedules", randomAreaId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    @Test
    @DisplayName("T3 [FM ONLY]: PATCH /api/areas/{id}/event-schedules/{sid} -> 401 không token, 403 cho ADMIN/USER/GUARD")
    void updateEventSchedule_fmOnly() throws Exception {
        assertFmOnly(() -> patch("/api/areas/{id}/event-schedules/{sid}", randomAreaId, randomScheduleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    @Test
    @DisplayName("T3 [FM ONLY]: POST /api/areas/{id}/event-schedules/{sid}/cancel -> 401 không token, 403 cho ADMIN/USER/GUARD")
    void cancelEventSchedule_fmOnly() throws Exception {
        assertFmOnly(() -> post("/api/areas/{id}/event-schedules/{sid}/cancel", randomAreaId, randomScheduleId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    @Test
    @DisplayName("T3 [FM ONLY]: POST /api/areas/{areaId}/assigned-personnel -> 401 không token, 403 cho ADMIN/USER/GUARD")
    void createAssignedPersonnel_fmOnly() throws Exception {
        assertFmOnly(() -> post("/api/areas/{areaId}/assigned-personnel", randomAreaId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(String.format("""
                        {
                            "userId": "%s",
                            "reason": "Gán nhân sự vào khu vực hợp lệ"
                        }
                        """, UUID.randomUUID())));
    }

    @Test
    @DisplayName("T3 [FM ONLY]: PATCH /api/areas/{areaId}/assigned-personnel/{id} -> 401 không token, 403 cho ADMIN/USER/GUARD")
    void updateAssignedPersonnel_fmOnly() throws Exception {
        assertFmOnly(() -> patch("/api/areas/{areaId}/assigned-personnel/{id}", randomAreaId, randomApId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "validTo": "2026-10-01T00:00:00Z",
                            "reason": "Gia hạn thời gian gán nhân sự khu vực"
                        }
                        """));
    }

    @Test
    @DisplayName("T3 [FM ONLY]: PATCH /api/areas/{areaId}/assigned-personnel/{id}/revoke -> 401 không token, 403 cho ADMIN/USER/GUARD")
    void revokeAssignedPersonnel_fmOnly() throws Exception {
        assertFmOnly(() -> patch("/api/areas/{areaId}/assigned-personnel/{id}/revoke", randomAreaId, randomApId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                            "reason": "Thu hồi quyền gán nhân sự khu vực"
                        }
                        """));
    }

    // ========================================================================= endpoint đọc có giới hạn role

    @Test
    @DisplayName("GET /api/areas, /{id}: chỉ ADMIN + FM")
    void readAreas_adminAndFm() throws Exception {
        assertOnly(() -> get("/api/areas"), Role.ADMIN, Role.FACILITY_MANAGER);
        assertOnly(() -> get("/api/areas/{id}", randomAreaId), Role.ADMIN, Role.FACILITY_MANAGER);
    }

    @Test
    @DisplayName("GET /{id}/event-schedules, /{areaId}/assigned-personnel: chỉ FM + ADMIN")
    void readSchedulesAndPersonnel_fmAndAdmin() throws Exception {
        assertOnly(() -> get("/api/areas/{id}/event-schedules", randomAreaId), Role.ADMIN, Role.FACILITY_MANAGER);
        assertOnly(() -> get("/api/areas/{areaId}/assigned-personnel", randomAreaId), Role.ADMIN, Role.FACILITY_MANAGER);
    }

    @Test
    @DisplayName("GET /map-pins, /{id}/cameras: ADMIN + FM + GUARD; /available-for-request: NORMAL_USER + FM + ADMIN")
    void readPinsCamerasAvailable() throws Exception {
        assertOnly(() -> get("/api/areas/map-pins"), Role.ADMIN, Role.FACILITY_MANAGER, Role.GUARD);
        assertOnly(() -> get("/api/areas/{id}/cameras", randomAreaId), Role.ADMIN, Role.FACILITY_MANAGER, Role.GUARD);
        assertOnly(() -> get("/api/areas/available-for-request"), Role.ADMIN, Role.FACILITY_MANAGER, Role.NORMAL_USER);
    }
}
