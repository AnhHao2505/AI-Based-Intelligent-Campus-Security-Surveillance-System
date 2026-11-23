package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.guest.GuestTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Việc 7: mục đích đơn truy cập kiểm theo REASON_MIN_LENGTH / REASON_MAX_LENGTH qua HTTP thật (@Valid trên controller).
 * ADMIN sửa REASON_MAX_LENGTH qua API là có hiệu lực ngay cho mục đích, không restart. Chỉ xét lỗi của trường
 * {@code purpose}; khu vực / khung giờ không cần hợp lệ vì @Valid chạy trước nghiệp vụ.
 */
class AccessRequestPurposeLengthIntegrationTest extends GuestTestSupport {

    @BeforeEach
    @AfterEach
    void resetReasonLimits() {
        setConfig(ConfigKey.REASON_MAX_LENGTH, ConfigKey.REASON_MAX_LENGTH.getDefaultValue());
        setConfig(ConfigKey.REASON_MIN_LENGTH, ConfigKey.REASON_MIN_LENGTH.getDefaultValue());
    }

    private Map<String, Object> body(String purpose, boolean group) {
        OffsetDateTime start = OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.HOURS);
        Map<String, Object> b = new HashMap<>();
        b.put("areaId", internalArea.getId());
        b.put("startTime", start.toString());
        b.put("endTime", start.plusHours(2).toString());
        b.put("purpose", purpose);
        if (group) {
            b.put("memberUserCodes", List.of(otherHost.getUserCode()));
        }
        return b;
    }

    private MvcResult create(User actor, String purpose, boolean group) throws Exception {
        return send(post(group ? "/api/access-requests/group" : "/api/access-requests/individual"), actor,
                body(purpose, group)).andReturn();
    }

    /**
     * Lỗi validate của trường purpose (null nếu trường này không lỗi). Chỉ đọc khi response là lỗi validate: tạo đơn
     * thành công thì data.purpose là chính mục đích của đơn, không phải câu báo lỗi.
     */
    private String purposeError(MvcResult r) throws Exception {
        if (!"VALIDATION_ERROR".equals(errorCode(r))) {
            return null;
        }
        JsonNode node = json(r).path("data").path("purpose");
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    @Test
    @DisplayName("PLI-01: đơn cá nhân và nhóm — mục đích 9 ký tự và 501 ký tự -> 400, câu báo theo cấu hình 10 / 500")
    void tooShortAndTooLong_rejected() throws Exception {
        for (boolean group : List.of(false, true)) {
            for (String purpose : List.of("a".repeat(9), "a".repeat(501))) {
                MvcResult r = create(hostL2, purpose, group);
                assertEquals(400, status(r), r.getResponse().getContentAsString());
                assertEquals("Mục đích phải có từ 10 đến 500 ký tự", purposeError(r), "group=" + group);
            }
        }
    }

    @Test
    @DisplayName("PLI-02: ADMIN đổi REASON_MAX_LENGTH 500 -> 50 qua API -> mục đích 60 ký tự bị 400 ngay (cá nhân và nhóm)")
    void maxLengthChange_appliesImmediately() throws Exception {
        String purpose60 = "Mục đích sáu mươi ký tự ".repeat(3).substring(0, 60);
        assertEquals(60, purpose60.length());
        for (boolean group : List.of(false, true)) {
            assertEquals(null, purposeError(create(hostL2, purpose60, group)), "trước khi đổi cấu hình, group=" + group);
        }

        MvcResult change = send(patch("/api/system-configurations/{key}", ConfigKey.REASON_MAX_LENGTH.getKey()), admin,
                Map.of("configValue", "50", "reason", "Giảm độ dài tối đa của lý do")).andReturn();
        assertEquals(200, status(change), change.getResponse().getContentAsString());

        for (boolean group : List.of(false, true)) {
            MvcResult r = create(hostL2, purpose60, group);
            assertEquals(400, status(r), r.getResponse().getContentAsString());
            assertEquals("Mục đích phải có từ 10 đến 50 ký tự", purposeError(r), "group=" + group);
        }
        assertFalse(purposeError(create(hostL2, "m".repeat(50), false)) != null, "50 ký tự vẫn hợp lệ");
    }
}
