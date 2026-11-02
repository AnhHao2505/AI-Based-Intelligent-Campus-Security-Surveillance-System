package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.report.SecurityReportSummaryDto;
import com.fa26se040.icss.service.SecurityReportPdfService;
import com.fa26se040.icss.service.SecurityReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ReportControllerTest {

    @Mock
    private SecurityReportService securityReportService;

    @Mock
    private SecurityReportPdfService securityReportPdfService;

    @InjectMocks
    private ReportController reportController;

    private MockMvc mockMvc;
    private Authentication mockAdminAuth;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(reportController).build();
        mockAdminAuth = new UsernamePasswordAuthenticationToken("admin@fpt.edu.vn", "password");
    }

    @Test
    @DisplayName("GET /api/reports/security-summary - Thành công khi ngày hợp lệ")
    void getSecurityReportSummary_Success() throws Exception {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 2);

        SecurityReportSummaryDto mockDto = new SecurityReportSummaryDto(
                start,
                end,
                OffsetDateTime.now(),
                "Administrator",
                "admin@fpt.edu.vn",
                "FPT University TP.HCM — Cơ sở Tân Uyên",
                10L, 8L, 2L, 0L, 0.2, 45L, 180L,
                1500L, 1480L, 20L,
                40L, 39L,
                20L, 20L, 1.0,
                15L, 14L,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        when(securityReportService.generateReport(eq(start), eq(end), eq("admin@fpt.edu.vn")))
                .thenReturn(mockDto);

        mockMvc.perform(get("/api/reports/security-summary")
                        .param("startDate", "2026-11-01")
                        .param("endDate", "2026-11-02")
                        .principal(mockAdminAuth))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(200))
                .andExpect(jsonPath("$.data.totalIncidents").value(10))
                .andExpect(jsonPath("$.data.totalAccessScans").value(1500))
                .andExpect(jsonPath("$.data.campusName").value("FPT University TP.HCM — Cơ sở Tân Uyên"));
    }

    @Test
    @DisplayName("GET /api/reports/security-summary - Lỗi 400 khi startDate lớn hơn endDate")
    void getSecurityReportSummary_InvalidDateRange() throws Exception {
        mockMvc.perform(get("/api/reports/security-summary")
                        .param("startDate", "2026-11-05")
                        .param("endDate", "2026-11-01")
                        .principal(mockAdminAuth))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_DATE_RANGE"));
    }

    @Test
    @DisplayName("GET /api/reports/download-pdf - Thành công trả về file nhị phân PDF")
    void downloadSecurityReportPdf_Success() throws Exception {
        LocalDate start = LocalDate.of(2026, 11, 1);
        LocalDate end = LocalDate.of(2026, 11, 2);

        SecurityReportSummaryDto mockDto = new SecurityReportSummaryDto(
                start,
                end,
                OffsetDateTime.now(),
                "Administrator",
                "admin@fpt.edu.vn",
                "FPT University TP.HCM — Cơ sở Tân Uyên",
                10L, 8L, 2L, 0L, 0.2, 45L, 180L,
                1500L, 1480L, 20L,
                40L, 39L,
                20L, 20L, 1.0,
                15L, 14L,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of()
        );

        byte[] fakePdf = "%PDF-1.4 mock pdf content".getBytes();
        when(securityReportService.generateReport(eq(start), eq(end), eq("admin@fpt.edu.vn")))
                .thenReturn(mockDto);
        when(securityReportPdfService.generatePdf(eq(mockDto)))
                .thenReturn(fakePdf);

        mockMvc.perform(get("/api/reports/download-pdf")
                        .param("startDate", "2026-11-01")
                        .param("endDate", "2026-11-02")
                        .principal(mockAdminAuth))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"Bao-Cao-An-Ninh-FPTU-2026-11-01-den-2026-11-02.pdf\""))
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(fakePdf));
    }
}


