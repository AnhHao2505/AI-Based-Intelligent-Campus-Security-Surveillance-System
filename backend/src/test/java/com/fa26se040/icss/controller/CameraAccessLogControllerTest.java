package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.accesslog.CameraAccessLogResponse;
import com.fa26se040.icss.dto.accesslog.CameraAccessLogStatsResponse;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.service.CameraAccessLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CameraAccessLogControllerTest {

    @Mock
    private CameraAccessLogService cameraAccessLogService;

    @InjectMocks
    private CameraAccessLogController cameraAccessLogController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(cameraAccessLogController)
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("GET /api/cameras/{cameraCode}/access-logs - Lấy danh sách nhật ký phân trang thành công")
    void getAccessLogs_Success() throws Exception {
        String cameraCode = "CAM-01";
        CameraAccessLogResponse item = CameraAccessLogResponse.builder()
                .id(UUID.randomUUID())
                .cameraCode(cameraCode)
                .userCode("SE170001")
                .fullName("Nguyen Van A")
                .accessResult(AccessResult.AUTHORIZED_ACCESS)
                .resultDescription("Hợp lệ")
                .colorCode("#10b981")
                .accessSource(AccessSource.ACCESS_LEVEL)
                .loggedAt(OffsetDateTime.now())
                .build();

        PageImpl<CameraAccessLogResponse> page = new PageImpl<>(Collections.singletonList(item), PageRequest.of(0, 20), 1);

        when(cameraAccessLogService.getLogs(eq(cameraCode), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/cameras/{cameraCode}/access-logs", cameraCode)
                        .param("page", "0")
                        .param("size", "20")
                        .param("result", "AUTHORIZED_ACCESS")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.content[0].cameraCode").value(cameraCode))
                .andExpect(jsonPath("$.data.content[0].userCode").value("SE170001"))
                .andExpect(jsonPath("$.data.content[0].accessResult").value("AUTHORIZED_ACCESS"))
                .andExpect(jsonPath("$.data.content[0].colorCode").value("#10b981"));

        verify(cameraAccessLogService).getLogs(eq(cameraCode), eq(AccessResult.AUTHORIZED_ACCESS), any(), any(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /api/cameras/{cameraCode}/access-logs/stats - Lấy thống kê số lượt trong ngày thành công")
    void getAccessLogStats_Success() throws Exception {
        String cameraCode = "CAM-01";
        CameraAccessLogStatsResponse stats = CameraAccessLogStatsResponse.builder()
                .cameraCode(cameraCode)
                .totalToday(10)
                .authorizedToday(7)
                .unauthorizedToday(1)
                .outOfTimeToday(1)
                .unknownToday(1)
                .intrusionToday(0)
                .build();

        when(cameraAccessLogService.getStatsToday(cameraCode)).thenReturn(stats);

        mockMvc.perform(get("/api/cameras/{cameraCode}/access-logs/stats", cameraCode)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.cameraCode").value(cameraCode))
                .andExpect(jsonPath("$.data.totalToday").value(10))
                .andExpect(jsonPath("$.data.authorizedToday").value(7))
                .andExpect(jsonPath("$.data.unauthorizedToday").value(1))
                .andExpect(jsonPath("$.data.outOfTimeToday").value(1))
                .andExpect(jsonPath("$.data.unknownToday").value(1))
                .andExpect(jsonPath("$.data.intrusionToday").value(0));

        verify(cameraAccessLogService).getStatsToday(cameraCode);
    }
}
