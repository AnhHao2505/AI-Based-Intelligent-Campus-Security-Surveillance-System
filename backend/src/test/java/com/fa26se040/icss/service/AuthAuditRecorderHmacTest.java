package com.fa26se040.icss.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * H1: mã băm chuỗi đăng nhập sai không khớp tài khoản dùng HMAC-SHA256 có khoá (app.security.audit-hash-key),
 * không dùng SHA-256 trơn (băm thử danh sách e-mail là dò ngược được). Khoá thiếu / ngắn -> không khởi tạo được bean.
 */
class AuthAuditRecorderHmacTest {

    private static final byte[] KEY_A = "unit-test-audit-hash-key-AAAAAAAAAAAAAAAA".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_B = "unit-test-audit-hash-key-BBBBBBBBBBBBBBBB".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("Cùng chuỗi + cùng khoá -> cùng hash (16 hex); khoá khác -> hash khác; khác SHA-256 trơn")
    void sameInputSameKey_sameHash_differentKey_differentHash() throws Exception {
        String a1 = AuthAuditRecorder.hmacPrefix("abc@x.vn", KEY_A);
        String a2 = AuthAuditRecorder.hmacPrefix("abc@x.vn", KEY_A);
        String b = AuthAuditRecorder.hmacPrefix("abc@x.vn", KEY_B);

        assertThat(a1).hasSize(16).matches("[0-9a-f]{16}").isEqualTo(a2);
        assertThat(b).isNotEqualTo(a1);
        String plainSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("abc@x.vn".getBytes(StandardCharsets.UTF_8))).substring(0, 16);
        assertThat(a1).isNotEqualTo(plainSha);
    }

    @Test
    @DisplayName("identify: chuỗi không khớp -> target_id UNKNOWN:<hmac>; chuẩn hoá trim + lower trước khi băm")
    void identify_usesKeyedHashOfNormalizedInput() {
        AuthAuditRecorder.Identity id = AuthAuditRecorder.identify(null, "  ABC@X.VN ", KEY_A);
        String expected = AuthAuditRecorder.hmacPrefix("abc@x.vn", KEY_A);
        assertThat(id.targetId()).isEqualTo("UNKNOWN:" + expected);
        assertThat(id.hash()).isEqualTo(expected);
        assertThat(id.email()).isEqualTo("a***@x.vn");
        assertThat(AuthAuditRecorder.identify(null, "abc@x.vn", KEY_B).hash()).isNotEqualTo(expected);
    }

    @Test
    @DisplayName("Thiếu khoá / khoá ngắn hơn 32 ký tự -> IllegalStateException nêu tên cấu hình, không lộ giá trị khoá")
    void missingOrShortKey_failsFast() {
        AuditService auditService = mock(AuditService.class);
        for (String bad : new String[]{null, "", "   ", "short-key-123"}) {
            assertThatThrownBy(() -> new AuthAuditRecorder(auditService, bad))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("app.security.audit-hash-key")
                    .hasMessageContaining("APP_SECURITY_AUDITHASHKEY");
        }
        assertThatThrownBy(() -> new AuthAuditRecorder(auditService, "short-key-123"))
                .message().doesNotContain("short-key-123");
        new AuthAuditRecorder(auditService, "a-long-enough-audit-hash-key-0123456789");
    }
}
