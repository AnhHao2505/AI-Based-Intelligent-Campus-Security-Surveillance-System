package com.fa26se040.icss.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AesEncryptionUtilTest {

    private AesEncryptionUtil aesEncryptionUtil;

    @BeforeEach
    void setUp() {
        aesEncryptionUtil = new AesEncryptionUtil(TEST_ONLY_SECRET);
    }

    /** Khoá giả chỉ dùng trong test (>= 32 ký tự), không phải khoá thật. */
    private static final String TEST_ONLY_SECRET = "test-only-camera-secret-0123456789abcdef";

    private static final String EXPECTED_MESSAGE = "app.security.camera-secret (env APP_SECURITY_CAMERA_SECRET) is missing"
            + " or shorter than 32 characters. Set it in .env; it must stay the same value that"
            + " encrypted the stored camera credentials.";

    @Test
    @DisplayName("DEF-13: missing secret (null / empty / blank) fails at startup with the fixed message")
    void missingSecretFailsFast() {
        for (String missing : new String[] {null, "", "   "}) {
            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new AesEncryptionUtil(missing));
            assertEquals(EXPECTED_MESSAGE, ex.getMessage());
        }
    }

    @Test
    @DisplayName("DEF-13: secret shorter than 32 characters fails at startup and the message does not echo it")
    void shortSecretFailsFast() {
        String shortSecret = "test-only-short-secret-31-chars"; // 31 ký tự
        assertEquals(AesEncryptionUtil.MIN_SECRET_LENGTH - 1, shortSecret.length());

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> new AesEncryptionUtil(shortSecret));
        assertEquals(EXPECTED_MESSAGE, ex.getMessage());
        assertFalse(ex.getMessage().contains(shortSecret));
    }

    @Test
    @DisplayName("DEF-13: secret of exactly 32 characters is accepted and round-trips")
    void minimumLengthSecretRoundTrips() {
        String secret = "test-only-camera-secret-32-chars";
        assertEquals(AesEncryptionUtil.MIN_SECRET_LENGTH, secret.length());

        AesEncryptionUtil util = new AesEncryptionUtil(secret);
        String encrypted = util.encrypt("cam-pass-01");
        assertTrue(encrypted.startsWith("ENC_GCM:"));
        assertEquals("cam-pass-01", util.decrypt(encrypted));
    }

    @Test
    @DisplayName("DEF-13: data encrypted with one secret cannot be decrypted with another")
    void differentSecretCannotDecrypt() {
        String encrypted = aesEncryptionUtil.encrypt("cam-pass-02");
        AesEncryptionUtil other = new AesEncryptionUtil("test-only-other-camera-secret-0123456789");

        assertThrows(RuntimeException.class, () -> other.decrypt(encrypted));
    }

    @Test
    @DisplayName("Should encrypt plaintext with ENC_GCM: prefix and decrypt correctly")
    void testEncryptAndDecrypt() {
        String plain = "MySecretCameraPassword@123";
        String encrypted = aesEncryptionUtil.encrypt(plain);

        assertNotNull(encrypted);
        assertTrue(encrypted.startsWith("ENC_GCM:"));
        assertNotEquals(plain, encrypted);

        String decrypted = aesEncryptionUtil.decrypt(encrypted);
        assertEquals(plain, decrypted);
    }

    @Test
    @DisplayName("Should use random IV for same plaintext (different ciphertexts)")
    void testRandomIv() {
        String plain = "SamePasswordTwice";
        String enc1 = aesEncryptionUtil.encrypt(plain);
        String enc2 = aesEncryptionUtil.encrypt(plain);

        assertNotEquals(enc1, enc2);
        assertEquals(plain, aesEncryptionUtil.decrypt(enc1));
        assertEquals(plain, aesEncryptionUtil.decrypt(enc2));
    }

    @Test
    @DisplayName("Should return legacy plaintext as-is without error")
    void testLegacyPlainTextFallback() {
        String legacy = "plain_camera_pwd";
        String decrypted = aesEncryptionUtil.decrypt(legacy);

        assertEquals(legacy, decrypted);
    }

    @Test
    @DisplayName("Should handle null or blank input gracefully")
    void testNullOrBlank() {
        assertNull(aesEncryptionUtil.encrypt(null));
        assertEquals("", aesEncryptionUtil.encrypt(""));
        assertEquals("   ", aesEncryptionUtil.encrypt("   "));

        assertNull(aesEncryptionUtil.decrypt(null));
        assertEquals("", aesEncryptionUtil.decrypt(""));
        assertEquals("   ", aesEncryptionUtil.decrypt("   "));
    }
}
