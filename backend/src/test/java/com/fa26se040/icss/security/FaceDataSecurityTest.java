package com.fa26se040.icss.security;

import com.fa26se040.icss.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class FaceDataSecurityTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("T2: /api/v1/face-data không có token phải trả về 401 Unauthorized")
    void unauthenticatedRequestToFaceData_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/face-data"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/face-data/test"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/face-data/upload"))
                .andExpect(status().isUnauthorized());
    }
}
