package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.config.RoleRestrictedEndpoints;
import com.fa26se040.icss.config.RoleRestrictedEndpoints.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * BE-01: quy tắc URL trong SecurityConfig ({@link RoleRestrictedEndpoints}) phải khớp {@code @PreAuthorize} trên
 * method controller. Đọc annotation bằng reflection từ handler thật mà Spring MVC map cho method + pattern, để sau
 * này đổi vai trò ở một chỗ mà quên chỗ kia thì test đỏ.
 */
class RoleRestrictedEndpointsConsistencyTest extends AbstractIntegrationTest {

    private static final Pattern ROLE_EXPRESSION = Pattern.compile("^\\s*has(Any)?Role\\((.*)\\)\\s*$");
    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    static Set<String> parseRoles(String expression) {
        Matcher m = ROLE_EXPRESSION.matcher(expression);
        if (!m.matches()) {
            fail("@PreAuthorize phải là hasRole(...) / hasAnyRole(...) để khai báo được ở SecurityConfig: " + expression);
        }
        Set<String> roles = new java.util.HashSet<>();
        Matcher q = QUOTED.matcher(m.group(2));
        while (q.find()) {
            roles.add(q.group(1));
        }
        return roles;
    }

    @Test
    @DisplayName("parseRoles đọc đúng hasRole / hasAnyRole")
    void parseRoles_readsExpressions() {
        assertEquals(Set.of("ADMIN"), parseRoles("hasRole('ADMIN')"));
        assertEquals(Set.of("NORMAL_USER", "FACILITY_MANAGER", "ADMIN"),
                parseRoles("hasAnyRole('NORMAL_USER','FACILITY_MANAGER', 'ADMIN')"));
    }

    @Test
    @DisplayName("Mỗi dòng RoleRestrictedEndpoints map đúng 1 handler, vai trò = @PreAuthorize của handler đó")
    void everyRule_matchesPreAuthorizeOfItsHandler() {
        Map<RequestMappingInfo, HandlerMethod> handlers = handlerMapping.getHandlerMethods();
        List<String> mismatches = new ArrayList<>();

        for (Rule rule : RoleRestrictedEndpoints.RULES) {
            RequestMethod method = RequestMethod.valueOf(rule.method().name());
            List<HandlerMethod> matched = handlers.entrySet().stream()
                    .filter(e -> e.getKey().getMethodsCondition().getMethods().contains(method))
                    .filter(e -> e.getKey().getPatternValues().contains(rule.pattern()))
                    .map(Map.Entry::getValue)
                    .toList();
            if (matched.size() != 1) {
                mismatches.add(rule.method() + " " + rule.pattern() + ": tìm thấy " + matched.size() + " handler");
                continue;
            }
            HandlerMethod handler = matched.get(0);
            PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (pre == null) {
                pre = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            }
            if (pre == null) {
                mismatches.add(rule.method() + " " + rule.pattern() + ": handler không có @PreAuthorize");
                continue;
            }
            Set<String> annotated = parseRoles(pre.value());
            Set<String> configured = Set.copyOf(rule.roles());
            if (!annotated.equals(configured)) {
                mismatches.add(rule.method() + " " + rule.pattern() + ": SecurityConfig " + configured
                        + " ≠ @PreAuthorize " + annotated + " (" + handler.getShortLogMessage() + ")");
            }
        }
        assertTrue(mismatches.isEmpty(), "Lệch SecurityConfig ↔ @PreAuthorize:\n" + String.join("\n", mismatches));
        assertEquals(RoleRestrictedEndpoints.RULES.size(),
                RoleRestrictedEndpoints.RULES.stream().map(r -> r.method() + " " + r.pattern())
                        .collect(Collectors.toSet()).size());
    }
}
