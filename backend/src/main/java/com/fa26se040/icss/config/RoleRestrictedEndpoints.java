package com.fa26se040.icss.config;

import org.springframework.http.HttpMethod;

import java.util.List;

/**
 * BE-01 / BE-02: endpoint phải chặn vai trò ở tầng filter (SecurityConfig), trước khi Spring MVC bind / validate
 * tham số. Không có lớp này, vai trò bị cấm gửi body sai định dạng nhận 400 / 415 (kèm tên trường, câu validate)
 * thay vì 403, vì {@code @PreAuthorize} chỉ chạy sau khi tham số đã resolve xong.
 * <p>
 * Vai trò ở đây phải trùng {@code @PreAuthorize} trên method controller (vẫn giữ {@code @PreAuthorize}).
 * {@code RoleRestrictedEndpointsConsistencyTest} đọc annotation bằng reflection và đối chiếu từng dòng.
 * Pattern viết đúng như mapping của controller ({@code {id}}…), method + pattern xác định một handler.
 */
public final class RoleRestrictedEndpoints {

    public record Rule(HttpMethod method, String pattern, List<String> roles) {
        public Rule {
            roles = List.copyOf(roles);
        }

        public String[] rolesArray() {
            return roles.toArray(String[]::new);
        }
    }

    private static final String ADMIN = "ADMIN";
    private static final String FM = "FACILITY_MANAGER";
    private static final String GUARD = "GUARD";
    private static final String NORMAL_USER = "NORMAL_USER";

    public static final List<Rule> RULES = List.of(
            // AccessRequestController
            rule(HttpMethod.POST, "/api/access-requests/individual", NORMAL_USER),
            rule(HttpMethod.POST, "/api/access-requests/group", NORMAL_USER),
            rule(HttpMethod.POST, "/api/access-requests", NORMAL_USER),
            rule(HttpMethod.POST, "/api/access-requests/resolve-members", NORMAL_USER, FM, ADMIN),
            rule(HttpMethod.PATCH, "/api/access-requests/{id}/review", FM),
            rule(HttpMethod.PATCH, "/api/access-requests/{id}/cancel-approved", FM),
            rule(HttpMethod.PATCH, "/api/access-requests/{id}/finish", FM),
            // AreaAssignedPersonnelController
            rule(HttpMethod.POST, "/api/areas/{areaId}/assigned-personnel", FM),
            rule(HttpMethod.PATCH, "/api/areas/{areaId}/assigned-personnel/{id}/revoke", FM),
            // AreaController
            rule(HttpMethod.POST, "/api/areas", ADMIN),
            rule(HttpMethod.GET, "/api/areas/{id}/type-change-preview", ADMIN),
            rule(HttpMethod.PUT, "/api/areas/{id}", ADMIN),
            rule(HttpMethod.PATCH, "/api/areas/{id}/access-rules", ADMIN),
            // IncidentDispatchPolicyController, IncidentSeverityRuleController, DutyPostTypeController
            rule(HttpMethod.PUT, "/api/incident-dispatch-policies/{severity}", ADMIN),
            rule(HttpMethod.PUT, "/api/incident-severity-rules/{id}", ADMIN),
            rule(HttpMethod.PUT, "/api/duty-post-types/{code}", ADMIN),
            // ReasonCatalogController
            rule(HttpMethod.GET, "/api/reason-catalogs/active", FM, ADMIN),
            rule(HttpMethod.POST, "/api/reason-catalogs", ADMIN),
            rule(HttpMethod.PUT, "/api/reason-catalogs/{id}", ADMIN),
            // SystemConfigController
            rule(HttpMethod.PATCH, "/api/system-configurations/{configKey}", ADMIN),
            // UserController
            rule(HttpMethod.PATCH, "/api/users/bulk-access-level", FM),
            rule(HttpMethod.PATCH, "/api/users/{id}/access-level", FM),
            // BE-02: endpoint multipart — content-type sai bị chặn ở bước chọn handler (415), trước @PreAuthorize
            rule(HttpMethod.POST, "/api/users/normal/bulk-import", ADMIN),
            rule(HttpMethod.POST, "/api/users/staff/bulk-import", ADMIN),
            rule(HttpMethod.POST, "/api/users/staff-accounts", ADMIN),
            rule(HttpMethod.POST, "/api/guest-visits/{visitId}/guests/{guestId}/photo", ADMIN),
            // BE-03: xin token xem video ghi hình (body JSON có @Valid)
            rule(HttpMethod.POST, "/api/cameras/recordings/stream-token", ADMIN, GUARD)
    );

    private static Rule rule(HttpMethod method, String pattern, String... roles) {
        return new Rule(method, pattern, List.of(roles));
    }

    private RoleRestrictedEndpoints() {
    }
}
