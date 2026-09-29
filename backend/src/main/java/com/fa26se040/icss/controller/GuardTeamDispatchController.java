package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.guard.GuardTeamDispatchCreateRequest;
import com.fa26se040.icss.dto.guard.GuardTeamDispatchDto;
import com.fa26se040.icss.service.GuardTeamDispatchService;
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
@RequestMapping("/api/guard-teams/dispatches")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
public class GuardTeamDispatchController {

    private final GuardTeamDispatchService dispatchService;

    @PostMapping
    public ResponseEntity<ApiResponse<List<GuardTeamDispatchDto>>> createDispatches(
            @Valid @RequestBody GuardTeamDispatchCreateRequest request,
            Authentication authentication
    ) {
        String email = authentication != null ? authentication.getName() : null;
        List<GuardTeamDispatchDto> created = dispatchService.createDispatches(request, email);
        return new ResponseEntity<>(ApiResponse.created(created, "Điều động bảo vệ thành công"), HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<GuardTeamDispatchDto>>> getDispatches(
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ResponseEntity.ok(ApiResponse.success(dispatchService.getDispatches(teamId, date, startDate, endDate), "Lấy danh sách điều động bảo vệ thành công"));
    }

    @GetMapping("/available-guards")
    public ResponseEntity<ApiResponse<List<com.fa26se040.icss.dto.guard.AvailableSubstituteDto>>> getAvailableGuards(
            @RequestParam UUID toTeamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) com.fa26se040.icss.enums.ShiftType shiftType
    ) {
        return ResponseEntity.ok(ApiResponse.success(dispatchService.getAvailableGuardsForDispatch(toTeamId, startDate, endDate, shiftType), "Lấy danh sách bảo vệ khả dụng để điều động thành công"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<GuardTeamDispatchDto>> cancelDispatch(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String email = authentication != null ? authentication.getName() : null;
        GuardTeamDispatchDto cancelled = dispatchService.cancelDispatch(id, email);
        return ResponseEntity.ok(ApiResponse.success(cancelled, "Hủy điều động bảo vệ thành công"));
    }
}
