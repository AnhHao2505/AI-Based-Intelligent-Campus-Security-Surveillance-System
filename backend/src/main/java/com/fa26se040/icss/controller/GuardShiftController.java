package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.guard.AvailableSubstituteDto;
import com.fa26se040.icss.dto.guard.BulkClearShiftsRequest;
import com.fa26se040.icss.dto.guard.BulkClearShiftsResponse;
import com.fa26se040.icss.dto.guard.CapacityCalculateRequest;
import com.fa26se040.icss.dto.guard.CapacityCalculateResponse;
import com.fa26se040.icss.dto.guard.GuardShiftCreateRequest;
import com.fa26se040.icss.dto.guard.GuardShiftDto;
import com.fa26se040.icss.dto.guard.GuardShiftUpdateRequest;
import com.fa26se040.icss.dto.guard.WizardGenerateShiftsRequest;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.service.GuardScheduleService;
import com.fa26se040.icss.service.GuardShiftRequestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/guard-shifts")
@RequiredArgsConstructor
public class GuardShiftController {

    private final GuardScheduleService guardScheduleService;
    private final GuardShiftRequestService guardShiftRequestService;

    // ==========================================
    // ADMIN & FM ENDPOINTS
    // ==========================================

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<List<GuardShiftDto>>> getShifts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) UUID guardId,
            @RequestParam(required = false) String building,
            @RequestParam(required = false) ShiftStatus status
    ) {
        log.info("Admin/FM querying shifts from [{}] to [{}], building: [{}]", startDate, endDate, building);
        return ResponseEntity.ok(ApiResponse.success(guardScheduleService.getShifts(startDate, endDate, guardId, building, status), "Lấy danh sách ca trực thành công"));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<GuardShiftDto>> createShift(@Valid @RequestBody GuardShiftCreateRequest request) {
        log.info("Admin/FM creating individual shift for guard [{}] on [{}]", request.getGuardId(), request.getShiftDate());
        GuardShiftDto created = guardScheduleService.createShift(request);
        return new ResponseEntity<>(ApiResponse.created(created, "Tạo ca trực mới thành công"), HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<GuardShiftDto>> updateShift(
            @PathVariable UUID id,
            @Valid @RequestBody GuardShiftUpdateRequest request
    ) {
        log.info("Admin/FM updating shift [{}]", id);
        return ResponseEntity.ok(ApiResponse.success(guardScheduleService.updateShift(id, request), "Cập nhật ca trực thành công"));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<Void>> deleteShift(@PathVariable UUID id) {
        log.info("Admin/FM deleting shift [{}]", id);
        guardScheduleService.deleteShift(id);
        return ResponseEntity.ok(ApiResponse.success("Xóa ca trực thành công"));
    }

    @PostMapping("/bulk-clear")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<BulkClearShiftsResponse>> bulkClearShifts(
            @Valid @RequestBody BulkClearShiftsRequest request
    ) {
        log.info("Admin/FM bulk clearing shifts: {}", request);
        return ResponseEntity.ok(ApiResponse.success(guardScheduleService.bulkClearShifts(request), "Xóa hàng loạt ca trực thành công"));
    }

    @PostMapping("/wizard/calculate-capacity")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<CapacityCalculateResponse>> calculateCapacity(
            @Valid @RequestBody CapacityCalculateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(guardScheduleService.calculateCapacity(request), "Tính toán phân bổ ca trực thành công"));
    }

    @PostMapping("/wizard/generate")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<List<GuardShiftDto>>> generateShiftsFromWizard(
            @Valid @RequestBody WizardGenerateShiftsRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(guardScheduleService.generateShiftsFromWizard(request), "Tạo tự động lịch trực thành công"));
    }

    @GetMapping("/{id}/available-substitutes")
    @PreAuthorize("hasAnyRole('GUARD', 'ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<List<AvailableSubstituteDto>>> getAvailableSubstitutes(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String requesterEmail = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(guardShiftRequestService.getAvailableSubstitutes(id, requesterEmail), "Lấy danh sách người trực thay khả dụng thành công"));
    }

    @GetMapping("/{id}/available-swap-shifts")
    @PreAuthorize("hasAnyRole('GUARD', 'ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<List<com.fa26se040.icss.dto.guard.AvailableSwapShiftDto>>> getAvailableSwapShifts(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String requesterEmail = authentication.getName();
        return ResponseEntity.ok(ApiResponse.success(guardShiftRequestService.getAvailableSwapShifts(id, requesterEmail), "Lấy danh sách ca trực đổi khả dụng thành công"));
    }

    // ==========================================
    // GUARD SELF-SERVICE ENDPOINTS
    // ==========================================

    @GetMapping("/my-shifts")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<ApiResponse<List<GuardShiftDto>>> getMyShifts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication
    ) {
        String guardEmail = authentication.getName();
        log.info("Guard [{}] querying personal shifts from [{}] to [{}]", guardEmail, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(guardScheduleService.getMyShifts(guardEmail, startDate, endDate), "Lấy danh sách ca trực cá nhân thành công"));
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<ApiResponse<GuardShiftDto>> checkIn(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String guardEmail = authentication.getName();
        log.info("Guard [{}] checking in for shift [{}]", guardEmail, id);
        return ResponseEntity.ok(ApiResponse.success(guardScheduleService.checkIn(id, guardEmail), "Điểm danh vào ca thành công"));
    }

    @PostMapping("/{id}/check-out")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<ApiResponse<GuardShiftDto>> checkOut(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String guardEmail = authentication.getName();
        log.info("Guard [{}] checking out from shift [{}]", guardEmail, id);
        return ResponseEntity.ok(ApiResponse.success(guardScheduleService.checkOut(id, guardEmail), "Điểm danh tan ca thành công"));
    }
}
