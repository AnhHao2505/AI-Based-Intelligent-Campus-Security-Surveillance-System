package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.DutyPostType;
import com.fa26se040.icss.entity.IncidentDispatchPolicy;
import com.fa26se040.icss.entity.IncidentSeverityRule;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentTimeWindow;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.DutyPostTypeRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.IncidentDispatchPolicyRepository;
import com.fa26se040.icss.repository.IncidentSeverityRuleRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fa26se040.icss.util.AfterHoursWindow;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * V90 PR-1 trên DB thật: seed (K1, K2, K7, K10-a, audit), resolver với bảng luật thật, API admin chính sách sự cố
 * (quyền, version, mã lỗi ERR_INCPOL_*, audit) và API xếp ca nhận / trả dutyPostCode.
 * Dòng seed bị sửa trong test được trả lại giá trị cũ ở @AfterAll; ca trực test tạo bị xoá.
 */
class IncidentPolicyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private IncidentSeverityRuleRepository severityRuleRepository;

    @Autowired
    private IncidentDispatchPolicyRepository dispatchPolicyRepository;

    @Autowired
    private DutyPostTypeRepository dutyPostTypeRepository;

    @Autowired
    private GuardShiftRepository guardShiftRepository;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private User admin;
    private User fm;
    private User guard;
    private final List<UUID> createdShiftIds = new ArrayList<>();

    private UUID touchedRuleId;
    private IncidentSeverity touchedRuleOriginal;
    private IncidentDispatchPolicy mediumOriginal;

    @BeforeAll
    void setUpUsers() {
        admin = newUser("adm", Role.ADMIN);
        fm = newUser("fm", Role.FACILITY_MANAGER);
        guard = newUser("grd", Role.GUARD);
        mediumOriginal = dispatchPolicyRepository.findById(IncidentSeverity.MEDIUM).orElseThrow();
    }

    @AfterAll
    void restoreSeedAndCleanUp() {
        guardShiftRepository.deleteAllById(createdShiftIds);
        if (touchedRuleId != null) {
            IncidentSeverityRule rule = severityRuleRepository.findById(touchedRuleId).orElseThrow();
            rule.setSeverity(touchedRuleOriginal);
            severityRuleRepository.save(rule);
        }
        IncidentDispatchPolicy medium = dispatchPolicyRepository.findById(IncidentSeverity.MEDIUM).orElseThrow();
        medium.setStep1Size(mediumOriginal.getStep1Size());
        medium.setStep1TimeoutSeconds(mediumOriginal.getStep1TimeoutSeconds());
        medium.setEscalateFmMinutes(mediumOriginal.getEscalateFmMinutes());
        medium.setNotifyFmImmediately(mediumOriginal.getNotifyFmImmediately());
        dispatchPolicyRepository.save(medium);
        // audit_logs chỉ cho thêm nên giữ user test làm changed_by
    }

    private User newUser(String tag, Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .email("v90." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("V90-" + tag.toUpperCase(Locale.ROOT) + "-" + suffix)
                .fullName("V90 " + tag + " " + suffix)
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
    }

    private String bearer(User u) {
        return "Bearer " + jwtTokenProvider.generateToken(u);
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    // ================================================================== seed V90

    @Test
    @DisplayName("V90 seed: đúng 10 luật severity theo K1, không có MOVING_OBJECT")
    void seed_severityRules() {
        List<String> rows = severityRuleRepository.findAll().stream()
                .map(r -> r.getEventType() + "/" + r.getAreaLevelCode() + "/" + r.getTimeWindow() + "=" + r.getSeverity())
                .toList();
        assertThat(rows).contains(
                "UNKNOWN_PERSON/CONFIDENTIAL/IN_HOURS=HIGH",
                "UNKNOWN_PERSON/HIGHLY_CONFIDENTIAL/ANY=CRITICAL",
                "INTRUSION/PUBLIC/AFTER_HOURS=MEDIUM",
                "INTRUSION/CONFIDENTIAL/AFTER_HOURS=CRITICAL",
                "INTRUSION/HIGHLY_CONFIDENTIAL/ANY=CRITICAL",
                "UNAUTHORIZED_ACCESS/CONFIDENTIAL/ANY=HIGH",
                "UNAUTHORIZED_ACCESS/HIGHLY_CONFIDENTIAL/ANY=CRITICAL",
                "ACCESS_OUT_OF_TIME/CONFIDENTIAL/IN_HOURS=MEDIUM",
                "ACCESS_OUT_OF_TIME/CONFIDENTIAL/AFTER_HOURS=HIGH",
                "ACCESS_OUT_OF_TIME/HIGHLY_CONFIDENTIAL/ANY=CRITICAL");
        assertThat(rows).hasSize(10);
        assertThat(rows).noneMatch(r -> r.startsWith("MOVING_OBJECT"));
    }

    @Test
    @DisplayName("V90 seed: chính sách K2, vị trí trực K10-a, 4 khoá K7 (SECURITY 59–62), audit_event_types, cột duty_post_code")
    void seed_policiesPostsConfigAudit() {
        Map<IncidentSeverity, String> policies = new java.util.EnumMap<>(IncidentSeverity.class);
        dispatchPolicyRepository.findAll().forEach(p -> policies.put(p.getSeverity(),
                p.getStep1Size() + "/" + p.getStep1TimeoutSeconds() + "/" + p.getEscalateFmMinutes() + "/" + p.getNotifyFmImmediately()));
        assertThat(policies).containsEntry(IncidentSeverity.HIGH, "2/30/5/false")
                .containsEntry(IncidentSeverity.CRITICAL, "0/10/2/true")
                .containsKey(IncidentSeverity.MEDIUM);

        assertThat(dutyPostTypeRepository.findAllByOrderBySortOrderAscCodeAsc())
                .extracting(DutyPostType::getCode, DutyPostType::getDispatchable)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("PATROL", true),
                        org.assertj.core.groups.Tuple.tuple("GATE", false),
                        org.assertj.core.groups.Tuple.tuple("CAMERA_ROOM", false));

        List<Map<String, Object>> configs = jdbcTemplate.queryForList(
                "SELECT config_key, config_value, data_type, config_group, display_order FROM system_configurations "
                        + "WHERE config_key IN ('DISPATCH_ENABLED','DISPATCH_LOAD_WINDOW_MINUTES','DISPATCH_CHECK_SECONDS','INCIDENT_SEVERITY_DEFAULT') "
                        + "ORDER BY display_order");
        assertThat(configs).extracting(m -> m.get("config_key") + "=" + m.get("data_type") + "/" + m.get("config_group") + "/" + m.get("display_order"))
                .containsExactly(
                        "DISPATCH_ENABLED=BOOLEAN/SECURITY/59",
                        "DISPATCH_LOAD_WINDOW_MINUTES=INTEGER/SECURITY/60",
                        "DISPATCH_CHECK_SECONDS=INTEGER/SECURITY/61",
                        "INCIDENT_SEVERITY_DEFAULT=STRING/SECURITY/62");

        Integer auditTypes = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_event_types WHERE action = 'UPDATE' AND module = 'SYSTEM' "
                        + "AND target_type IN ('INCIDENT_SEVERITY_RULE','INCIDENT_DISPATCH_POLICY','DUTY_POST_TYPE')", Integer.class);
        assertThat(auditTypes).isEqualTo(3);

        String columnDefault = jdbcTemplate.queryForObject(
                "SELECT column_default FROM information_schema.columns WHERE table_name = 'guard_shifts' AND column_name = 'duty_post_code'",
                String.class);
        assertThat(columnDefault).contains("PATROL");
    }

    @Test
    @DisplayName("Resolver với bảng luật thật: mọi dòng seed khớp đúng mức; ô không seed -> INCIDENT_SEVERITY_DEFAULT")
    void resolver_realRules() {
        AfterHoursWindow inHours = mock(AfterHoursWindow.class);
        AfterHoursWindow afterHours = mock(AfterHoursWindow.class);
        when(inHours.isAfterHours(any())).thenReturn(false);
        when(afterHours.isAfterHours(any())).thenReturn(true);
        IncidentSeverityResolver day = new IncidentSeverityResolver(severityRuleRepository, inHours, systemConfigService);
        IncidentSeverityResolver night = new IncidentSeverityResolver(severityRuleRepository, afterHours, systemConfigService);
        OffsetDateTime at = OffsetDateTime.now();

        for (IncidentSeverityRule rule : severityRuleRepository.findAll()) {
            if (rule.getTimeWindow() != IncidentTimeWindow.AFTER_HOURS) {
                assertThat(day.resolve(rule.getEventType(), rule.getAreaLevelCode(), at)).as(rule.getEventType() + " day").isEqualTo(rule.getSeverity());
            }
            if (rule.getTimeWindow() != IncidentTimeWindow.IN_HOURS) {
                assertThat(night.resolve(rule.getEventType(), rule.getAreaLevelCode(), at)).as(rule.getEventType() + " night").isEqualTo(rule.getSeverity());
            }
        }

        IncidentSeverity configuredDefault = IncidentSeverity.valueOf(
                systemConfigService.getString(com.fa26se040.icss.enums.ConfigKey.INCIDENT_SEVERITY_DEFAULT));
        assertThat(day.resolve("UNKNOWN_PERSON", AreaLevel.PUBLIC, at)).isEqualTo(configuredDefault);
        assertThat(day.resolve("INTRUSION", AreaLevel.CONFIDENTIAL, at)).isEqualTo(configuredDefault);
    }

    // ================================================================== API luật severity

    @Test
    @DisplayName("GET luật severity: ADMIN 200; FM và GUARD 403")
    void severityRules_access() throws Exception {
        mockMvc.perform(get("/api/incident-severity-rules").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(10));
        mockMvc.perform(get("/api/incident-severity-rules").header("Authorization", bearer(fm)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/incident-severity-rules").header("Authorization", bearer(guard)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT luật severity: version đúng -> 200, version tăng, có audit; version cũ -> 409 ERR_INCPOL_004; thiếu version -> 400")
    void severityRule_update_versionAndAudit() throws Exception {
        IncidentSeverityRule rule = severityRuleRepository.findByEventTypeAndAreaLevelCodeAndTimeWindow(
                "UNAUTHORIZED_ACCESS", AreaLevel.CONFIDENTIAL, IncidentTimeWindow.ANY).orElseThrow();
        touchedRuleId = rule.getId();
        touchedRuleOriginal = rule.getSeverity();
        long version = rule.getVersion();
        IncidentSeverity target = rule.getSeverity() == IncidentSeverity.CRITICAL ? IncidentSeverity.HIGH : IncidentSeverity.CRITICAL;
        Integer auditBefore = countAudit("INCIDENT_SEVERITY_RULE", rule.getId().toString());

        MvcResult ok = mockMvc.perform(put("/api/incident-severity-rules/" + rule.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"" + target + "\",\"version\":" + version + "}"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(data(ok).path("severity").asText()).isEqualTo(target.name());
        assertThat(data(ok).path("version").asLong()).isEqualTo(version + 1);
        assertThat(countAudit("INCIDENT_SEVERITY_RULE", rule.getId().toString())).isEqualTo(auditBefore + 1);

        mockMvc.perform(put("/api/incident-severity-rules/" + rule.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"HIGH\",\"version\":" + version + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ERR_INCPOL_004"));

        mockMvc.perform(put("/api/incident-severity-rules/" + rule.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"severity\":\"HIGH\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"));
    }

    private Integer countAudit(String targetType, String targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE target_type = ? AND target_id = ?", Integer.class, targetType, targetId);
    }

    // ================================================================== API chính sách dispatch

    @Test
    @DisplayName("PUT chính sách: ngoài phạm vi K2 -> 400 ERR_INCPOL_005/006/007; hợp lệ -> 200; FM -> 403")
    void dispatchPolicy_update() throws Exception {
        long version = dispatchPolicyRepository.findById(IncidentSeverity.MEDIUM).orElseThrow().getVersion();

        mockMvc.perform(put("/api/incident-dispatch-policies/MEDIUM").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody(11, 60, 15, version)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_INCPOL_005"));
        mockMvc.perform(put("/api/incident-dispatch-policies/MEDIUM").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody(1, 601, 15, version)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_INCPOL_006"));
        mockMvc.perform(put("/api/incident-dispatch-policies/MEDIUM").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody(1, 60, 0, version)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_INCPOL_007"));
        mockMvc.perform(put("/api/incident-dispatch-policies/MEDIUM").header("Authorization", bearer(fm))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody(1, 60, 15, version)))
                .andExpect(status().isForbidden());

        int newSize = mediumOriginal.getStep1Size() == 3 ? 4 : 3;
        mockMvc.perform(put("/api/incident-dispatch-policies/MEDIUM").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(policyBody(newSize, 60, 15, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step1Size").value(newSize))
                .andExpect(jsonPath("$.data.version").value((int) (version + 1)));
        assertThat(countAudit("INCIDENT_DISPATCH_POLICY", "MEDIUM")).isPositive();
    }

    private static String policyBody(int size, int timeout, int escalate, long version) {
        return "{\"step1Size\":" + size + ",\"step1TimeoutSeconds\":" + timeout + ",\"escalateFmMinutes\":" + escalate
                + ",\"notifyFmImmediately\":false,\"version\":" + version + "}";
    }

    // ================================================================== API vị trí trực

    @Test
    @DisplayName("Vị trí trực: ngừng PATROL -> 400 ERR_INCPOL_008; FM đọc được danh sách đang dùng, không sửa được")
    void dutyPosts() throws Exception {
        long version = dutyPostTypeRepository.findById("PATROL").orElseThrow().getVersion();

        mockMvc.perform(put("/api/duty-post-types/PATROL").header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Tuần tra\",\"dispatchable\":true,\"isActive\":false,\"sortOrder\":1,\"version\":" + version + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_INCPOL_008"));

        mockMvc.perform(get("/api/duty-post-types/active").header("Authorization", bearer(fm)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("PATROL"));
        mockMvc.perform(get("/api/duty-post-types").header("Authorization", bearer(fm)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/duty-post-types/GATE").header("Authorization", bearer(fm))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Trực cổng\",\"dispatchable\":false,\"isActive\":true,\"sortOrder\":2,\"version\":0}"))
                .andExpect(status().isForbidden());
    }

    // ================================================================== API xếp ca

    @Test
    @DisplayName("Xếp ca: không gửi dutyPostCode -> PATROL; gửi GATE -> GATE; mã không tồn tại -> 400; sửa không gửi -> giữ nguyên")
    void shift_dutyPostCode() throws Exception {
        MvcResult noPost = createShift("08:00", null);
        assertThat(noPost.getResponse().getStatus()).isEqualTo(201);
        assertThat(data(noPost).path("dutyPostCode").asText()).isEqualTo("PATROL");

        MvcResult gate = createShift("09:00", "GATE");
        assertThat(gate.getResponse().getStatus()).isEqualTo(201);
        assertThat(data(gate).path("dutyPostCode").asText()).isEqualTo("GATE");

        MvcResult unknown = createShift("10:00", "NOT_A_POST");
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);

        String gateId = data(gate).path("id").asText();
        mockMvc.perform(put("/api/guard-shifts/" + gateId).header("Authorization", bearer(fm))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guardId\":\"" + guard.getId() + "\",\"shiftDate\":\"2031-05-20\",\"shiftType\":\"SHIFT_MORNING\","
                                + "\"startTime\":\"09:00\",\"endTime\":\"13:00\",\"notes\":\"sửa\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dutyPostCode").value("GATE"));
    }

    private MvcResult createShift(String start, String dutyPostCode) throws Exception {
        String post = dutyPostCode != null ? ",\"dutyPostCode\":\"" + dutyPostCode + "\"" : "";
        MvcResult r = mockMvc.perform(post("/api/guard-shifts").header("Authorization", bearer(fm))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"guardId\":\"" + guard.getId() + "\",\"shiftDate\":\"2031-05-20\",\"shiftType\":\"SHIFT_MORNING\","
                                + "\"startTime\":\"" + start + "\",\"endTime\":\"13:00\"" + post + "}"))
                .andReturn();
        if (r.getResponse().getStatus() == 201) {
            createdShiftIds.add(UUID.fromString(data(r).path("id").asText()));
        }
        return r;
    }
}
