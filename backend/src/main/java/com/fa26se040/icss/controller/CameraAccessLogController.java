package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.accesslog.CameraAccessLogResponse;
import com.fa26se040.icss.dto.accesslog.CameraAccessLogStatsResponse;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.service.CameraAccessLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

@Slf4j
@RestController
@RequestMapping("/api/cameras/{cameraCode}/access-logs")
@RequiredArgsConstructor
public class CameraAccessLogController {

    private final CameraAccessLogService cameraAccessLogService;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER', 'GUARD')")
    public ResponseEntity<ApiResponse<Page<CameraAccessLogResponse>>> getLogs(
            @PathVariable String cameraCode,
            @RequestParam(required = false) AccessResult result,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @PageableDefault(size = 20, sort = "loggedAt", direction = Sort.Direction.DESC) Pageable pageable
    ) {
        log.info("REST request to get access logs for camera [{}], result: [{}], search: [{}]", cameraCode, result, search);
        Page<CameraAccessLogResponse> logs = cameraAccessLogService.getLogs(cameraCode, result, search, from, to, pageable);
        return ResponseEntity.ok(ApiResponse.success(logs, "Lấy danh sách nhật ký ra vào thành công"));
    }

    @GetMapping("/stats")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER', 'GUARD')")
    public ResponseEntity<ApiResponse<CameraAccessLogStatsResponse>> getStats(
            @PathVariable String cameraCode
    ) {
        log.info("REST request to get access log stats for camera [{}]", cameraCode);
        CameraAccessLogStatsResponse stats = cameraAccessLogService.getStatsToday(cameraCode);
        return ResponseEntity.ok(ApiResponse.success(stats, "Lấy thống kê nhật ký ra vào trong ngày thành công"));
    }
}
