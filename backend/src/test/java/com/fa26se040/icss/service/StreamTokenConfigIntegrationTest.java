package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * V121: thời hạn token xem video ghi hình / live có chú thích nằm trong system_configurations, ADMIN sửa trên màn Cấu hình
 * là có hiệu lực ngay cho token cấp sau (không restart). Token đã cấp giữ hạn cũ (exp nằm trong token đã ký).
 */
class StreamTokenConfigIntegrationTest extends AbstractIntegrationTest {

    private static final String REASON = "Điều chỉnh thời hạn liên kết xem camera để thử nghiệm";
    private static final String PATH = "CAM-001/2026/11/13/10-00-00.mp4";

    @Autowired private SystemConfigService systemConfigService;
    @Autowired private RecordingStreamTokenService recordingStreamTokenService;
    @Autowired private AnnotatedStreamTokenService annotatedStreamTokenService;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    @Value("${icss.internal.service-key:}")
    private String serviceKey;

    private User admin;
    private String recordingBefore;
    private String annotatedBefore;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        admin = userRepository.save(User.builder()
                .userCode("STC-ADM-" + suffix)
                .email("stc.admin." + suffix + "@fpt.edu.vn")
                .fullName("Stream token config admin " + suffix)
                .role(Role.ADMIN)
                .accessLevel(3)
                .isActive(true)
                .build());
        recordingBefore = value(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS);
        annotatedBefore = value(ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS);
    }

    @AfterEach
    void restore() {
        if (!recordingBefore.equals(value(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS))) {
            systemConfigService.update(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS.getKey(), recordingBefore, REASON, admin.getEmail());
        }
        if (!annotatedBefore.equals(value(ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS))) {
            systemConfigService.update(ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS.getKey(), annotatedBefore, REASON, admin.getEmail());
        }
        admin.setIsActive(false);
        userRepository.save(admin);
    }

    private String value(ConfigKey key) {
        return jdbc.queryForObject("SELECT config_value FROM system_configurations WHERE config_key = ?", String.class, key.getKey());
    }

    private int historyCount(ConfigKey key) {
        return jdbc.queryForObject("SELECT count(*) FROM system_configuration_change_logs WHERE config_key = ?",
                Integer.class, key.getKey());
    }

    private static void assertExpiresIn(long expiresAt, long before, long after, int seconds) {
        assertTrue(expiresAt >= before + seconds && expiresAt <= after + seconds,
                "exp phải = now + " + seconds + " (exp=" + expiresAt + ", now trong [" + before + ", " + after + "])");
    }

    @Test
    @DisplayName("V121 có 2 khoá: RECORDING 300 (60–3600), ANNOTATED 60 (30–600), INTEGER, nhóm SECURITY, sửa được; ConfigKey cùng mặc định")
    void migrationSeedsBothKeys() {
        Map<String, Object> rec = jdbc.queryForMap("SELECT config_value, data_type, min_value, max_value, config_group, editable "
                + "FROM system_configurations WHERE config_key = 'RECORDING_STREAM_TOKEN_SECONDS'");
        assertEquals("300", rec.get("config_value"));
        assertEquals(60, ((Number) rec.get("min_value")).intValue());
        assertEquals(3600, ((Number) rec.get("max_value")).intValue());
        Map<String, Object> ann = jdbc.queryForMap("SELECT config_value, data_type, min_value, max_value, config_group, editable "
                + "FROM system_configurations WHERE config_key = 'ANNOTATED_STREAM_TOKEN_SECONDS'");
        assertEquals("60", ann.get("config_value"));
        assertEquals(30, ((Number) ann.get("min_value")).intValue());
        assertEquals(600, ((Number) ann.get("max_value")).intValue());
        for (Map<String, Object> row : new Map[]{rec, ann}) {
            assertEquals("INTEGER", row.get("data_type"));
            assertEquals("SECURITY", row.get("config_group"));
            assertEquals(true, row.get("editable"));
        }
        assertEquals("300", ConfigKey.RECORDING_STREAM_TOKEN_SECONDS.getDefaultValue());
        assertEquals("60", ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS.getDefaultValue());
    }

    @Test
    @DisplayName("ADMIN đổi RECORDING 300 -> 120: token cấp ngay sau đó có exp = now + 120, không restart; có 1 dòng lịch sử")
    void recordingTtlChange_appliesToNextToken() {
        systemConfigService.update(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS.getKey(), "300", REASON, admin.getEmail());
        long before = Instant.now().getEpochSecond();
        assertExpiresIn(recordingStreamTokenService.issue(PATH, "recording").expiresAt(), before, Instant.now().getEpochSecond(), 300);

        int history = historyCount(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS);
        systemConfigService.update(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS.getKey(), "120", REASON, admin.getEmail());

        before = Instant.now().getEpochSecond();
        RecordingStreamTokenService.TokenResponse token = recordingStreamTokenService.issue(PATH, "recording");
        assertExpiresIn(token.expiresAt(), before, Instant.now().getEpochSecond(), 120);
        recordingStreamTokenService.verify(PATH, "recording", token.token());
        assertEquals(history + 1, historyCount(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS));
    }

    @Test
    @DisplayName("ADMIN đổi ANNOTATED 60 -> 45: token live cấp ngay sau đó có exp = now + 45")
    void annotatedTtlChange_appliesToNextToken() {
        systemConfigService.update(ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS.getKey(), "45", REASON, admin.getEmail());

        long before = Instant.now().getEpochSecond();
        AnnotatedStreamTokenService.TokenResponse token = annotatedStreamTokenService.issue("CAM-STC-01");
        assertExpiresIn(token.expiresAt(), before, Instant.now().getEpochSecond(), 45);
        assertEquals(AnnotatedStreamTokenService.generateToken("CAM-STC-01", token.expiresAt(), serviceKey), token.token());
    }

    @Test
    @DisplayName("Ngoài min/max -> lỗi, giá trị giữ nguyên: RECORDING 59 / 3601, ANNOTATED 29 / 601")
    void outOfRange_rejected() {
        String rec = value(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS);
        String ann = value(ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS);
        for (String bad : new String[]{"59", "3601", "0", "-5", "abc"}) {
            assertThrows(IllegalArgumentException.class, () -> systemConfigService.update(
                    ConfigKey.RECORDING_STREAM_TOKEN_SECONDS.getKey(), bad, REASON, admin.getEmail()), bad);
        }
        for (String bad : new String[]{"29", "601"}) {
            assertThrows(IllegalArgumentException.class, () -> systemConfigService.update(
                    ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS.getKey(), bad, REASON, admin.getEmail()), bad);
        }
        assertEquals(rec, value(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS));
        assertEquals(ann, value(ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS));
    }

    @Test
    @DisplayName("Token cũ đã hết hạn vẫn 401 dù ADMIN nâng thời hạn lên 3600 (exp nằm trong token đã ký)")
    void expiredOldToken_staysExpiredAfterRaisingTtl() {
        systemConfigService.update(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS.getKey(), "120", REASON, admin.getEmail());
        // Token cấp 200 giây trước với hạn 120 giây
        String old = new RecordingStreamTokenService(serviceKey, systemConfigService,
                Clock.fixed(Instant.now().minusSeconds(200), ZoneOffset.UTC)).issue(PATH, "recording").token();

        systemConfigService.update(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS.getKey(), "3600", REASON, admin.getEmail());

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> recordingStreamTokenService.verify(PATH, "recording", old));
        assertEquals(RecordingStreamTokenService.MSG_EXPIRED, ex.getMessage());
    }
}
