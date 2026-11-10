package com.fa26se040.icss.audit;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AuditWriteException;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fa26se040.icss.service.AuditService;
import com.fa26se040.icss.service.FaceDataService;
import com.fa26se040.icss.service.MinioStorageService;
import com.fa26se040.icss.service.NotificationService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * PR-AUDIT (B5, mẫu E.2): ghi audit CREATE / USER_ACCOUNT lỗi -> cả thao tác tạo tài khoản rollback: không có user,
 * không có face_data, không có audit REGISTER_FACE, không gửi mail, ảnh MinIO được xoá bù.
 * Tách class riêng vì @SpyBean AuditService tạo Spring context riêng.
 */
class AccountAuditRollbackTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private FaceDataService faceDataService;

    @SpyBean private AuditService auditService;
    @MockBean private MinioStorageService minioStorageService;
    @MockBean private NotificationService notificationService;

    private AiServiceRestTemplate aiRestTemplate;
    private ClientHttpRequestFactory originalFactory;
    private MockRestServiceServer aiServer;
    private User admin;

    @BeforeAll
    void setUp() {
        aiRestTemplate = (AiServiceRestTemplate) ReflectionTestUtils.getField(faceDataService, "restTemplate");
        originalFactory = (ClientHttpRequestFactory) ReflectionTestUtils.getField(aiRestTemplate, "requestFactory");
        aiServer = MockRestServiceServer.bindTo(aiRestTemplate).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepository.save(User.builder()
                .userCode("AUDRB-ADM-" + suffix)
                .fullName("Audit rollback admin " + suffix)
                .email("audrb-adm-" + suffix + "@fpt.edu.vn")
                .role(Role.ADMIN)
                .accessLevel(3)
                .isActive(true)
                .build());
    }

    @AfterAll
    void cleanUp() {
        aiRestTemplate.setRequestFactory(originalFactory);
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", admin.getId());
    }

    @Test
    @DisplayName("B5: lỗi ghi audit CREATE khi tạo tài khoản -> 500, tài khoản / face_data / audit REGISTER_FACE không tồn tại, không gửi mail")
    void auditFailure_rollsBackAccountCreation() throws Exception {
        String code = "AUDRB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String vector = IntStream.range(0, 512).mapToObj(i -> "0.03").collect(Collectors.joining(","));
        aiServer.expect(requestTo(endsWith("/api/v1/faces/process-registration")))
                .andRespond(withSuccess("{\"success\":true,\"code\":\"" + code + "\",\"face_count\":1,\"embedding_front\":["
                        + vector + "]}", MediaType.APPLICATION_JSON));
        when(minioStorageService.uploadFaceImage(any(), anyString())).thenReturn("face-profiles/" + code + ".jpg");
        doThrow(new AuditWriteException("giả lập lỗi ghi audit", new RuntimeException("db down")))
                .when(auditService).record(eq(AuditTargetType.USER_ACCOUNT), eq(AuditAction.CREATE), anyString(),
                        any(), any(), any(), any(), any(), any(com.fa26se040.icss.dto.audit.AuditActor.class));

        MvcResult r = mockMvc.perform(multipart("/api/users/staff-accounts")
                        .file(new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", new byte[]{1, 2, 3}))
                        .param("fullName", "Audit Rollback " + code)
                        .param("userCode", code)
                        .param("email", code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                        .param("role", Role.NORMAL_USER.name())
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin)))
                .andReturn();

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE user_code = ?", Integer.class, code)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM face_data WHERE code = ?", Integer.class, code)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_id = ?", Integer.class, code)).isZero();
        verify(minioStorageService).deleteFaceImage(code);
        verify(notificationService, org.mockito.Mockito.never())
                .sendStaffAccountSetupEmail(anyString(), anyString(), anyString(), anyString());
    }
}
