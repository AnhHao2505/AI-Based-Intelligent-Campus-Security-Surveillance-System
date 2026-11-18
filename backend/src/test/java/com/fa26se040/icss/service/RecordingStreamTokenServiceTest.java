package com.fa26se040.icss.service;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.UnauthorizedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** BE-03 & Security: token xem video ghi hình — ký theo path + source + user + role, hết hạn theo cấu hình. */
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

    private Authentication authUser(String email, String role) {
        return new UsernamePasswordAuthenticationToken(
                email, "password",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
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

    // =========================================================================
    // V2 User-Bound & Authority-Bound Security Tests
    // =========================================================================

    @Test
    @DisplayName("Token V2: Cấp cho GUARD thành công và mở đúng file với cùng tài khoản")
    void issueV2_guard_verifiesSuccessfullyWithSameUser() {
        RecordingStreamTokenService service = at(NOW);
        String user = "guard.security@fpt.edu.vn";
        RecordingStreamTokenService.TokenResponse response =
                service.issue(PATH, "minio_recording", user, "GUARD");

        assertNotNull(response.token());
        assertTrue(response.token().startsWith("V2."));
        assertEquals(user, response.userEmail());
        assertEquals("GUARD", response.userRole());

        Authentication auth = authUser(user, "GUARD");
        assertDoesNotThrow(() -> service.verify(PATH, "minio_recording", response.token(), auth));
    }

    @Test
    @DisplayName("Token V2: Cố tình cấp cho vai trò không có thẩm quyền (USER/STUDENT) -> 403 AccessDenied")
    void issueV2_unauthorizedRole_throwsAccessDenied() {
        RecordingStreamTokenService service = at(NOW);
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.issue(PATH, "minio_recording", "student@fpt.edu.vn", "USER"));
        assertEquals(RecordingStreamTokenService.MSG_UNAUTHORIZED_ROLE, ex.getMessage());
    }

    @Test
    @DisplayName("Token V2: Người dùng khác cố tình mở link cấp cho Guard -> 403 Forbidden chặn xem chéo")
    void verifyV2_crossUserAccess_throwsAccessDenied() {
        RecordingStreamTokenService service = at(NOW);
        String guardUser = "guard.security@fpt.edu.vn";
        RecordingStreamTokenService.TokenResponse response =
                service.issue(PATH, "recording", guardUser, "GUARD");

        // Một tài khoản khác (dù có role GUARD khác) cố mở link của guardUser
        Authentication otherGuard = authUser("other.guard@fpt.edu.vn", "GUARD");
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.verify(PATH, "recording", response.token(), otherGuard));
        assertEquals(RecordingStreamTokenService.MSG_CROSS_USER_ACCESS_DENIED, ex.getMessage());
    }

    @Test
    @DisplayName("Token V2: Quản trị viên (ADMIN) được phép mở kiểm tra video của Guard")
    void verifyV2_adminUser_canAccessGuardToken() {
        RecordingStreamTokenService service = at(NOW);
        String guardUser = "guard.security@fpt.edu.vn";
        RecordingStreamTokenService.TokenResponse response =
                service.issue(PATH, "recording", guardUser, "GUARD");

        Authentication adminAuth = authUser("admin.security@fpt.edu.vn", "ADMIN");
        assertDoesNotThrow(() -> service.verify(PATH, "recording", response.token(), adminAuth));
    }

    @Test
    @DisplayName("Token V2: Tài khoản không có thẩm quyền (USER) dù có link cũng bị chặn 403")
    void verifyV2_unauthorizedCaller_throwsAccessDenied() {
        RecordingStreamTokenService service = at(NOW);
        String guardUser = "guard.security@fpt.edu.vn";
        RecordingStreamTokenService.TokenResponse response =
                service.issue(PATH, "recording", guardUser, "GUARD");

        Authentication normalUser = authUser("normal.student@fpt.edu.vn", "NORMAL_USER");
        AccessDeniedException ex = assertThrows(AccessDeniedException.class,
                () -> service.verify(PATH, "recording", response.token(), normalUser));
        assertEquals(RecordingStreamTokenService.MSG_UNAUTHORIZED_ROLE, ex.getMessage());
    }

    @Test
    @DisplayName("Token V2: Token bị sửa thông tin user hoặc vai trò -> 401 Invalid")
    void verifyV2_tamperedToken_throwsUnauthorized() {
        RecordingStreamTokenService service = at(NOW);
        String guardUser = "guard.security@fpt.edu.vn";
        RecordingStreamTokenService.TokenResponse response =
                service.issue(PATH, "recording", guardUser, "GUARD");

        // Giả mạo đổi role thành ADMIN ở chuỗi token
        String[] parts = response.token().split("\\.");
        String forgedRoleToken = String.format("%s.%s.%s.%s.%s", parts[0], parts[1], parts[2], "ADMIN", parts[4]);

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> service.verify(PATH, "recording", forgedRoleToken));
        assertEquals(RecordingStreamTokenService.MSG_INVALID, ex.getMessage());
    }

    @Test
    @DisplayName("Token V2: Hết hạn -> 401 Expired")
    void verifyV2_expired_throwsUnauthorized() {
        RecordingStreamTokenService service = at(NOW);
        String guardUser = "guard.security@fpt.edu.vn";
        RecordingStreamTokenService.TokenResponse response =
                service.issue(PATH, "recording", guardUser, "GUARD");

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> at(NOW.plusSeconds(301)).verify(PATH, "recording", response.token()));
        assertEquals(RecordingStreamTokenService.MSG_EXPIRED, ex.getMessage());
    }

    @Test
    @DisplayName("parseTokenPayload trích xuất đúng thông tin cho cả V1 và V2")
    void parseTokenPayload_extractsCorrectMetadata() {
        RecordingStreamTokenService service = at(NOW);

        // V2
        RecordingStreamTokenService.TokenResponse v2 = service.issue(PATH, "recording", "guard@fpt.edu.vn", "GUARD");
        RecordingStreamTokenService.TokenPayload p2 = service.parseTokenPayload(v2.token());
        assertNotNull(p2);
        assertEquals("V2", p2.version());
        assertEquals("guard@fpt.edu.vn", p2.userEmail());
        assertEquals("GUARD", p2.userRole());

        // V1 legacy
        RecordingStreamTokenService.TokenResponse v1 = service.issue(PATH, "recording");
        RecordingStreamTokenService.TokenPayload p1 = service.parseTokenPayload(v1.token());
        assertNotNull(p1);
        assertEquals("V2", p1.version()); // delegate to V2 when context resolved

        // Malformed / V1 legacy token rejected
        assertNull(service.parseTokenPayload("invalid.token"));
        assertNull(service.parseTokenPayload("1700000000.deadbeef"));
        assertNull(service.parseTokenPayload(""));
        assertNull(service.parseTokenPayload(null));
    }

    @Test
    @DisplayName("F-07: Từ chối token V1 legacy không có tiền tố V2.")
    void verify_legacyV1Token_throwsUnauthorizedInvalid() {
        RecordingStreamTokenService service = at(NOW);
        String v1FormatToken = (NOW.getEpochSecond() + 300) + ".abcdef0123456789";

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> service.verify(PATH, "recording", v1FormatToken));
        assertEquals(RecordingStreamTokenService.MSG_INVALID, ex.getMessage());
    }
}
