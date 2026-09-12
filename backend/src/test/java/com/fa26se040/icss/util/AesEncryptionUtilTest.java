package com.fa26se040.icss.util;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AesEncryptionUtilTest {

    private AesEncryptionUtil aesEncryptionUtil;

    @BeforeEach
    void setUp() {
        aesEncryptionUtil = new AesEncryptionUtil("test-master-secret-key-for-camera-passwords-2026");
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
