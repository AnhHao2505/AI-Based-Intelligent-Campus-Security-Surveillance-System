package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S3 (20/10): danh sách origin CORS đọc từ app.cors.allowed-origins (mặc định 5173 / 5174 / 3000 như trước).
 */
class CorsAllowedOriginsIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("S3: preflight từ origin trong danh sách mặc định -> được phép")
    void defaultOriginAllowed() throws Exception {
        for (String origin : new String[]{"http://localhost:5173", "http://localhost:5174", "http://localhost:3000"}) {
            mockMvc.perform(options("/api/auth/login")
                            .header("Origin", origin)
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", origin));
        }
    }

    @Test
    @DisplayName("S3: preflight từ origin lạ -> 403")
    void unknownOriginRejected() throws Exception {
        mockMvc.perform(options("/api/auth/login")
                        .header("Origin", "http://evil.example.com")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }
}
