package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.area.AreaAccessRulesUpdateRequest;
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

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
/**
 * V83 (D4): endpoint access-rules giữ lại nhưng không còn sửa cấp (service luôn ném ERR_AREA_056). Kiểm DTO vẫn chạy trước.
 * Đã bỏ 3 test của chức năng cũ (thiếu cờ chỉ định -> 400, khu inactive -> ERR_AREA_017, sửa hợp lệ -> 200), thay bằng 1 test.
 */
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
    @DisplayName("BR-AL-03: PATCH /api/areas/{id}/access-rules với reason null, rỗng, khoảng trắng, 501 ký tự -> 400 Bad Request và service không được gọi")
    void updateAccessRules_ReasonValidation_Returns400_AndServiceNeverCalled() throws Exception {
        UUID areaId = UUID.randomUUID();

        // 1. reason = null
        String bodyNull = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": null}";
        mockMvc.perform(patch("/api/areas/{id}/access-rules", areaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyNull))
                .andExpect(status().isBadRequest());

        // 2. reason = ""
        String bodyEmpty = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": \"\"}";
        mockMvc.perform(patch("/api/areas/{id}/access-rules", areaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyEmpty))
                .andExpect(status().isBadRequest());

        // 3. reason = "   "
        String bodySpaces = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": \"   \"}";
        mockMvc.perform(patch("/api/areas/{id}/access-rules", areaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySpaces))
                .andExpect(status().isBadRequest());

        // 4. reason = 501 ký tự
        String body501 = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": \"" + "a".repeat(501) + "\"}";
        mockMvc.perform(patch("/api/areas/{id}/access-rules", areaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body501))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verify(areaService, org.mockito.Mockito.never())
                .updateAccessRules(any(), any(), any());
    }

    @Test
    @DisplayName("V83 (D4): PATCH /api/areas/{id}/access-rules hợp lệ về dữ liệu -> 400 ERR_AREA_056 \"Cấp khu vực cố định theo loại\"")
    void updateAccessRules_FixedByType_Returns400() throws Exception {
        UUID areaId = UUID.randomUUID();
        when(areaService.updateAccessRules(eq(areaId), any(), any()))
                .thenThrow(new AreaException(AreaErrorCode.ERR_AREA_056));

        String body = objectMapper.writeValueAsString(new AreaAccessRulesUpdateRequest(2, false, "Lý do cập nhật cấp khu vực"));
        mockMvc.perform(patch("/api/areas/{id}/access-rules", areaId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ERR_AREA_056"))
                .andExpect(jsonPath("$.message").value(
                        "Cấp khu vực cố định theo loại (Công khai: cấp 1, Bảo mật: cấp 2, Tuyệt mật: cấp 3). Muốn đổi cấp, hãy đổi loại khu vực"));
    }
}
