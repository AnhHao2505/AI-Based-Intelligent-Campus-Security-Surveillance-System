package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.area.EventScheduleResponse;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.service.EventScheduleQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Màn Quản lý sự kiện: lịch sự kiện của mọi khu vực. Tạo / sửa / huỷ vẫn đi qua /api/areas/{id}/event-schedules.
 * Quyền đọc giống GET /api/areas/{id}/event-schedules (FACILITY_MANAGER, ADMIN).
 */
@RestController
@RequestMapping("/api/event-schedules")
@RequiredArgsConstructor
public class EventScheduleController {

    private final EventScheduleQueryService eventScheduleQueryService;

    @GetMapping
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Page<EventScheduleResponse>>> getAllEventSchedules(
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Page<EventScheduleResponse> result = eventScheduleQueryService.search(status, areaId, from, to, q, page, size);
        return ResponseEntity.ok(ApiResponse.success(result, "Lấy danh sách lịch sự kiện thành công"));
    }
}
