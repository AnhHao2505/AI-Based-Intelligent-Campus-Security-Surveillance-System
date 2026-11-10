package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.ai.AiFaceItemDto;
import com.fa26se040.icss.dto.ai.AiSyncDataDto;
import com.fa26se040.icss.service.AiSyncService;
import com.fa26se040.icss.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.*;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalAiControllerUnitTest {

    @Mock
    private SystemConfigService systemConfigService;

    @Mock
    private AiSyncService aiSyncService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        InternalAiController controller = new InternalAiController(systemConfigService, aiSyncService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("GET /api/internal/ai/sync-data - Trả về dữ liệu đồng bộ thành công")
    void getSyncData_Success() throws Exception {
        AiFaceItemDto face = AiFaceItemDto.builder()
                .code("SV001")
                .fullName("Nguyen Van A")
                .embedding(List.of(0.1f, 0.2f))
                .isActive(true)
                .role("NORMAL_USER")
                .expiresAt(null)
                .build();

        AiSyncDataDto syncData = AiSyncDataDto.builder()
                .faces(List.of(face))
                .cameraAreaMap(Map.of("CAM-001", "Cổng chính"))
                .publicCameraCodes(Set.of("CAM-001"))
                .timestamp(System.currentTimeMillis())
                .build();

        when(aiSyncService.getAiSyncData()).thenReturn(syncData);

        mockMvc.perform(get("/api/internal/ai/sync-data"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpCode").value(200))
                .andExpect(jsonPath("$.data.faces[0].code").value("SV001"))
                .andExpect(jsonPath("$.data.faces[0].fullName").value("Nguyen Van A"))
                .andExpect(jsonPath("$.data.faces[0].role").value("NORMAL_USER"))
                .andExpect(jsonPath("$.data.cameraAreaMap.CAM-001").value("Cổng chính"))
                .andExpect(jsonPath("$.data.publicCameraCodes[0]").value("CAM-001"));
    }

    @Test
    @DisplayName("GET /api/internal/ai/runtime-config - Trả về cấu hình runtime thành công")
    void getRuntimeConfig_Success() throws Exception {
        when(systemConfigService.getAiRuntimeConfigMap()).thenReturn(Map.of("after_hour_start", "22:00"));

        mockMvc.perform(get("/api/internal/ai/runtime-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpCode").value(200))
                .andExpect(jsonPath("$.data.after_hour_start").value("22:00"));
    }
}
