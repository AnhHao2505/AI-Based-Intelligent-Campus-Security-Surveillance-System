package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.accesscontrol.AccessControlAuditLogResponse;
import com.fa26se040.icss.dto.accesscontrol.LevelPresetResponse;
import com.fa26se040.icss.dto.accesscontrol.LevelPresetUpdateRequest;
import com.fa26se040.icss.enums.AccessControlTargetType;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.exception.AccessControlErrorCode;
import com.fa26se040.icss.exception.AccessControlException;
import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.service.AccessControlAuditService;
import com.fa26se040.icss.service.AreaLevelPresetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AccessControlControllerTest {

    @Mock
    private AreaLevelPresetService presetService;

    @Mock
    private AccessControlAuditService auditService;

    @InjectMocks
    private AccessControlController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new org.springframework.data.web.PageableHandlerMethodArgumentResolver())
                .build();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("GET /api/access-control/level-presets -> 200 OK với danh sách presets")
    void getLevelPresets_Returns200() throws Exception {
        LevelPresetResponse p1 = new LevelPresetResponse(AreaLevel.PUBLIC, 1, false, OffsetDateTime.now(), "Admin", "ADM01", 0L);
        LevelPresetResponse p2 = new LevelPresetResponse(AreaLevel.HIGHLY_CONFIDENTIAL, 3, true, OffsetDateTime.now(), "Admin", "ADM01", 0L);

        when(presetService.getAllPresets()).thenReturn(List.of(p1, p2));

        mockMvc.perform(get("/api/access-control/level-presets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].areaLevel").value("PUBLIC"))
                .andExpect(jsonPath("$.data[1].areaLevel").value("HIGHLY_CONFIDENTIAL"));
    }

    @Test
    @DisplayName("PATCH /api/access-control/level-presets/{areaType} hợp lệ -> 200 OK")
    void updateLevelPreset_Valid_Returns200() throws Exception {
        LevelPresetUpdateRequest req = new LevelPresetUpdateRequest(2, true, "Nâng cấp bảo mật", 0L);
        LevelPresetResponse resp = new LevelPresetResponse(AreaLevel.PUBLIC, 2, true, OffsetDateTime.now(), "FM", "FM01", 1L);

        when(presetService.updatePreset(eq(AreaLevel.PUBLIC), any(), any())).thenReturn(resp);

        mockMvc.perform(patch("/api/access-control/level-presets/{areaType}", "PUBLIC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.areaAccessLevel").value(2))
                .andExpect(jsonPath("$.data.explicitAuthorizationRequired").value(true));
    }

    @Test
    @DisplayName("PATCH /api/access-control/level-presets/{areaType} với level ngoài 1..3 -> 400 Bad Request")
    void updateLevelPreset_InvalidLevel_Returns400() throws Exception {
        LevelPresetUpdateRequest req0 = new LevelPresetUpdateRequest(0, false, "Lý do", 0L);
        mockMvc.perform(patch("/api/access-control/level-presets/{areaType}", "PUBLIC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req0)))
                .andExpect(status().isBadRequest());

        LevelPresetUpdateRequest req4 = new LevelPresetUpdateRequest(4, false, "Lý do", 0L);
        mockMvc.perform(patch("/api/access-control/level-presets/{areaType}", "PUBLIC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req4)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("BR-AL-03: PUT/PATCH /api/access-control/level-presets/{areaType} với reason null, rỗng, khoảng trắng, 501 ký tự -> 400 Bad Request và service không được gọi")
    void updateLevelPreset_ReasonValidation_Returns400_AndServiceNeverCalled() throws Exception {
        // 1. reason = null
        String bodyNull = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": null, \"version\": 0}";
        mockMvc.perform(patch("/api/access-control/level-presets/{areaType}", "PUBLIC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyNull))
                .andExpect(status().isBadRequest());

        // 2. reason = ""
        String bodyEmpty = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": \"\", \"version\": 0}";
        mockMvc.perform(put("/api/access-control/level-presets/{areaType}", "PUBLIC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyEmpty))
                .andExpect(status().isBadRequest());

        // 3. reason = "   "
        String bodySpaces = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": \"   \", \"version\": 0}";
        mockMvc.perform(patch("/api/access-control/level-presets/{areaType}", "PUBLIC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodySpaces))
                .andExpect(status().isBadRequest());

        // 4. reason = 501 ký tự
        String body501 = "{\"areaAccessLevel\": 2, \"explicitAuthorizationRequired\": false, \"reason\": \"" + "a".repeat(501) + "\", \"version\": 0}";
        mockMvc.perform(put("/api/access-control/level-presets/{areaType}", "PUBLIC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body501))
                .andExpect(status().isBadRequest());

        org.mockito.Mockito.verify(presetService, org.mockito.Mockito.never())
                .updatePreset(any(), any(), any());
    }

    @Test
    @DisplayName("PATCH /api/access-control/level-presets/{areaType} với version xung đột -> 409 Conflict")
    void updateLevelPreset_VersionConflict_Returns409() throws Exception {
        LevelPresetUpdateRequest req = new LevelPresetUpdateRequest(2, true, "Lý do", 0L);
        when(presetService.updatePreset(eq(AreaLevel.PUBLIC), any(), any()))
                .thenThrow(new AccessControlException(AccessControlErrorCode.ERR_AC_003));

        mockMvc.perform(patch("/api/access-control/level-presets/{areaType}", "PUBLIC")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ERR_AC_003"));
    }

    @Test
    @DisplayName("GET /api/access-control/audit-logs -> 200 OK với danh sách phân trang")
    void getAuditLogs_Returns200() throws Exception {
        AccessControlAuditLogResponse logResp = new AccessControlAuditLogResponse(
                UUID.randomUUID(),
                AccessControlTargetType.USER_ACCESS_LEVEL,
                com.fa26se040.icss.enums.AccessControlAction.UPDATE,
                UUID.randomUUID().toString(),
                null,
                null,
                UUID.randomUUID(),
                "Nguyễn Văn A",
                "NV01",
                null,
                null,
                "Lý do",
                UUID.randomUUID(),
                "FM",
                "FM01",
                OffsetDateTime.now()
        );
        Page<AccessControlAuditLogResponse> page = new PageImpl<>(List.of(logResp), PageRequest.of(0, 10), 1);
        when(auditService.getAuditLogs(eq(AccessControlTargetType.USER_ACCESS_LEVEL), any(), any(), any(), any(), any(), any())).thenReturn(page);

        mockMvc.perform(get("/api/access-control/audit-logs")
                        .param("targetType", "USER_ACCESS_LEVEL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].targetType").value("USER_ACCESS_LEVEL"));
    }
}
