package com.fa26se040.icss.service;

import com.fa26se040.icss.exception.UnauthorizedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * BE-03: token ngắn hạn cho GET /api/cameras/recordings/stream, cùng cơ chế với {@link AnnotatedStreamTokenService}.
 * Thẻ &lt;video src&gt; và cửa sổ tải file không gửi được header Authorization, nên trình duyệt xin token
 * (POST /api/cameras/recordings/stream-token, cần JWT ADMIN / GUARD) rồi gắn vào query {@code token}.
 * <p>
 * Token định dạng {exp}.{hexdigest}, hexdigest = HMAC-SHA256(key=INTERNAL_SERVICE_KEY,
 * msg="REC|" + source + "|" + path + "|" + exp). Token chỉ mở đúng một file (path + source), hết hạn sau
 * {@code icss.camera.recording-stream-token-seconds} giây.
 */
@Service
public class RecordingStreamTokenService {

    public static final String DEFAULT_SOURCE = "recording";

    static final String MSG_MISSING = "Chưa có mã xem video. Vui lòng mở lại video từ danh sách ghi hình.";
    static final String MSG_INVALID = "Liên kết xem video không hợp lệ.";
    static final String MSG_EXPIRED = "Liên kết xem video đã hết hạn. Vui lòng mở lại video từ danh sách ghi hình.";

    private final String serviceKey;
    private final long tokenSeconds;
    private final Clock clock;

    public record TokenResponse(String token, long expiresAt) {}

    @Autowired
    public RecordingStreamTokenService(
            @Value("${icss.internal.service-key:}") String serviceKey,
            @Value("${icss.camera.recording-stream-token-seconds:300}") long tokenSeconds) {
        this(serviceKey, tokenSeconds, Clock.systemUTC());
    }

    RecordingStreamTokenService(String serviceKey, long tokenSeconds, Clock clock) {
        this.serviceKey = serviceKey;
        this.tokenSeconds = tokenSeconds;
        this.clock = clock;
    }

    public TokenResponse issue(String path, String source) {
        requireKey();
        long exp = Instant.now(clock).getEpochSecond() + tokenSeconds;
        return new TokenResponse(exp + "." + sign(path, normalizeSource(source), exp), exp);
    }

    /**
     * Kiểm token trước khi mở file. Thiếu / sai / hết hạn → {@link UnauthorizedException} (401).
     */
    public void verify(String path, String source, String token) {
        if (token == null || token.isBlank()) {
            throw new UnauthorizedException(MSG_MISSING);
        }
        if (serviceKey == null || serviceKey.isBlank() || path == null) {
            throw new UnauthorizedException(MSG_INVALID);
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new UnauthorizedException(MSG_INVALID);
        }
        long exp;
        try {
            exp = Long.parseLong(token.substring(0, dot));
        } catch (NumberFormatException e) {
            throw new UnauthorizedException(MSG_INVALID);
        }
        String expected = sign(path, normalizeSource(source), exp);
        byte[] given = token.substring(dot + 1).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given)) {
            throw new UnauthorizedException(MSG_INVALID);
        }
        if (Instant.now(clock).getEpochSecond() > exp) {
            throw new UnauthorizedException(MSG_EXPIRED);
        }
    }

    private static String normalizeSource(String source) {
        return source == null || source.isBlank() ? DEFAULT_SOURCE : source;
    }

    private void requireKey() {
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException("Chưa cấu hình khoá ký liên kết xem video (INTERNAL_SERVICE_KEY).");
        }
    }

    private String sign(String path, String source, long exp) {
        try {
            String message = "REC|" + source + "|" + path + "|" + exp;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(serviceKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Lỗi sinh token xem video", e);
        }
    }
}
