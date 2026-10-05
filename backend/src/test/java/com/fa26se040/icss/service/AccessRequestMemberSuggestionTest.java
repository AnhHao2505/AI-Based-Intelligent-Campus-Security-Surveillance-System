package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * GET /api/access-requests/member-suggestions — gợi ý mã thành viên cho form đơn nhóm (NORMAL_USER).
 * Cùng điều kiện thành viên hợp lệ với resolve-members (AccessRequestService.isEligibleMember), cùng rate limit
 * SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE, chỉ trả userCode + fullName, tối đa 10.
 */
class AccessRequestMemberSuggestionTest extends Step5bTestSupport {

    private String originalRateLimit;
    /** Tiền tố mã riêng của mỗi test để kết quả không lẫn dữ liệu khác trên DB test dùng chung. */
    private String prefix;
    private User caller;

    @BeforeEach
    void setUpSuggestion() {
        originalRateLimit = systemConfigService.getString(ConfigKey.SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE);
        prefix = "MS" + suffix.toUpperCase();
        caller = codeUser(prefix + "-00", Role.NORMAL_USER, true, false, 2);
    }

    @AfterEach
    void restoreRateLimit() {
        if (originalRateLimit != null && !originalRateLimit.isBlank()) {
            systemConfigService.update(ConfigKey.SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE.name(), originalRateLimit,
                    admin.getEmail());
        }
    }

    private User codeUser(String code, Role role, boolean active, boolean deleted) {
        return codeUser(code, role, active, deleted, 1);
    }

    private User codeUser(String code, Role role, boolean active, boolean deleted, int level) {
        return userRepository.save(User.builder()
                .email(code.toLowerCase() + "@fpt.edu.vn")
                .userCode(code)
                .fullName("Thanh vien " + code)
                .role(role)
                .accessLevel(level)
                .isActive(active)
                .deletedAt(deleted ? OffsetDateTime.now().minusDays(1) : null)
                .build());
    }

    private MvcResult suggest(User actor, String q) throws Exception {
        var builder = get("/api/access-requests/member-suggestions");
        if (q != null) {
            builder = builder.param("q", q);
        }
        return send(builder, actor, null).andReturn();
    }

    private List<String> codes(MvcResult r) throws Exception {
        JsonNode data = json(r).path("data");
        return StreamSupport.stream(data.spliterator(), false).map(n -> n.path("userCode").asText()).toList();
    }

    @Test
    @DisplayName("#1 q=s -> chỉ mã bắt đầu bằng S (không phân biệt hoa thường), tối đa 10, sắp theo mã")
    void prefixLowercase_returnsSortedCodesStartingWithS() throws Exception {
        MvcResult r = suggest(caller, "s");

        assertEquals(200, status(r), describe(r));
        List<String> codes = codes(r);
        assertTrue(codes.size() <= 10, "Tối đa 10: " + codes);
        assertTrue(codes.stream().allMatch(c -> c.toUpperCase().startsWith("S")), codes.toString());
        List<String> sorted = new ArrayList<>(codes);
        sorted.sort(String::compareTo);
        assertEquals(sorted, codes, "Sắp theo mã");
    }

    @Test
    @DisplayName("#2 prefix đúng: mã chỉ CHỨA chuỗi ở giữa không được trả")
    void prefixOnly_notContains() throws Exception {
        User starts = codeUser(prefix + "-01", Role.NORMAL_USER, true, false);
        User middle = codeUser("X" + prefix + "-02", Role.NORMAL_USER, true, false);

        MvcResult r = suggest(caller, prefix.toLowerCase());

        assertEquals(200, status(r), describe(r));
        List<String> codes = codes(r);
        assertTrue(codes.contains(starts.getUserCode()), codes.toString());
        assertFalse(codes.contains(middle.getUserCode()), "Mã chỉ chứa chuỗi ở giữa không được gợi ý");
    }

    @Test
    @DisplayName("#3 không có người gọi, user vô hiệu hoá, user đã xoá, user khác NORMAL_USER (đúng những người resolve-members từ chối)")
    void excludesCallerAndIneligibleUsers() throws Exception {
        User active = codeUser(prefix + "-01", Role.NORMAL_USER, true, false);
        User inactive = codeUser(prefix + "-02", Role.NORMAL_USER, false, false);
        User deleted = codeUser(prefix + "-03", Role.NORMAL_USER, true, true);
        User otherRole = codeUser(prefix + "-04", Role.FACILITY_MANAGER, true, false);

        MvcResult r = suggest(caller, prefix);

        assertEquals(200, status(r), describe(r));
        // BR-RQ-MEM-01: FACILITY_MANAGER không còn được gợi ý
        assertEquals(List.of(active.getUserCode()), codes(r));

        // Cùng điều kiện với resolve-members: 3 user bị loại cũng bị resolve-members từ chối
        MvcResult resolved = send(post("/api/access-requests/resolve-members"), caller,
                Map.of("userCodes", List.of(inactive.getUserCode(), deleted.getUserCode(), otherRole.getUserCode())))
                .andReturn();
        assertEquals(200, status(resolved), describe(resolved));
        for (JsonNode item : json(resolved).path("data")) {
            assertFalse(item.path("found").asBoolean(), "resolve-members phải từ chối " + item);
        }
    }

    @Test
    @DisplayName("#4 q = '%' / '_' / 'a b' / 21 ký tự -> 400")
    void invalidQuery_badRequest() throws Exception {
        for (String q : List.of("%", "_", "a b", "A".repeat(21))) {
            MvcResult r = suggest(caller, q);
            assertEquals(400, status(r), "q=" + q + ": " + describe(r));
        }
    }

    @Test
    @DisplayName("#5 q rỗng -> thành viên trong đơn của CHÍNH người gọi; thành viên trong đơn người khác không có")
    void emptyQuery_returnsOwnRecentMembersOnly() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        User m1 = codeUser(prefix + "-11", Role.NORMAL_USER, true, false);
        User m2 = codeUser(prefix + "-12", Role.NORMAL_USER, true, false);
        User other = codeUser(prefix + "-13", Role.NORMAL_USER, true, false);
        User otherRequester = codeUser(prefix + "-14", Role.NORMAL_USER, true, false);
        newRequest(area, caller, RequestType.GROUP, RequestStatus.PENDING, start, start.plusHours(2), m1, m2);
        newRequest(area, otherRequester, RequestType.GROUP, RequestStatus.PENDING, start, start.plusHours(2), other);

        MvcResult r = suggest(caller, null);
        MvcResult rBlank = suggest(caller, "  ");

        assertEquals(200, status(r), describe(r));
        assertEquals(Set.of(m1.getUserCode(), m2.getUserCode()), Set.copyOf(codes(r)));
        assertEquals(Set.copyOf(codes(r)), Set.copyOf(codes(rBlank)), "q chỉ có khoảng trắng = q rỗng");
    }

    @Test
    @DisplayName("#6 JSON mỗi gợi ý chỉ có userCode và fullName")
    void responseHasOnlyCodeAndName() throws Exception {
        codeUser(prefix + "-01", Role.NORMAL_USER, true, false);

        MvcResult r = suggest(caller, prefix);

        assertEquals(200, status(r), describe(r));
        JsonNode data = json(r).path("data");
        assertTrue(data.size() > 0);
        for (JsonNode item : data) {
            List<String> fields = new ArrayList<>();
            for (Iterator<String> it = item.fieldNames(); it.hasNext(); ) {
                fields.add(it.next());
            }
            assertEquals(Set.of("userCode", "fullName"), Set.copyOf(fields), item.toString());
        }
    }

    @Test
    @DisplayName("#7 FM / ADMIN / GUARD gọi -> 403")
    void nonNormalUser_forbidden() throws Exception {
        for (User actor : List.of(fm, admin, guard)) {
            MvcResult r = suggest(actor, "s");
            assertEquals(403, status(r), actor.getRole() + ": " + describe(r));
        }
    }

    @Test
    @DisplayName("#8 chưa đăng nhập -> 401")
    void unauthenticated_unauthorized() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/access-requests/member-suggestions").param("q", "s")).andReturn();
        assertEquals(401, status(r), describe(r));
    }

    // ------------------------------------------------------------------ BR-RQ-MEM-01: thành viên chỉ NORMAL_USER

    @Test
    @DisplayName("MEM-1: gợi ý theo tiền tố chỉ khớp mã GUARD / ADMIN -> rỗng")
    void suggestionsExcludeGuardAndAdmin() throws Exception {
        codeUser(prefix + "-G1", Role.GUARD, true, false);
        codeUser(prefix + "-A1", Role.ADMIN, true, false);

        MvcResult r = suggest(caller, prefix + "-");

        assertEquals(200, status(r), describe(r));
        assertEquals(List.of(), codes(r));
    }

    @Test
    @DisplayName("MEM-2: resolve-members với mã GUARD -> not found, lý do giống hệt mã không tồn tại")
    void resolveGuardCode_sameReasonAsNonexistent() throws Exception {
        User guardMember = codeUser(prefix + "-G2", Role.GUARD, true, false);
        String missing = prefix + "-ZZ";

        MvcResult r = send(post("/api/access-requests/resolve-members"), caller,
                Map.of("userCodes", List.of(guardMember.getUserCode(), missing))).andReturn();

        assertEquals(200, status(r), describe(r));
        JsonNode data = json(r).path("data");
        assertEquals(2, data.size());
        JsonNode guardItem = data.get(0);
        JsonNode missingItem = data.get(1);
        assertFalse(guardItem.path("found").asBoolean());
        assertFalse(missingItem.path("found").asBoolean());
        assertEquals(missingItem.path("reason").asText(), guardItem.path("reason").asText(),
                "Không được lộ là tài khoản tồn tại nhưng khác role");
        assertTrue(guardItem.path("fullName").isNull() || guardItem.path("fullName").isMissingNode(),
                "Không trả họ tên của tài khoản khác role: " + guardItem);
    }

    private Map<String, Object> groupBody(Area area, User member) {
        OffsetDateTime start = OffsetDateTime.now().plusHours(3).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("areaId", area.getId());
        b.put("startTime", start.toString());
        b.put("endTime", start.plusHours(1).toString());
        b.put("purpose", "Đơn nhóm kiểm BR-RQ-MEM-01 " + suffix);
        b.put("memberUserCodes", List.of(member.getUserCode()));
        return b;
    }

    @Test
    @DisplayName("MEM-3: tạo đơn nhóm có thành viên FACILITY_MANAGER -> 400")
    void createGroupWithFacilityManagerMember_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        User fmMember = codeUser(prefix + "-F1", Role.FACILITY_MANAGER, true, false, 3);

        MvcResult r = send(post("/api/access-requests/group"), caller, groupBody(area, fmMember)).andReturn();

        assertEquals(400, status(r), describe(r));
        assertTrue(message(r).contains("Không tìm thấy người dùng hợp lệ với mã này"), message(r));
    }

    @Test
    @DisplayName("MEM-4: tạo đơn nhóm có thành viên NORMAL_USER đủ cấp -> 201 như cũ")
    void createGroupWithNormalUserMember_created() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        User member = codeUser(prefix + "-N1", Role.NORMAL_USER, true, false, 2);

        MvcResult r = send(post("/api/access-requests/group"), caller, groupBody(area, member)).andReturn();

        assertEquals(201, status(r), describe(r));
    }

    @Test
    @DisplayName("#9 dùng chung rate limit với resolve-members: vượt SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE -> 429")
    void rateLimitShared_tooManyRequests() throws Exception {
        systemConfigService.update(ConfigKey.SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE.name(), "3", admin.getEmail());

        assertEquals(200, status(suggest(caller, "s")));
        assertEquals(200, status(suggest(caller, "sv")));
        MvcResult resolved = send(post("/api/access-requests/resolve-members"), caller,
                Map.of("userCodes", List.of("SV-001"))).andReturn();
        assertEquals(200, status(resolved), "Lần thứ 3 (resolve-members) vẫn trong ngưỡng: " + describe(resolved));

        MvcResult over = suggest(caller, "s");
        assertEquals(429, status(over), "Lần thứ 4 trong 1 phút phải bị chặn: " + describe(over));
    }
}
