package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.guard.GuardShiftRequestCreateDto;
import com.fa26se040.icss.dto.guard.GuardShiftRequestResponseDto;
import com.fa26se040.icss.dto.guard.GuardShiftRequestReviewDto;
import com.fa26se040.icss.enums.GuardShiftRequestStatus;
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
@RequestMapping("/api/guard-shift-requests")
@RequiredArgsConstructor
public class GuardShiftRequestController {

    private final GuardShiftRequestService requestService;

    @PostMapping
    @PreAuthorize("hasAnyRole('GUARD', 'ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<GuardShiftRequestResponseDto> createRequest(
            @Valid @RequestBody GuardShiftRequestCreateDto dto,
            Authentication authentication
    ) {
        GuardShiftRequestResponseDto created = requestService.createRequest(dto, authentication.getName());
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/my-requests")
    @PreAuthorize("hasAnyRole('GUARD', 'ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<List<GuardShiftRequestResponseDto>> getMyRequests(Authentication authentication) {
        return ResponseEntity.ok(requestService.getMyRequests(authentication.getName()));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<List<GuardShiftRequestResponseDto>> getAllRequests(
            @RequestParam(required = false) GuardShiftRequestStatus status,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ResponseEntity.ok(requestService.getAllRequests(status, teamId, startDate, endDate));
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<GuardShiftRequestResponseDto> approveRequest(
            @PathVariable UUID id,
            @RequestBody(required = false) GuardShiftRequestReviewDto reviewDto,
            Authentication authentication
    ) {
        return ResponseEntity.ok(requestService.approveRequest(id, reviewDto, authentication.getName()));
    }

    @PutMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")
    public ResponseEntity<GuardShiftRequestResponseDto> rejectRequest(
            @PathVariable UUID id,
            @RequestBody(required = false) GuardShiftRequestReviewDto reviewDto,
            Authentication authentication
    ) {
        return ResponseEntity.ok(requestService.rejectRequest(id, reviewDto, authentication.getName()));
    }
}
