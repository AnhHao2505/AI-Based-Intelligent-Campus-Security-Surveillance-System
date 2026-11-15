package com.fa26se040.icss.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Component
public class AesEncryptionUtil {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int TAG_LENGTH_BIT = 128;
    private static final int IV_LENGTH_BYTE = 12;
    private static final String PREFIX = "ENC_GCM:";

    /**
     * DEF-13: độ dài tối thiểu của khoá camera. Khoá AES-256 suy ra bằng SHA-256 của chuỗi, nên độ mạnh thực tế
     * chỉ bằng độ ngẫu nhiên của chuỗi: chuỗi ngắn thì đoán/vét được. 32 ký tự cùng ngưỡng với INTERNAL_SERVICE_KEY và
     * APP_SECURITY_AUDITHASHKEY; sinh khoá mới bằng `openssl rand -hex 32` (64 ký tự hex).
     */
    static final int MIN_SECRET_LENGTH = 32;

    static final String INVALID_SECRET_MESSAGE = "app.security.camera-secret (env APP_SECURITY_CAMERA_SECRET) is missing"
            + " or shorter than " + MIN_SECRET_LENGTH + " characters. Set it in .env; it must stay the same value that"
            + " encrypted the stored camera credentials.";

    private final SecretKey secretKey;

    /**
     * DEF-13: khoá chỉ đọc từ cấu hình (env APP_SECURITY_CAMERA_SECRET), không có giá trị mặc định trong code.
     * Thiếu hoặc ngắn hơn {@link #MIN_SECRET_LENGTH} ký tự thì dừng khởi động; thông báo không in giá trị khoá.
     */
    public AesEncryptionUtil(@Value("${app.security.camera-secret}") String secret) {
        if (secret == null || secret.isBlank() || secret.length() < MIN_SECRET_LENGTH) {
            throw new IllegalStateException(INVALID_SECRET_MESSAGE);
        }
        this.secretKey = deriveKey(secret);
    }

    private SecretKey deriveKey(String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] key = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize AES key", e);
        }
    }

    /**
     * Mã hóa chuỗi văn bản thuần bằng AES-256-GCM.
     * Trả về chuỗi dạng "ENC_GCM:<Base64(IV + CipherText)>"
     */
    public String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            return plainText;
        }
        if (plainText.startsWith(PREFIX)) {
            return plainText; // Already encrypted
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTE];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherText);

            return PREFIX + Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("Lỗi khi mã hóa dữ liệu camera: {}", e.getMessage());
            throw new RuntimeException("Mã hóa dữ liệu thất bại", e);
        }
    }

    /**
     * Giải mã chuỗi đã mã hóa.
     * Nếu chuỗi không có tiền tố ENC_GCM: (dữ liệu cũ dạng plain-text), trả về nguyên bản.
     */
    public String decrypt(String cipherTextWithPrefix) {
        if (cipherTextWithPrefix == null || cipherTextWithPrefix.isBlank()) {
            return cipherTextWithPrefix;
        }
        if (!cipherTextWithPrefix.startsWith(PREFIX)) {
            return cipherTextWithPrefix; // Legacy plain-text fallback
        }
        try {
            String base64Payload = cipherTextWithPrefix.substring(PREFIX.length());
            byte[] decoded = Base64.getDecoder().decode(base64Payload);

            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[IV_LENGTH_BYTE];
            byteBuffer.get(iv);

            byte[] cipherText = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] plainTextBytes = cipher.doFinal(cipherText);
            return new String(plainTextBytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("Lỗi khi giải mã dữ liệu camera: {}", e.getMessage());
            throw new RuntimeException("Giải mã dữ liệu thất bại", e);
        }
    }

    public boolean isEncrypted(String text) {
        return text != null && text.startsWith(PREFIX);
    }
}
