package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Header X-Service-Key cho endpoint nội bộ AI → backend (InternalServiceKeyFilter), chạy qua SecurityFilterChain thật.
 * Camera không tồn tại để check-entry trả 200 mà không ghi nhật ký (không cần fixture).
 */
class InternalServiceKeyIntegrationTest extends AbstractIntegrationTest {

    private static final String CHECK_ENTRY = "/api/access-control/check-entry";
    private static final String RUNTIME_CONFIG = "/api/internal/ai/runtime-config";

    @Autowired private MockMvc mockMvc;
    @Value("${icss.internal.service-key}") private String serviceKey;

    private static MockHttpServletRequestBuilder checkEntry() {
        return post(CHECK_ENTRY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"cameraCode\":\"K-NO-SUCH-CAMERA\",\"userCode\":\"K-NO-SUCH-USER\"}");
    }

    @Test
    @DisplayName("K-01: Không có header X-Service-Key → 401, body ApiResponse (check-entry và runtime-config)")
    void k01_missingHeader_Unauthorized() throws Exception {
        mockMvc.perform(checkEntry())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.httpCode").value(401))
                .andExpect(jsonPath("$.errorCode").value("Unauthorized"))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(get(RUNTIME_CONFIG))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.httpCode").value(401))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("K-02: Sai khoá (cùng độ dài và khác độ dài) → 401")
    void k02_wrongKey_Unauthorized() throws Exception {
        String sameLengthWrongKey = "x".repeat(serviceKey.length());

        mockMvc.perform(checkEntry().header(InternalServiceKeyFilter.HEADER, sameLengthWrongKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.httpCode").value(401));
        mockMvc.perform(checkEntry().header(InternalServiceKeyFilter.HEADER, serviceKey + "x"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(RUNTIME_CONFIG).header(InternalServiceKeyFilter.HEADER, sameLengthWrongKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.httpCode").value(401));
    }

    @Test
    @DisplayName("K-03: Đúng khoá → 200 (check-entry và runtime-config)")
    void k03_correctKey_Ok() throws Exception {
        mockMvc.perform(checkEntry().header(InternalServiceKeyFilter.HEADER, serviceKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allowed").value(false))
                .andExpect(jsonPath("$.data.reason").value("Camera không tồn tại"));

        mockMvc.perform(get(RUNTIME_CONFIG).header(InternalServiceKeyFilter.HEADER, serviceKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.after_hour_start").exists())
                .andExpect(jsonPath("$.data.after_hour_end").exists());
    }

    @Test
    @DisplayName("K-04: Endpoint JWT (GET /api/access-history/my) không token → 401 của tầng JWT như cũ; có khoá dịch vụ vẫn 401")
    void k04_jwtEndpoint_NotAffectedByServiceKey() throws Exception {
        mockMvc.perform(get("/api/access-history/my"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"))
                .andExpect(jsonPath("$.httpCode").doesNotExist());

        // Khoá dịch vụ không thay thế JWT ở endpoint ngoài phạm vi filter
        mockMvc.perform(get("/api/access-history/my").header(InternalServiceKeyFilter.HEADER, serviceKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        // Path cũ của runtime-config không còn public
        mockMvc.perform(get("/api/access-control/ai-runtime-config").header(InternalServiceKeyFilter.HEADER, serviceKey))
                .andExpect(status().isUnauthorized());
    }
}
