package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.accesslog.AccessHistoryItem;
import com.fa26se040.icss.dto.accesslog.MyAccessHistoryItem;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.service.AccessHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lịch sử ra vào (MF4). GUARD không dùng API này — GUARD xem theo camera ở /api/cameras/{code}/access-logs.
 */
@RestController
@RequestMapping("/api/access-history")
@RequiredArgsConstructor
public class AccessHistoryController {

    private final AccessHistoryService accessHistoryService;

    /** Lịch sử của chính người đang đăng nhập; người được lấy từ token, không nhận userId từ request. */
    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Page<MyAccessHistoryItem>>> getMyHistory(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        Page<MyAccessHistoryItem> result = accessHistoryService.getMyHistory(authentication.getName(), from, to, page, size);
        return ResponseEntity.ok(ApiResponse.success(result, "Lấy lịch sử ra vào thành công"));
    }

    /** Lịch sử toàn hệ thống, lọc theo khu vực, camera, mã người, kết quả, khoảng thời gian. */
    @GetMapping
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Page<AccessHistoryItem>>> search(
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) String cameraCode,
            @RequestParam(required = false) String userCode,
            @RequestParam(required = false) AccessResult accessResult,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<AccessHistoryItem> result = accessHistoryService.search(areaId, cameraCode, userCode, accessResult, from, to, page, size);
        return ResponseEntity.ok(ApiResponse.success(result, "Lấy lịch sử ra vào thành công"));
    }
}
