package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.config.RoleRestrictedEndpoints;
import com.fa26se040.icss.config.RoleRestrictedEndpoints.Rule;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-01 / BE-02: vai trò bị cấm nhận 403 ngay cả khi body / tham số sai định dạng (trước đây 400 VALIDATION_ERROR
 * hoặc 500), vì SecurityConfig chặn theo method + đường dẫn trước khi Spring MVC bind tham số.
 * Vai trò được phép gửi body {} vẫn nhận 400 (endpoint JSON) hoặc 415 (endpoint multipart).
 */
class RoleRestrictedEndpointsAuthorizationTest extends AbstractIntegrationTest {

    /** 22 endpoint của BE-01 (B3-rbac-ma-tran.md, cột "Lệch" 400≠403). */
    private static final Set<String> BE01_ENDPOINTS = Set.of(
            "POST /api/access-requests/individual",
            "POST /api/access-requests/group",
            "POST /api/access-requests",
            "POST /api/access-requests/resolve-members",
            "PATCH /api/access-requests/{id}/review",
            "PATCH /api/access-requests/{id}/cancel-approved",
            "PATCH /api/access-requests/{id}/finish",
            "POST /api/areas/{areaId}/assigned-personnel",
            "PATCH /api/areas/{areaId}/assigned-personnel/{id}/revoke",
            "POST /api/areas",
            "GET /api/areas/{id}/type-change-preview",
            "PUT /api/areas/{id}",
            "PATCH /api/areas/{id}/access-rules",
            "PUT /api/incident-dispatch-policies/{severity}",
            "PUT /api/incident-severity-rules/{id}",
            "PUT /api/duty-post-types/{code}",
            "GET /api/reason-catalogs/active",
            "POST /api/reason-catalogs",
            "PUT /api/reason-catalogs/{id}",
            "PATCH /api/system-configurations/{configKey}",
            "PATCH /api/users/bulk-access-level",
            "PATCH /api/users/{id}/access-level"
    );

    /** 4 endpoint multipart của BE-02 (JSON {} -> 500 cho mọi vai trò). */
    private static final Set<String> BE02_MULTIPART_ENDPOINTS = Set.of(
            "POST /api/users/normal/bulk-import",
            "POST /api/users/staff/bulk-import",
            "POST /api/users/staff-accounts",
            "POST /api/guest-visits/{visitId}/guests/{guestId}/photo"
    );

    private static final Set<String> BE03_ENDPOINTS = Set.of("POST /api/cameras/recordings/stream-token");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    private final Map<Role, String> tokens = new EnumMap<>(Role.class);

    private String bearer(Role role) {
        return tokens.computeIfAbsent(role, r -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            User user = userRepository.save(User.builder()
                    .userCode("RBAC-" + r.name().substring(0, 3) + "-" + suffix)
                    .email("rbac.test." + r.name().toLowerCase() + "." + suffix + "@fpt.edu.vn")
                    .fullName("RBAC Test " + r.name())
                    .role(r)
                    .accessLevel(3)
                    .isActive(true)
                    .build());
            return "Bearer " + jwtTokenProvider.generateToken(user);
        });
    }

    private static String key(Rule rule) {
        return rule.method().name() + " " + rule.pattern();
    }

    private static String concreteUrl(Rule rule) {
        return rule.pattern().replaceAll("\\{[^}]+}", UUID.randomUUID().toString());
    }

    private static boolean isMultipart(Rule rule) {
        return BE02_MULTIPART_ENDPOINTS.contains(key(rule));
    }

    private static MockHttpServletRequestBuilder jsonEmptyBody(Rule rule) {
        return MockMvcRequestBuilders.request(rule.method(), concreteUrl(rule))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}");
    }

    Stream<Arguments> forbiddenCases() {
        return RoleRestrictedEndpoints.RULES.stream().flatMap(rule -> Arrays.stream(Role.values())
                .filter(role -> !rule.roles().contains(role.name()))
                .map(role -> Arguments.of(key(rule), role, rule)));
    }

    Stream<Arguments> allowedCases() {
        return RoleRestrictedEndpoints.RULES.stream().flatMap(rule -> rule.roles().stream()
                .map(role -> Arguments.of(key(rule), Role.valueOf(role), rule)));
    }

    Stream<Arguments> rules() {
        return RoleRestrictedEndpoints.RULES.stream().map(rule -> Arguments.of(key(rule), rule));
    }

    @Test
    @DisplayName("Danh sách chặn ở SecurityConfig phủ đủ 22 endpoint BE-01 + 4 endpoint multipart BE-02 + token video BE-03")
    void ruleList_coversReportedEndpoints() {
        Set<String> actual = RoleRestrictedEndpoints.RULES.stream()
                .map(RoleRestrictedEndpointsAuthorizationTest::key)
                .collect(Collectors.toSet());
        Set<String> expected = Stream.of(BE01_ENDPOINTS, BE02_MULTIPART_ENDPOINTS, BE03_ENDPOINTS)
                .flatMap(Set::stream).collect(Collectors.toSet());
        assertEquals(22, BE01_ENDPOINTS.size());
        assertEquals(expected, actual);
        assertEquals(RoleRestrictedEndpoints.RULES.size(), actual.size(), "Trùng dòng trong RoleRestrictedEndpoints");
    }

    @ParameterizedTest(name = "{0} · {1} · body rỗng -> 403")
    @MethodSource("forbiddenCases")
    @DisplayName("Vai trò bị cấm gửi body {} -> 403 (định dạng ApiResponse như @PreAuthorize)")
    void forbiddenRole_emptyBody_returns403(String endpoint, Role role, Rule rule) throws Exception {
        mockMvc.perform(jsonEmptyBody(rule).header("Authorization", bearer(role)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.httpCode").value(403))
                .andExpect(jsonPath("$.errorCode").value("Forbidden"))
                .andExpect(jsonPath("$.message").value("Access denied: Access Denied"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @ParameterizedTest(name = "{0} · {1} · body rỗng -> 400/415")
    @MethodSource("allowedCases")
    @DisplayName("Vai trò được phép gửi body {} -> 400 (JSON) / 415 (multipart), không 403, không 500")
    void allowedRole_emptyBody_returns400Or415(String endpoint, Role role, Rule rule) throws Exception {
        if (isMultipart(rule)) {
            mockMvc.perform(jsonEmptyBody(rule).header("Authorization", bearer(role)))
                    .andExpect(status().isUnsupportedMediaType())
                    .andExpect(jsonPath("$.httpCode").value(415));
        } else {
            mockMvc.perform(jsonEmptyBody(rule).header("Authorization", bearer(role)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.httpCode").value(400));
        }
    }

    @ParameterizedTest(name = "{0} · không token -> 401")
    @MethodSource("rules")
    @DisplayName("Không token -> 401 (không đổi)")
    void noToken_returns401(String endpoint, Rule rule) throws Exception {
        mockMvc.perform(jsonEmptyBody(rule)).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("BE-02: ADMIN gửi multipart thiếu file -> 400, không 500")
    void admin_multipartWithoutFile_returns400() throws Exception {
        MockMultipartFile other = new MockMultipartFile("other", "x.txt", "text/plain", "x".getBytes());
        for (String url : new String[]{"/api/users/normal/bulk-import", "/api/users/staff/bulk-import"}) {
            mockMvc.perform(multipart(url).file(other).header("Authorization", bearer(Role.ADMIN)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.httpCode").value(400));
        }
    }

    @Test
    @DisplayName("BE-02: ADMIN gửi content-type multipart nhưng thân hỏng (không boundary) -> 400, không 500")
    void admin_brokenMultipartBody_returns400() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/api/users/normal/bulk-import")
                        .header("Authorization", bearer(Role.ADMIN))
                        .contentType("multipart/form-data")
                        .content("not-a-multipart-body"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.httpCode").value(400));
    }
}
