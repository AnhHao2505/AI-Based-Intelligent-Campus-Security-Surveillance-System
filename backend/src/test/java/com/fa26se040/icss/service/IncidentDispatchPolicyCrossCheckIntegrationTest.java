package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.IncidentDispatchPolicy;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.IncidentDispatchPolicyRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ràng buộc chéo chính sách phân phối sự cố (Lucas chốt 14/11/2026): mức có đợt 1 phải có
 * escalate_fm_minutes × 60 > step1_timeout_seconds. Kiểm cả API (ERR_INCPOL_009) lẫn CHECK V124 của DB.
 * Dòng seed bị sửa trong test được trả lại giá trị cũ sau mỗi test.
 */
class IncidentDispatchPolicyCrossCheckIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private IncidentDispatchPolicyRepository dispatchPolicyRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private User admin;
    private final Map<IncidentSeverity, int[]> originals = new EnumMap<>(IncidentSeverity.class);

    @BeforeAll
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepository.save(User.builder()
                .email("v124.adm." + suffix + "@fpt.edu.vn")
                .userCode("V124-ADM-" + suffix.toUpperCase(Locale.ROOT))
                .fullName("V124 admin " + suffix)
                .role(Role.ADMIN)
                .accessLevel(1)
                .isActive(true)
                .build());
        for (IncidentDispatchPolicy p : dispatchPolicyRepository.findAll()) {
            originals.put(p.getSeverity(), new int[]{p.getStep1Size(), p.getStep1TimeoutSeconds(), p.getEscalateFmMinutes()});
        }
    }

    @AfterEach
    void restorePolicies() {
        // Ghi lại thẳng bằng SQL (giá trị gốc thoả CHECK), không qua @Version của entity
        originals.forEach((severity, v) -> jdbcTemplate.update(
                "UPDATE incident_dispatch_policies SET step1_size = ?, step1_timeout_seconds = ?, escalate_fm_minutes = ? "
                        + "WHERE severity = ?", v[0], v[1], v[2], severity.name()));
    }

    @AfterAll
    void deactivateAdmin() {
        admin.setIsActive(false);
        userRepository.save(admin);
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateToken(admin);
    }

    private static String body(int size, int timeout, int escalate, long version) {
        return "{\"step1Size\":" + size + ",\"step1TimeoutSeconds\":" + timeout + ",\"escalateFmMinutes\":" + escalate
                + ",\"notifyFmImmediately\":false,\"version\":" + version + "}";
    }

    private Integer auditCount(String severity) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE target_type = 'INCIDENT_DISPATCH_POLICY' AND target_id = ?",
                Integer.class, severity);
    }

    @Test
    @DisplayName("PUT vi phạm (mời 2, 120 giây, 1 phút) -> 400 ERR_INCPOL_009, câu lỗi nêu cả hai giá trị, không lưu, không audit")
    void put_violation_badRequest() throws Exception {
        IncidentDispatchPolicy before = dispatchPolicyRepository.findById(IncidentSeverity.MEDIUM).orElseThrow();
        int audits = auditCount("MEDIUM");

        mockMvc.perform(put("/api/incident-dispatch-policies/MEDIUM").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(2, 120, 1, before.getVersion())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_INCPOL_009"))
                .andExpect(jsonPath("$.message").value(
                        "Thời gian chờ trước khi báo Quản lý CSVC (1 phút = 60 giây) phải lớn hơn thời gian chờ đợt 1 (120 giây)"));

        IncidentDispatchPolicy after = dispatchPolicyRepository.findById(IncidentSeverity.MEDIUM).orElseThrow();
        assertThat(after.getStep1Size()).isEqualTo(before.getStep1Size());
        assertThat(after.getStep1TimeoutSeconds()).isEqualTo(before.getStep1TimeoutSeconds());
        assertThat(after.getEscalateFmMinutes()).isEqualTo(before.getEscalateFmMinutes());
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(auditCount("MEDIUM")).isEqualTo(audits);
    }

    @Test
    @DisplayName("PUT biên bằng nhau (1 phút = 60 giây) -> 400 ERR_INCPOL_009; hợp lệ 31 giây / 1 phút -> 200")
    void put_boundaryRejected_validAccepted() throws Exception {
        long version = dispatchPolicyRepository.findById(IncidentSeverity.MEDIUM).orElseThrow().getVersion();

        mockMvc.perform(put("/api/incident-dispatch-policies/MEDIUM").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, 60, 1, version)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("ERR_INCPOL_009"));

        mockMvc.perform(put("/api/incident-dispatch-policies/MEDIUM").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(1, 31, 1, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step1TimeoutSeconds").value(31))
                .andExpect(jsonPath("$.data.escalateFmMinutes").value(1));
    }

    @Test
    @DisplayName("PUT mời 0 người (không có đợt 1): 600 giây / 1 phút -> 200, không áp ràng buộc chéo")
    void put_noStep1_notChecked() throws Exception {
        long version = dispatchPolicyRepository.findById(IncidentSeverity.CRITICAL).orElseThrow().getVersion();

        mockMvc.perform(put("/api/incident-dispatch-policies/CRITICAL").header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(0, 600, 1, version)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.step1TimeoutSeconds").value(600));
    }

    @Test
    @DisplayName("V124: CHECK chk_incident_dispatch_policies_escalate_after_step1 có trên bảng, đúng biểu thức")
    void migration_constraintPresent() {
        String def = jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                        + "WHERE conname = 'chk_incident_dispatch_policies_escalate_after_step1'",
                String.class);
        assertThat(def).contains("step1_size = 0").contains("escalate_fm_minutes * 60").contains("> step1_timeout_seconds");
        Integer applied = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '124' AND success", Integer.class);
        assertThat(applied).isEqualTo(1);
    }

    @Test
    @DisplayName("V124: ghi thẳng repository / SQL vi phạm hoặc bằng biên -> DB chặn; mời 0 người thì DB cho ghi")
    void migration_checkBlocksDirectWrites() {
        IncidentDispatchPolicy medium = dispatchPolicyRepository.findById(IncidentSeverity.MEDIUM).orElseThrow();
        medium.setStep1Size((short) 2);
        medium.setStep1TimeoutSeconds(120);
        medium.setEscalateFmMinutes(1);
        assertThatThrownBy(() -> dispatchPolicyRepository.saveAndFlush(medium))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE incident_dispatch_policies SET step1_size = 1, step1_timeout_seconds = 60, escalate_fm_minutes = 1 "
                        + "WHERE severity = 'MEDIUM'"))
                .isInstanceOf(DataIntegrityViolationException.class);

        int rows = jdbcTemplate.update(
                "UPDATE incident_dispatch_policies SET step1_size = 0, step1_timeout_seconds = 600, escalate_fm_minutes = 1 "
                        + "WHERE severity = 'CRITICAL'");
        assertThat(rows).isEqualTo(1);

        IncidentDispatchPolicy reloaded = dispatchPolicyRepository.findById(IncidentSeverity.MEDIUM).orElseThrow();
        int[] original = originals.get(IncidentSeverity.MEDIUM);
        assertThat(reloaded.getEscalateFmMinutes()).isEqualTo(original[2]);
    }
}
