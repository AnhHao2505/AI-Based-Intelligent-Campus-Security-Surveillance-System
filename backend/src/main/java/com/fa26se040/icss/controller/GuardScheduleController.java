package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.guard.GenerateShiftsRequest;
import com.fa26se040.icss.dto.guard.GenerateShiftsResponse;
import com.fa26se040.icss.dto.guard.GuardScheduleTemplateCreateRequest;
import com.fa26se040.icss.dto.guard.GuardScheduleTemplateDto;
import com.fa26se040.icss.service.GuardScheduleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/guard-schedules")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
public class GuardScheduleController {

    private final GuardScheduleService guardScheduleService;

    @GetMapping("/templates")
    public ResponseEntity<ApiResponse<List<GuardScheduleTemplateDto>>> getTemplates(
            @RequestParam(required = false) String building
    ) {
        log.info("Admin fetching guard schedule templates, building: {}", building);
        return ResponseEntity.ok(ApiResponse.success(guardScheduleService.getTemplates(building), "Lấy danh sách mẫu ca trực thành công"));
    }

    @PostMapping("/templates")
    public ResponseEntity<ApiResponse<GuardScheduleTemplateDto>> createTemplate(
            @Valid @RequestBody GuardScheduleTemplateCreateRequest request
    ) {
        log.info("Admin creating guard schedule template for guard [{}]", request.getGuardId());
        GuardScheduleTemplateDto created = guardScheduleService.createTemplate(request);
        return new ResponseEntity<>(ApiResponse.created(created, "Tạo mẫu ca trực thành công"), HttpStatus.CREATED);
    }

    @PutMapping("/templates/{id}")
    public ResponseEntity<ApiResponse<GuardScheduleTemplateDto>> updateTemplate(
            @PathVariable UUID id,
            @Valid @RequestBody GuardScheduleTemplateCreateRequest request
    ) {
        log.info("Admin updating guard schedule template [{}]", id);
        return ResponseEntity.ok(ApiResponse.success(guardScheduleService.updateTemplate(id, request), "Cập nhật mẫu ca trực thành công"));
    }

    @DeleteMapping("/templates/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteTemplate(@PathVariable UUID id) {
        log.info("Admin deleting guard schedule template [{}]", id);
        guardScheduleService.deleteTemplate(id);
        return ResponseEntity.ok(ApiResponse.success("Xóa mẫu ca trực thành công"));
    }

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<GenerateShiftsResponse>> generateShifts(
            @Valid @RequestBody GenerateShiftsRequest request
    ) {
        log.info("Admin generating shifts from [{}] to [{}] for building [{}]",
                request.getStartDate(), request.getEndDate(), request.getBuilding());
        return ResponseEntity.ok(ApiResponse.success(guardScheduleService.generateShifts(request), "Tạo ca trực từ mẫu thành công"));
    }
}
