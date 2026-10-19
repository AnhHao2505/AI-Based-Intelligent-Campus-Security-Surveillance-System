package com.fa26se040.icss.controller;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AuditWriteException;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fa26se040.icss.service.AuditService;
import com.fa26se040.icss.service.MinioStorageService;
import io.minio.StatObjectResponse;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * /api/media sau khi sửa bảo mật PR #101 (M1–M5): JWT bắt buộc, phân quyền theo bucket, kiểm objectName,
 * stream + header không cache, audit VIEW_PHOTO. MinIO là mock; audit ghi thật vào campus_security_test.
 */
class MediaControllerTest extends AbstractIntegrationTest {

    private static final String FACES = "face-profiles";
    private static final String EVIDENCE = "security-evidence";
    private static final String SNAPSHOTS = "camera-snapshots";
    private static final byte[] IMAGE = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02};

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @PersistenceContext
    private EntityManager entityManager;

    @MockBean
    private MinioStorageService minioStorageService;
    @SpyBean
    private AuditService auditService;

    private String suffix;
    private User admin;
    private User fm;
    private User guard;
    private User normalUser;

    @BeforeEach
    void setUp() {
        when(minioStorageService.getFacesBucket()).thenReturn(FACES);
        when(minioStorageService.getEvidenceBucket()).thenReturn(EVIDENCE);
        when(minioStorageService.getSnapshotsBucket()).thenReturn(SNAPSHOTS);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = saveUser("MDAD", Role.ADMIN);
        fm = saveUser("MDFM", Role.FACILITY_MANAGER);
        guard = saveUser("MDGD", Role.GUARD);
        normalUser = saveUser("MDNU", Role.NORMAL_USER);
    }

    private User saveUser(String prefix, Role role) {
        return userRepository.save(User.builder()
                .userCode(prefix + "-" + suffix)
                .fullName(prefix + " " + suffix)
                .email(prefix.toLowerCase() + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(2)
                .isActive(true)
                .build());
    }

    private MockHttpServletRequestBuilder getAs(User user, String path) {
        return get(path).header("Authorization", "Bearer " + jwtTokenProvider.generateToken(user));
    }

    private void givenObject(String bucket, String objectName, String contentType) throws Exception {
        StatObjectResponse stat = mock(StatObjectResponse.class);
        when(stat.contentType()).thenReturn(contentType);
        when(stat.size()).thenReturn((long) IMAGE.length);
        when(minioStorageService.statObject(bucket, objectName)).thenReturn(Optional.of(stat));
        when(minioStorageService.getObject(bucket, objectName)).thenReturn(new ByteArrayInputStream(IMAGE));
    }

    private long auditCount(AuditTargetType targetType, String targetId, User actor) {
        return entityManager.createQuery(
                        "SELECT COUNT(a) FROM AuditLog a WHERE a.targetType = :t AND a.action = :act "
                                + "AND a.targetId = :id AND a.changedBy.id = :uid", Long.class)
                .setParameter("t", targetType)
                .setParameter("act", AuditAction.VIEW_PHOTO)
                .setParameter("id", targetId)
                .setParameter("uid", actor.getId())
                .getSingleResult();
    }

    private long auditCountByTargetId(String targetId) {
        return entityManager.createQuery("SELECT COUNT(a) FROM AuditLog a WHERE a.targetId = :id", Long.class)
                .setParameter("id", targetId)
                .getSingleResult();
    }

    // ===== M1: JWT bắt buộc =====

    @Test
    @DisplayName("M1: không token -> 401 (cả 3 bucket và bucket lạ), không chạm MinIO")
    void noToken_401() throws Exception {
        for (String path : new String[]{"/api/media/face-profiles/SV-1/a.jpg", "/api/media/security-evidence/a.jpg",
                "/api/media/camera-snapshots/CAM-1/a.jpg", "/api/media/unknown-bucket/a.jpg"}) {
            mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        verify(minioStorageService, never()).statObject(anyString(), anyString());
    }

    // ===== M4: phân quyền theo bucket =====

    @Test
    @DisplayName("M4: NORMAL_USER -> 403 cả 3 bucket, không chạm MinIO")
    void normalUser_403_allBuckets() throws Exception {
        for (String bucket : new String[]{FACES, EVIDENCE, SNAPSHOTS}) {
            mockMvc.perform(getAs(normalUser, "/api/media/" + bucket + "/x/a.jpg")).andExpect(status().isForbidden());
        }
        verify(minioStorageService, never()).statObject(anyString(), anyString());
    }

    @Test
    @DisplayName("M4: FACILITY_MANAGER, GUARD -> 403 face-profiles và camera-snapshots, 200 security-evidence")
    void fmAndGuard_onlyEvidence() throws Exception {
        String evidence = "2026-10-18/CAM-" + suffix + "/INTRUSION_Track1_101500.jpg";
        for (User user : new User[]{fm, guard}) {
            givenObject(EVIDENCE, evidence, "image/jpeg");
            mockMvc.perform(getAs(user, "/api/media/face-profiles/SV-1/SV-1_front.jpg")).andExpect(status().isForbidden());
            mockMvc.perform(getAs(user, "/api/media/camera-snapshots/CAM-1/roi.jpg")).andExpect(status().isForbidden());
            mockMvc.perform(getAs(user, "/api/media/security-evidence/" + evidence))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes(IMAGE));
        }
    }

    @Test
    @DisplayName("M4: ADMIN -> 200 cả 3 bucket")
    void admin_200_allBuckets() throws Exception {
        String face = "FACE-" + suffix + "/FACE-" + suffix + "_front.jpg";
        String evidence = "2026-10-18/CAM-" + suffix + "/UNKNOWN_PERSON_Track2_101500.jpg";
        String snapshot = "CAM-" + suffix + "/roi_reference_20261018_101500.jpg";
        givenObject(FACES, face, "image/jpeg");
        givenObject(EVIDENCE, evidence, "image/jpeg");
        givenObject(SNAPSHOTS, snapshot, "image/jpeg");

        mockMvc.perform(getAs(admin, "/api/media/face-profiles/" + face)).andExpect(status().isOk()).andExpect(content().bytes(IMAGE));
        mockMvc.perform(getAs(admin, "/api/media/security-evidence/" + evidence)).andExpect(status().isOk()).andExpect(content().bytes(IMAGE));
        mockMvc.perform(getAs(admin, "/api/media/camera-snapshots/" + snapshot)).andExpect(status().isOk()).andExpect(content().bytes(IMAGE));
    }

    @Test
    @DisplayName("M4: bucket ngoài bảng -> 403 kể cả ADMIN")
    void unknownBucket_403() throws Exception {
        mockMvc.perform(getAs(admin, "/api/media/unauthorized-bucket/secret.txt")).andExpect(status().isForbidden());
        verify(minioStorageService, never()).statObject(anyString(), anyString());
    }

    // ===== B3: objectName =====

    @Test
    @DisplayName("B3: objectName chứa '..' -> 400, không chạm MinIO")
    void objectNameWithDotDot_400() throws Exception {
        // "evidence..jpg" không phải segment ".." nên lọt qua firewall của Spring Security -> 400 do MediaController.validateObjectName
        MvcResult result = mockMvc.perform(getAs(admin, "/api/media/security-evidence/2026-10-18/evidence..jpg"))
                .andExpect(status().isBadRequest())
                .andReturn();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("Đường dẫn ảnh không hợp lệ"), body);
        verify(minioStorageService, never()).statObject(anyString(), anyString());
    }

    @Test
    @DisplayName("B3: quy tắc objectName (rỗng, '..', bắt đầu '/', ký tự điều khiển, > 100) -> IllegalArgumentException (400)")
    void validateObjectName_rules() {
        assertThrows(IllegalArgumentException.class, () -> MediaController.validateObjectName(null));
        assertThrows(IllegalArgumentException.class, () -> MediaController.validateObjectName(" "));
        assertThrows(IllegalArgumentException.class, () -> MediaController.validateObjectName("a/../b.jpg"));
        assertThrows(IllegalArgumentException.class, () -> MediaController.validateObjectName("/abs.jpg"));
        assertThrows(IllegalArgumentException.class, () -> MediaController.validateObjectName("a\nb.jpg"));
        assertThrows(IllegalArgumentException.class, () -> MediaController.validateObjectName("a\u0000b.jpg"));
        assertThrows(IllegalArgumentException.class, () -> MediaController.validateObjectName("a".repeat(101)));
        MediaController.validateObjectName("a".repeat(100));
        MediaController.validateObjectName("SV-1/SV-1_front.jpg");
    }

    @Test
    @DisplayName("B3: objectName đúng 100 ký tự -> không bị chặn (200, ghi audit đủ targetId); 101 ký tự -> 400")
    void objectNameLengthLimit_100() throws Exception {
        String prefix = "2026-10-18/CAM-" + suffix + "/";
        String exactly100 = prefix + "e".repeat(100 - prefix.length() - 4) + ".jpg";
        String length101 = prefix + "e".repeat(101 - prefix.length() - 4) + ".jpg";
        assertEquals(100, exactly100.length());
        assertEquals(101, length101.length());
        givenObject(EVIDENCE, exactly100, "image/jpeg");

        mockMvc.perform(getAs(admin, "/api/media/security-evidence/" + exactly100))
                .andExpect(status().isOk())
                .andExpect(content().bytes(IMAGE));
        assertEquals(1, auditCount(AuditTargetType.SECURITY_EVIDENCE, exactly100, admin));

        mockMvc.perform(getAs(admin, "/api/media/security-evidence/" + length101)).andExpect(status().isBadRequest());
        verify(minioStorageService, never()).statObject(EVIDENCE, length101);
    }

    @Test
    @DisplayName("B3: object không tồn tại -> 404, body không chứa thông điệp MinIO")
    void objectNotFound_404() throws Exception {
        when(minioStorageService.statObject(EVIDENCE, "missing.jpg")).thenReturn(Optional.empty());

        MvcResult result = mockMvc.perform(getAs(admin, "/api/media/security-evidence/missing.jpg"))
                .andExpect(status().isNotFound())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertFalse(body.contains("NoSuchKey"), body);
        verify(minioStorageService, never()).getObject(anyString(), anyString());
    }

    @Test
    @DisplayName("Lỗi storage khác -> 500, body không chứa thông điệp MinIO")
    void storageError_500_withoutMinioMessage() throws Exception {
        when(minioStorageService.statObject(EVIDENCE, "broken.jpg"))
                .thenThrow(new RuntimeException("minio internal detail: bucket secret-xyz"));

        MvcResult result = mockMvc.perform(getAs(admin, "/api/media/security-evidence/broken.jpg"))
                .andExpect(status().isInternalServerError())
                .andReturn();

        assertFalse(result.getResponse().getContentAsString().contains("secret-xyz"));
    }

    // ===== M3 / M5: header, stream, content-type =====

    @Test
    @DisplayName("M3/M5: Cache-Control = private, no-store; nosniff; Content-Type từ statObject; Content-Length")
    void headersAndContentType() throws Exception {
        String snapshot = "CAM-" + suffix + "/roi.png";
        givenObject(SNAPSHOTS, snapshot, "image/png");

        mockMvc.perform(getAs(admin, "/api/media/camera-snapshots/" + snapshot))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().longValue("Content-Length", IMAGE.length))
                .andExpect(content().bytes(IMAGE));
    }

    @Test
    @DisplayName("M5: storage không có content-type -> application/octet-stream")
    void missingContentType_fallsBackToOctetStream() throws Exception {
        String snapshot = "CAM-" + suffix + "/roi.bin";
        givenObject(SNAPSHOTS, snapshot, null);

        mockMvc.perform(getAs(admin, "/api/media/camera-snapshots/" + snapshot))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"));
    }

    // ===== M4 / B5: audit xem ảnh =====

    @Test
    @DisplayName("B5: xem face-profiles ghi đúng 1 dòng audit FACE_PROFILE / VIEW_PHOTO, targetId = userCode, actor = người gọi")
    void viewFaceProfile_writesOneAuditRow() throws Exception {
        String userCode = "FACE-" + suffix;
        String face = userCode + "/" + userCode + "_front.jpg";
        givenObject(FACES, face, "image/jpeg");

        mockMvc.perform(getAs(admin, "/api/media/face-profiles/" + face)).andExpect(status().isOk());

        assertEquals(1, auditCount(AuditTargetType.FACE_PROFILE, userCode, admin));
        assertEquals(1, auditCountByTargetId(userCode));
        assertEquals(0, auditCountByTargetId(face));
    }

    @Test
    @DisplayName("B5: xem security-evidence ghi đúng 1 dòng audit SECURITY_EVIDENCE / VIEW_PHOTO, targetId = objectName, actor = người gọi")
    void viewEvidence_writesOneAuditRow() throws Exception {
        String evidence = "2026-10-18/CAM-" + suffix + "/INTRUSION_Track7_101500.jpg";
        givenObject(EVIDENCE, evidence, "image/jpeg");

        mockMvc.perform(getAs(guard, "/api/media/security-evidence/" + evidence)).andExpect(status().isOk());

        assertEquals(1, auditCount(AuditTargetType.SECURITY_EVIDENCE, evidence, guard));
        assertEquals(1, auditCountByTargetId(evidence));
    }

    @Test
    @DisplayName("V81_1: ADMIN xem được nhật ký module SECURITY (có dòng VIEW_PHOTO vừa ghi); FACILITY_MANAGER -> 403")
    void securityAuditModule_visibleToAdminOnly() throws Exception {
        String userCode = "FACE-" + suffix;
        String face = userCode + "/" + userCode + "_front.jpg";
        givenObject(FACES, face, "image/jpeg");
        mockMvc.perform(getAs(admin, "/api/media/face-profiles/" + face)).andExpect(status().isOk());

        MvcResult result = mockMvc.perform(getAs(admin, "/api/access-control/audit-logs?module=SECURITY&changedBy=" + admin.getId()))
                .andExpect(status().isOk())
                .andReturn();
        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(body.contains("\"targetId\":\"" + userCode + "\""), body);
        assertTrue(body.contains("FACE_PROFILE") && body.contains("VIEW_PHOTO"), body);

        mockMvc.perform(getAs(fm, "/api/access-control/audit-logs?module=SECURITY")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("B5: xem camera-snapshots không ghi audit")
    void viewSnapshot_noAudit() throws Exception {
        String snapshot = "CAM-" + suffix + "/roi_reference_20261018_101500.jpg";
        givenObject(SNAPSHOTS, snapshot, "image/jpeg");

        mockMvc.perform(getAs(admin, "/api/media/camera-snapshots/" + snapshot)).andExpect(status().isOk());

        assertEquals(0, auditCountByTargetId(snapshot));
        assertEquals(0, auditCountByTargetId("CAM-" + suffix));
    }

    @Test
    @DisplayName("B5: không ghi được audit -> 500, không trả ảnh, đóng stream")
    void auditFailure_500_noImage() throws Exception {
        String evidence = "2026-10-18/CAM-" + suffix + "/INTRUSION_Track9_101500.jpg";
        givenObject(EVIDENCE, evidence, "image/jpeg");
        AtomicBoolean closed = new AtomicBoolean(false);
        when(minioStorageService.getObject(EVIDENCE, evidence)).thenReturn(new ByteArrayInputStream(IMAGE) {
            @Override
            public void close() {
                closed.set(true);
            }
        });
        doThrow(new AuditWriteException("Không ghi được nhật ký hệ thống"))
                .when(auditService).record(any(), any(), anyString(), any(), any(), any(), any(), any(), any(AuditActor.class));

        MvcResult result = mockMvc.perform(getAs(admin, "/api/media/security-evidence/" + evidence))
                .andExpect(status().isInternalServerError())
                .andReturn();

        byte[] body = result.getResponse().getContentAsByteArray();
        assertFalse(body.length >= IMAGE.length && body[0] == IMAGE[0] && body[1] == IMAGE[1], "không được trả ảnh khi audit lỗi");
        assertTrue(closed.get(), "stream phải được đóng khi audit lỗi");
        assertEquals(0, auditCountByTargetId(evidence));
    }
}
