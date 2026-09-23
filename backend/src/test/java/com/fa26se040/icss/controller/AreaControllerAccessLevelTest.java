package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.area.AreaAccessRulesUpdateRequest;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.exception.AreaException;
import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.service.AreaService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AreaControllerAccessLevelTest {

    @Mock
    private AreaService areaService;

    @InjectMocks
    private AreaController areaController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(areaController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("PATCH /api/areas/{id}/access-rules với level ngoài khoảng 1..3 -> 400 Bad Request")
    void updateAccessRules_InvalidLevel_Returns400() throws Exception {
        UUID areaId = UUID.randomUUID();

        // Level = 0
        String body0 = objectMapper.writeValueAsString(new AreaAccessRulesUpdateRequest(0, true, "Lý do"));
        mockMvc.perform(patch("/api/areas/{id}/access-rules", areaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body0))
                .andExpect(status().isBadRequest());

        // Level = 4
        String body4 = objectMapper.writeValueAsString(new AreaAccessRulesUpdateRequest(4, false, "Lý do"));
        mockMvc.perform(patch("/api/areas/{id}/access-rules", areaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body4))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /api/areas/{id}/access-rules thiếu explicitAuthorizationRequired -> 400 Bad Request")
    void updateAccessRules_MissingFlag_Returns400() throws Exception {
        UUID areaId = UUID.randomUUID();
        String body = "{\"areaAccessLevel\": 2, \"reason\": \"Lý do\"}";

        mockMvc.perform(patch("/api/areas/{id}/access-rules", areaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /api/areas/{id}/access-rules cho area inactive/đã xoá -> 400 Bad Request")
    void updateAccessRules_InactiveArea_Returns400() throws Exception {
        UUID areaId = UUID.randomUUID();
        when(areaService.updateAccessRules(eq(areaId), any(), any()))
                .thenThrow(new AreaException(AreaErrorCode.ERR_AREA_017));

        String body = objectMapper.writeValueAsString(new AreaAccessRulesUpdateRequest(2, false, "Lý do"));
        mockMvc.perform(patch("/api/areas/{id}/access-rules", areaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_AREA_017"));
    }

    @Test
    @DisplayName("PATCH /api/areas/{id}/access-rules hợp lệ -> 200 OK")
    void updateAccessRules_Valid_Returns200() throws Exception {
        UUID areaId = UUID.randomUUID();
        AreaResponse resp = new AreaResponse(
                areaId,
                "LAB-01",
                "Phòng Lab",
                AreaLevel.HIGHLY_CONFIDENTIAL,
                2,
                false,
                "Tòa A",
                "Tầng 1",
                "Mô tả",
                null,
                true,
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );

        when(areaService.updateAccessRules(eq(areaId), any(), any())).thenReturn(resp);

        String body = objectMapper.writeValueAsString(new AreaAccessRulesUpdateRequest(2, false, "Lý do cập nhật"));
        mockMvc.perform(patch("/api/areas/{id}/access-rules", areaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaAccessLevel").value(2))
                .andExpect(jsonPath("$.explicitAuthorizationRequired").value(false));
    }
}
