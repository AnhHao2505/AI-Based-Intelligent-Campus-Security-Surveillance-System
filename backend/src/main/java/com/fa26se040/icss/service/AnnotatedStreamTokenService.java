package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Service cấp token xác thực ngắn hạn cho luồng live-annotated từ AI Service.
 * Token định dạng: {exp}.{hexdigest}
 * hexdigest = HMAC-SHA256(key=INTERNAL_SERVICE_KEY, msg=f"{cameraCode.toUpperCase()}|{exp}")
 * Hết hạn sau ANNOTATED_STREAM_TOKEN_SECONDS giây (V121, cấu hình hệ thống, đọc lại mỗi lần cấp). AI service chỉ so
 * exp trong token với giờ hiện tại (ai-service/src/security.py verify_stream_token), không có thời hạn riêng.
 */
@Slf4j
@Service
public class AnnotatedStreamTokenService {

    private final CameraRepository cameraRepository;
    private final String serviceKey;
    private final SystemConfigService systemConfigService;
    private final Clock clock;

    public record TokenResponse(String token, long expiresAt) {}

    @Autowired
    public AnnotatedStreamTokenService(
            CameraRepository cameraRepository,
            @Value("${icss.internal.service-key:}") String serviceKey,
            SystemConfigService systemConfigService) {
        this(cameraRepository, serviceKey, systemConfigService, Clock.systemUTC());
    }

    /** Đồng hồ thay được (test kiểm exp). */
    public AnnotatedStreamTokenService(CameraRepository cameraRepository, String serviceKey,
                                       SystemConfigService systemConfigService, Clock clock) {
        this.cameraRepository = cameraRepository;
        this.serviceKey = serviceKey;
        this.systemConfigService = systemConfigService;
        this.clock = clock;
    }

    /**
     * Cấp token cho camera theo mã: camera phải tồn tại (chưa xoá) và không ở trạng thái DECOMMISSIONED.
     * Token ký theo mã camera lưu trong DB, không theo chuỗi client gửi lên.
     */
    public TokenResponse issueForCamera(String cameraCode) {
        Camera camera = cameraRepository.findByCameraCodeAndDeletedAtIsNull(cameraCode)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));
        if (camera.getStatus() == CameraStatus.DECOMMISSIONED) {
            throw new CameraException(CameraErrorCode.ERR_STREAM_003);
        }
        return issue(camera.getCameraCode());
    }

    public TokenResponse issue(String cameraCode) {
        long exp = Instant.now(clock).getEpochSecond() + tokenSeconds();
        String token = generateToken(cameraCode, exp, this.serviceKey);
        return new TokenResponse(token, exp);
    }

    /**
     * ANNOTATED_STREAM_TOKEN_SECONDS hiện hành. Giá trị thiếu / sai kiểu: SystemConfigService đã ghi WARN và trả mặc định;
     * giá trị &lt;= 0 (sửa thẳng DB lọt min_value) -> WARN + mặc định của ConfigKey.
     */
    long tokenSeconds() {
        int seconds = systemConfigService.getInt(ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS);
        if (seconds <= 0) {
            int fallback = Integer.parseInt(ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS.getDefaultValue());
            log.warn("{}={} không hợp lệ, dùng mặc định {}", ConfigKey.ANNOTATED_STREAM_TOKEN_SECONDS.getKey(), seconds, fallback);
            return fallback;
        }
        return seconds;
    }

    public static String generateToken(String cameraCode, long exp, String key) {
        if (cameraCode == null || key == null) {
            throw new IllegalArgumentException("cameraCode và key không được để null");
        }
        try {
            String message = cameraCode.toUpperCase() + "|" + exp;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] hmacBytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(hmacBytes);
            return exp + "." + hex;
        } catch (Exception e) {
            throw new RuntimeException("Lỗi sinh stream token", e);
        }
    }
}
