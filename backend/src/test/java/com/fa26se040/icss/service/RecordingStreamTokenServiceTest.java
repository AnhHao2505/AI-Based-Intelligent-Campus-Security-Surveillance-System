package com.fa26se040.icss.service;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.exception.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BE-03: token xem video ghi hình — ký theo path + source, hết hạn theo cấu hình. */
class RecordingStreamTokenServiceTest {

    private static final String KEY = "test-only-internal-service-key-0123456789";
    private static final String PATH = "CAM-001/2026/11/12/10-00-00.mp4";
    private static final Instant NOW = Instant.parse("2026-11-12T03:00:00Z");

    /** V121: thời hạn đọc từ RECORDING_STREAM_TOKEN_SECONDS. */
    static SystemConfigService ttlConfig(int seconds) {
        SystemConfigService config = mock(SystemConfigService.class);
        when(config.getInt(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS)).thenReturn(seconds);
        return config;
    }

    private RecordingStreamTokenService at(Instant instant) {
        return new RecordingStreamTokenService(KEY, ttlConfig(300), Clock.fixed(instant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("Token vừa cấp mở được đúng file, đúng nguồn")
    void issuedToken_verifiesForSamePathAndSource() {
        RecordingStreamTokenService service = at(NOW);
        RecordingStreamTokenService.TokenResponse token = service.issue(PATH, "minio_recording");

        assertEquals(NOW.getEpochSecond() + 300, token.expiresAt());
        assertDoesNotThrow(() -> service.verify(PATH, "minio_recording", token.token()));
    }

    @Test
    @DisplayName("source null / rỗng ký như 'recording' (giá trị mặc định của controller)")
    void blankSource_treatedAsDefaultRecording() {
        RecordingStreamTokenService service = at(NOW);
        String token = service.issue(PATH, null).token();

        assertDoesNotThrow(() -> service.verify(PATH, "recording", token));
    }

    @Test
    @DisplayName("Thiếu token -> 401, câu hướng dẫn mở lại video")
    void missingToken_throwsUnauthorized() {
        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> at(NOW).verify(PATH, "recording", null));
        assertEquals(RecordingStreamTokenService.MSG_MISSING, ex.getMessage());

        assertThrows(UnauthorizedException.class, () -> at(NOW).verify(PATH, "recording", "  "));
    }

    @Test
    @DisplayName("Token hết hạn -> 401 'đã hết hạn'")
    void expiredToken_throwsUnauthorized() {
        String token = at(NOW).issue(PATH, "recording").token();

        assertDoesNotThrow(() -> at(NOW.plusSeconds(300)).verify(PATH, "recording", token));
        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> at(NOW.plusSeconds(301)).verify(PATH, "recording", token));
        assertEquals(RecordingStreamTokenService.MSG_EXPIRED, ex.getMessage());
    }

    @Test
    @DisplayName("Token của file khác, nguồn khác hoặc bị sửa hạn -> 401 'không hợp lệ'")
    void tokenForOtherFileOrTampered_throwsUnauthorized() {
        RecordingStreamTokenService service = at(NOW);
        String token = service.issue(PATH, "recording").token();
        String forgedExp = (NOW.getEpochSecond() + 9999) + token.substring(token.indexOf('.'));

        for (Runnable call : new Runnable[]{
                () -> service.verify("CAM-002/2026/11/12/10-00-00.mp4", "recording", token),
                () -> service.verify(PATH, "evidence", token),
                () -> service.verify(PATH, "recording", forgedExp),
                () -> service.verify(PATH, "recording", "abc"),
                () -> service.verify(PATH, "recording", "abc.def"),
                () -> service.verify(PATH, "recording", token + "00")
        }) {
            UnauthorizedException ex = assertThrows(UnauthorizedException.class, call::run);
            assertEquals(RecordingStreamTokenService.MSG_INVALID, ex.getMessage());
        }
    }

    @Test
    @DisplayName("Token ký bằng khoá khác -> 401")
    void tokenSignedWithOtherKey_throwsUnauthorized() {
        RecordingStreamTokenService other = new RecordingStreamTokenService(
                "another-key-another-key-another-key-0000", ttlConfig(300), Clock.fixed(NOW, ZoneOffset.UTC));
        String token = other.issue(PATH, "recording").token();

        assertThrows(UnauthorizedException.class, () -> at(NOW).verify(PATH, "recording", token));
    }

    @Test
    @DisplayName("V121: thời hạn đọc mỗi lần cấp; giá trị <= 0 (sửa thẳng DB) -> dùng mặc định 300 của ConfigKey")
    void ttlReadOnEveryIssue_invalidFallsBackToDefault() {
        SystemConfigService config = ttlConfig(120);
        RecordingStreamTokenService service = new RecordingStreamTokenService(KEY, config, Clock.fixed(NOW, ZoneOffset.UTC));
        assertEquals(NOW.getEpochSecond() + 120, service.issue(PATH, "recording").expiresAt());

        when(config.getInt(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS)).thenReturn(0);
        assertEquals(NOW.getEpochSecond() + 300, service.issue(PATH, "recording").expiresAt());
    }
}
