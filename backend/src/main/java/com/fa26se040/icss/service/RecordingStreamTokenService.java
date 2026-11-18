package com.fa26se040.icss.service;

import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.UnauthorizedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * BE-03 & Security Fix: Cấp và xác thực token xem video ghi hình an ninh (Stream/Download Token).
 * <p>
 * Ngăn chặn lỗ hổng rò rỉ liên kết video:
 * - Token được ký số HMAC-SHA256 bảo mật bằng bí danh nội bộ (INTERNAL_SERVICE_KEY).
 * - Token V2 ràng buộc chặt chẽ danh tính người xin cấp (userEmail), thẩm quyền (userRole),
 *   đường dẫn tệp (path), nguồn tệp (source) và thời gian hết hạn (exp).
 * - Chỉ những vai trò được cấp phép (ADMIN, GUARD, FACILITY_MANAGER) mới được phép tạo và sử dụng token.
 * - Khi có người dùng khác (ví dụ: tài khoản sinh viên/người dùng thường) cố tình dùng link đã cấp
 *   cho Bảo vệ, hệ thống sẽ từ chối ngay lập tức với 403 Forbidden.
 * - Tương thích ngược an toàn với định dạng V1 cũ ({exp}.{hmac}).
 */
@Slf4j
@Service
public class RecordingStreamTokenService {

    public static final String DEFAULT_SOURCE = "recording";
    public static final String TOKEN_PREFIX_V2 = "V2";

    /** Các vai trò có thẩm quyền truy cập kho video an ninh. */
    public static final Set<String> ALLOWED_AUTHORITIES = Set.of(
            Role.ADMIN.name(),
            Role.GUARD.name(),
            Role.FACILITY_MANAGER.name()
    );

    static final String MSG_MISSING = "Chưa có mã xem video. Vui lòng mở lại video từ danh sách ghi hình.";
    static final String MSG_INVALID = "Liên kết xem video không hợp lệ.";
    static final String MSG_EXPIRED = "Liên kết xem video đã hết hạn. Vui lòng mở lại video từ danh sách ghi hình.";
    static final String MSG_UNAUTHORIZED_ROLE = "Vai trò người dùng không có thẩm quyền truy cập kho video an ninh.";
    static final String MSG_CROSS_USER_ACCESS_DENIED = "Liên kết xem video chỉ dành riêng cho người dùng được cấp thẩm quyền.";

    private final String serviceKey;
    private final SystemConfigService systemConfigService;
    private final Clock clock;

    public record TokenResponse(String token, long expiresAt, String userEmail, String userRole) {
        public TokenResponse(String token, long expiresAt) {
            this(token, expiresAt, null, null);
        }
    }

    public record TokenPayload(String version, long expiresAt, String userEmail, String userRole, String signature) {}

    @Autowired
    public RecordingStreamTokenService(
            @Value("${icss.internal.service-key:}") String serviceKey,
            SystemConfigService systemConfigService) {
        this(serviceKey, systemConfigService, Clock.systemUTC());
    }

    /** Đồng hồ thay được (test: token đã hết hạn, kiểm exp). */
    public RecordingStreamTokenService(String serviceKey, SystemConfigService systemConfigService, Clock clock) {
        this.serviceKey = serviceKey;
        this.systemConfigService = systemConfigService;
        this.clock = clock;
    }

    /**
     * Cấp token V2 cho đường dẫn và nguồn tệp chỉ định theo ngữ cảnh người dùng hiện tại.
     */
    public TokenResponse issue(String path, String source) {
        String currentUser = resolveCurrentAuthenticatedUser();
        String currentRole = resolveCurrentAuthenticatedRole();
        return issue(path, source, currentUser, currentRole);
    }

    /**
     * Cấp token V2 ràng buộc danh tính và thẩm quyền người dùng.
     */
    public TokenResponse issue(String path, String source, String userEmail, String userRole) {
        requireKey();
        String normSource = normalizeSource(source);
        String normUser = normalizeUser(userEmail);
        String normRole = normalizeRole(userRole);

        if (!ALLOWED_AUTHORITIES.contains(normRole)) {
            log.warn("🚨 [StreamToken] Từ chối cấp token cho user={}, role={} không thuộc danh sách có thẩm quyền", normUser, normRole);
            throw new AccessDeniedException(MSG_UNAUTHORIZED_ROLE);
        }

        long exp = Instant.now(clock).getEpochSecond() + tokenSeconds();
        String signature = signV2(path, normSource, normUser, normRole, exp);
        String encodedUser = Base64.getUrlEncoder().withoutPadding().encodeToString(normUser.getBytes(StandardCharsets.UTF_8));
        String token = String.format("%s.%d.%s.%s.%s", TOKEN_PREFIX_V2, exp, encodedUser, normRole, signature);

        log.info("🔐 [StreamToken] Đã cấp token video bảo mật V2: user={}, role={}, path={}, exp={}",
                normUser, normRole, path, exp);
        return new TokenResponse(token, exp, normUser, normRole);
    }

    /**
     * Kiểm tra token trước khi mở file (tự động lấy Authentication từ SecurityContext hiện tại).
     */
    public void verify(String path, String source, String token) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        verify(path, source, token, auth);
    }

    /**
     * Kiểm tra token toàn diện: chữ ký HMAC, hạn dùng, thẩm quyền vai trò và tính sở hữu của người dùng.
     */
    public void verify(String path, String source, String token, Authentication authentication) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException(MSG_MISSING);
        }
        if (serviceKey == null || serviceKey.isBlank() || path == null) {
            throw new UnauthorizedException(MSG_INVALID);
        }

        String normSource = normalizeSource(source);

        // Bắt buộc Token V2 có ràng buộc danh tính và thẩm quyền người dùng
        if (!token.startsWith(TOKEN_PREFIX_V2 + ".")) {
            throw new UnauthorizedException(MSG_INVALID);
        }
        verifyTokenV2(path, normSource, token, authentication);
    }

    private void verifyTokenV2(String path, String normSource, String token, Authentication authentication) {
        String[] parts = token.split("\\.");
        if (parts.length != 5) {
            throw new UnauthorizedException(MSG_INVALID);
        }

        long exp;
        try {
            exp = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new UnauthorizedException(MSG_INVALID);
        }

        String userEmail;
        try {
            byte[] decodedBytes = Base64.getUrlDecoder().decode(parts[2]);
            userEmail = new String(decodedBytes, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            throw new UnauthorizedException(MSG_INVALID);
        }

        String userRole = parts[3].toUpperCase(Locale.ROOT);
        String signature = parts[4];

        // 1. Kiểm tra thẩm quyền vai trò ghi trên token
        if (!ALLOWED_AUTHORITIES.contains(userRole)) {
            log.warn("🚨 [StreamToken] Token V2 chứa vai trò không có quyền: role={}, user={}", userRole, userEmail);
            throw new AccessDeniedException(MSG_UNAUTHORIZED_ROLE);
        }

        // 2. Kiểm tra chữ ký số HMAC-SHA256
        String expectedSig = signV2(path, normSource, userEmail, userRole, exp);
        byte[] expectedBytes = expectedSig.getBytes(StandardCharsets.UTF_8);
        byte[] givenBytes = signature.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, givenBytes)) {
            log.warn("🚨 [StreamToken] Chữ ký Token V2 không khớp cho file={}", path);
            throw new UnauthorizedException(MSG_INVALID);
        }

        // 3. Kiểm tra thời hạn hiệu lực
        if (Instant.now(clock).getEpochSecond() > exp) {
            log.warn("⏰ [StreamToken] Token V2 đã hết hạn: user={}, exp={}", userEmail, exp);
            throw new UnauthorizedException(MSG_EXPIRED);
        }

        // 4. Kiểm tra chéo người dùng gọi API (nếu người gọi đang có phiên đăng nhập)
        if (authentication != null && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getName())) {
            String callerEmail = authentication.getName().toLowerCase(Locale.ROOT);
            boolean isAdmin = hasAuthority(authentication, Role.ADMIN.name());
            boolean isAllowedRole = authentication.getAuthorities().stream()
                    .map(GrantedAuthority::getAuthority)
                    .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                    .anyMatch(ALLOWED_AUTHORITIES::contains);

            if (!isAllowedRole) {
                log.warn("🚨 [StreamToken] Người dùng {} không có quyền xem video an ninh", callerEmail);
                throw new AccessDeniedException(MSG_UNAUTHORIZED_ROLE);
            }

            // Nếu người đang xem không phải người xin cấp và cũng không phải ADMIN -> Chặn xem chéo
            if (!callerEmail.equals(userEmail) && !isAdmin) {
                log.warn("🚨 [StreamToken] Người dùng {} cố tình xem link video được cấp cho {}", callerEmail, userEmail);
                throw new AccessDeniedException(MSG_CROSS_USER_ACCESS_DENIED);
            }
        }
    }

    /**
     * Trích xuất thông tin payload từ token V2 phục vụ giám sát và kiểm tra.
     */
    public TokenPayload parseTokenPayload(String token) {
        if (token == null || token.isBlank() || !token.startsWith(TOKEN_PREFIX_V2 + ".")) {
            return null;
        }
        String[] parts = token.split("\\.");
        if (parts.length != 5) {
            return null;
        }
        try {
            long exp = Long.parseLong(parts[1]);
            String user = new String(Base64.getUrlDecoder().decode(parts[2]), StandardCharsets.UTF_8);
            return new TokenPayload(parts[0], exp, user, parts[3], parts[4]);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean hasAuthority(Authentication auth, String roleName) {
        if (auth == null || auth.getAuthorities() == null) return false;
        String expected = "ROLE_" + roleName;
        return auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equalsIgnoreCase(expected) || a.getAuthority().equalsIgnoreCase(roleName));
    }

    private String resolveCurrentAuthenticatedUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return "system";
    }

    private String resolveCurrentAuthenticatedRole() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getAuthorities() != null) {
            for (GrantedAuthority ga : auth.getAuthorities()) {
                String a = ga.getAuthority();
                String role = a.startsWith("ROLE_") ? a.substring(5) : a;
                if (ALLOWED_AUTHORITIES.contains(role)) {
                    return role;
                }
            }
        }
        return Role.GUARD.name();
    }

    /**
     * RECORDING_STREAM_TOKEN_SECONDS hiện hành. Giá trị thiếu / sai kiểu: SystemConfigService đã ghi WARN và trả mặc định;
     * giá trị &lt;= 0 (sửa thẳng DB lọt min_value) -> WARN + mặc định của ConfigKey.
     */
    long tokenSeconds() {
        int seconds = systemConfigService.getInt(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS);
        if (seconds <= 0) {
            int fallback = Integer.parseInt(ConfigKey.RECORDING_STREAM_TOKEN_SECONDS.getDefaultValue());
            log.warn("{}={} không hợp lệ, dùng mặc định {}", ConfigKey.RECORDING_STREAM_TOKEN_SECONDS.getKey(), seconds, fallback);
            return fallback;
        }
        return seconds;
    }

    private static String normalizeSource(String source) {
        return source == null || source.isBlank() ? DEFAULT_SOURCE : source.trim();
    }

    private static String normalizeUser(String userEmail) {
        return userEmail != null && !userEmail.isBlank() ? userEmail.trim().toLowerCase(Locale.ROOT) : "anonymous";
    }

    private static String normalizeRole(String userRole) {
        return userRole != null && !userRole.isBlank() ? userRole.trim().toUpperCase(Locale.ROOT) : Role.GUARD.name();
    }

    private void requireKey() {
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException("Chưa cấu hình khoá ký liên kết xem video (INTERNAL_SERVICE_KEY).");
        }
    }

    private String signV2(String path, String source, String userEmail, String userRole, long exp) {
        try {
            String message = "REC_V2|" + userEmail + "|" + userRole + "|" + source + "|" + path + "|" + exp;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(serviceKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Lỗi sinh token xem video V2", e);
        }
    }
}
