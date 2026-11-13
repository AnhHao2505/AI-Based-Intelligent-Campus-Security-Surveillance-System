package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnnotatedStreamTokenServiceTest {

    private static final String TEST_KEY = "0123456789abcdef0123456789abcdef";
    private static final String TEST_CAMERA = "CAM-001";
    private static final long TEST_EXP = 1893456000L;
    private static final String EXPECTED_TOKEN = "1893456000.83208ab377034e26a5a0ba3f022dae5dcc22f673d99c2de3ab46f8d39f93fcde";

    private final CameraRepository cameraRepository = mock(CameraRepository.class);

    /** V121: thời hạn đọc từ ANNOTATED_STREAM_TOKEN_SECONDS. */
    private static SystemConfigService ttlConfig(long seconds) {
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getInt(ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS)).thenReturn((int) seconds);
        return config;
    }

    private Camera camera(CameraStatus status) {
        return Camera.builder()
                .id(UUID.randomUUID())
                .cameraCode(TEST_CAMERA)
                .name("Camera Cổng")
                .status(status)
                .build();
    }

    @Test
    @DisplayName("T1: Vector chung Java/Python khớp chính xác token và chữ ký")
    void testT1CommonVector() {
        String token = AnnotatedStreamTokenService.generateToken(TEST_CAMERA, TEST_EXP, TEST_KEY);
        assertEquals(EXPECTED_TOKEN, token);

        // Không phân biệt chữ hoa/thường mã camera
        String lowerToken = AnnotatedStreamTokenService.generateToken("cam-001", TEST_EXP, TEST_KEY);
        assertEquals(EXPECTED_TOKEN, lowerToken);
    }

    @Test
    @DisplayName("T4: Issue token tạo token và hạn dùng đúng TTL")
    void testIssueTokenTTL() {
        long ttlSeconds = 60L;
        AnnotatedStreamTokenService service = new AnnotatedStreamTokenService(cameraRepository, TEST_KEY, ttlConfig(ttlSeconds));

        long before = Instant.now().getEpochSecond();
        AnnotatedStreamTokenService.TokenResponse response = service.issue(TEST_CAMERA);
        long after = Instant.now().getEpochSecond();

        assertNotNull(response);
        assertNotNull(response.token());
        assertTrue(response.expiresAt() >= before + ttlSeconds);
        assertTrue(response.expiresAt() <= after + ttlSeconds);

        assertTrue(response.token().startsWith(response.expiresAt() + "."));
    }

    @Test
    @DisplayName("issueForCamera: camera đang hoạt động -> token ký theo mã camera trong DB")
    void issueForCamera_activeCamera_signsWithStoredCameraCode() {
        AnnotatedStreamTokenService service = new AnnotatedStreamTokenService(cameraRepository, TEST_KEY, ttlConfig(60L));
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("cam-001"))
                .thenReturn(Optional.of(camera(CameraStatus.ACTIVE)));

        AnnotatedStreamTokenService.TokenResponse response = service.issueForCamera("cam-001");

        String expected = AnnotatedStreamTokenService.generateToken(TEST_CAMERA, response.expiresAt(), TEST_KEY);
        assertEquals(expected, response.token());
    }

    @Test
    @DisplayName("issueForCamera: camera không tồn tại -> ERR_CAM_002")
    void issueForCamera_cameraNotFound_throwsErrCam002() {
        AnnotatedStreamTokenService service = new AnnotatedStreamTokenService(cameraRepository, TEST_KEY, ttlConfig(60L));
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-404")).thenReturn(Optional.empty());

        CameraException ex = assertThrows(CameraException.class, () -> service.issueForCamera("CAM-404"));

        assertEquals(CameraErrorCode.ERR_CAM_002, ex.getErrorCode());
    }

    @Test
    @DisplayName("issueForCamera: camera DECOMMISSIONED -> ERR_STREAM_003")
    void issueForCamera_decommissionedCamera_throwsErrStream003() {
        AnnotatedStreamTokenService service = new AnnotatedStreamTokenService(cameraRepository, TEST_KEY, ttlConfig(60L));
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull(TEST_CAMERA))
                .thenReturn(Optional.of(camera(CameraStatus.DECOMMISSIONED)));

        CameraException ex = assertThrows(CameraException.class, () -> service.issueForCamera(TEST_CAMERA));

        assertEquals(CameraErrorCode.ERR_STREAM_003, ex.getErrorCode());
    }

    @Test
    @DisplayName("V121: thời hạn đọc mỗi lần cấp; giá trị <= 0 (sửa thẳng DB) -> dùng mặc định 60 của ConfigKey")
    void ttlReadOnEveryIssue_invalidFallsBackToDefault() {
        Instant now = Instant.parse("2026-11-13T03:00:00Z");
        SystemConfigService config = ttlConfig(45);
        AnnotatedStreamTokenService service = new AnnotatedStreamTokenService(cameraRepository, TEST_KEY, config,
                java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        assertEquals(now.getEpochSecond() + 45, service.issue(TEST_CAMERA).expiresAt());

        when(config.getInt(ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS)).thenReturn(-1);
        assertEquals(now.getEpochSecond() + 60, service.issue(TEST_CAMERA).expiresAt());
    }
}
