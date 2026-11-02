package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.report.SecurityReportSummaryDto;
import com.fa26se040.icss.service.SecurityReportPdfService;
import com.fa26se040.icss.service.SecurityReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final SecurityReportService securityReportService;
    private final SecurityReportPdfService securityReportPdfService;

    @GetMapping("/security-summary")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<SecurityReportSummaryDto>> getSecurityReportSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication
    ) {
        if (startDate.isAfter(endDate)) {
            return ResponseEntity.badRequest().body(ApiResponse.error(400, "INVALID_DATE_RANGE", "Ngày bắt đầu không được lớn hơn ngày kết thúc"));
        }

        String userEmail = authentication != null ? authentication.getName() : null;
        log.info("Generating report summary requested by [{}], range: [{} -> {}]", userEmail, startDate, endDate);

        SecurityReportSummaryDto report = securityReportService.generateReport(startDate, endDate, userEmail);
        return ResponseEntity.ok(ApiResponse.success(report, "Tạo báo cáo an ninh tổng hợp thành công"));
    }

    @GetMapping(value = "/download-pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<byte[]> downloadSecurityReportPdf(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication
    ) {
        if (startDate.isAfter(endDate)) {
            return ResponseEntity.badRequest().build();
        }

        String userEmail = authentication != null ? authentication.getName() : null;
        log.info("Generating native vector PDF report requested by [{}], range: [{} -> {}]", userEmail, startDate, endDate);

        SecurityReportSummaryDto report = securityReportService.generateReport(startDate, endDate, userEmail);
        byte[] pdfBytes = securityReportPdfService.generatePdf(report);

        String filename = "Bao-Cao-An-Ninh-FPTU-" + startDate + "-den-" + endDate + ".pdf";

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }
}


