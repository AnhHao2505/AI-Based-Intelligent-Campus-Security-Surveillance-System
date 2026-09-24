package com.fa26se040.icss.controller;

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
    public ResponseEntity<List<GuardTeamDispatchDto>> createDispatches(
            @Valid @RequestBody GuardTeamDispatchCreateRequest request,
            Authentication authentication
    ) {
        String email = authentication != null ? authentication.getName() : null;
        List<GuardTeamDispatchDto> created = dispatchService.createDispatches(request, email);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping
    public ResponseEntity<List<GuardTeamDispatchDto>> getDispatches(
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate
    ) {
        return ResponseEntity.ok(dispatchService.getDispatches(teamId, date, startDate, endDate));
    }

    @GetMapping("/available-guards")
    public ResponseEntity<List<com.fa26se040.icss.dto.guard.AvailableSubstituteDto>> getAvailableGuards(
            @RequestParam UUID toTeamId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) com.fa26se040.icss.enums.ShiftType shiftType
    ) {
        return ResponseEntity.ok(dispatchService.getAvailableGuardsForDispatch(toTeamId, startDate, endDate, shiftType));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<GuardTeamDispatchDto> cancelDispatch(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String email = authentication != null ? authentication.getName() : null;
        return ResponseEntity.ok(dispatchService.cancelDispatch(id, email));
    }
}
