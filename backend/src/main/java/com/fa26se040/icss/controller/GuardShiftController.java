package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.guard.GuardShiftCreateRequest;
import com.fa26se040.icss.dto.guard.GuardShiftDto;
import com.fa26se040.icss.dto.guard.GuardShiftUpdateRequest;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.service.GuardScheduleService;
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

    // ==========================================
    // ADMIN ENDPOINTS
    // ==========================================

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<GuardShiftDto>> getShifts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) UUID guardId,
            @RequestParam(required = false) String building,
            @RequestParam(required = false) ShiftStatus status
    ) {
        log.info("Admin querying shifts from [{}] to [{}], building: [{}]", startDate, endDate, building);
        return ResponseEntity.ok(guardScheduleService.getShifts(startDate, endDate, guardId, building, status));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GuardShiftDto> createShift(@Valid @RequestBody GuardShiftCreateRequest request) {
        log.info("Admin creating individual shift for guard [{}] on [{}]", request.getGuardId(), request.getShiftDate());
        GuardShiftDto created = guardScheduleService.createShift(request);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GuardShiftDto> updateShift(
            @PathVariable UUID id,
            @Valid @RequestBody GuardShiftUpdateRequest request
    ) {
        log.info("Admin updating shift [{}]", id);
        return ResponseEntity.ok(guardScheduleService.updateShift(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteShift(@PathVariable UUID id) {
        log.info("Admin deleting shift [{}]", id);
        guardScheduleService.deleteShift(id);
        return ResponseEntity.noContent().build();
    }

    // ==========================================
    // GUARD SELF-SERVICE ENDPOINTS
    // ==========================================

    @GetMapping("/my-shifts")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<List<GuardShiftDto>> getMyShifts(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            Authentication authentication
    ) {
        String guardEmail = authentication.getName();
        log.info("Guard [{}] querying personal shifts from [{}] to [{}]", guardEmail, startDate, endDate);
        return ResponseEntity.ok(guardScheduleService.getMyShifts(guardEmail, startDate, endDate));
    }

    @PostMapping("/{id}/check-in")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<GuardShiftDto> checkIn(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String guardEmail = authentication.getName();
        log.info("Guard [{}] checking in for shift [{}]", guardEmail, id);
        return ResponseEntity.ok(guardScheduleService.checkIn(id, guardEmail));
    }

    @PostMapping("/{id}/check-out")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<GuardShiftDto> checkOut(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String guardEmail = authentication.getName();
        log.info("Guard [{}] checking out from shift [{}]", guardEmail, id);
        return ResponseEntity.ok(guardScheduleService.checkOut(id, guardEmail));
    }
}
