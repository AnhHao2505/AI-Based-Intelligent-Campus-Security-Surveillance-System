package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.incident.IncidentClaimResponse;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentResolveRequest;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.service.SecurityIncidentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class SecurityIncidentController {

    private final SecurityIncidentService securityIncidentService;

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<List<IncidentDetailResponse>> getActiveIncidents(
            @RequestParam(required = false) String building
    ) {
        log.info("Querying active incidents for building [{}]", building);
        return ResponseEntity.ok(securityIncidentService.getActiveIncidents(building));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<List<IncidentDetailResponse>> getIncidents(
            @RequestParam(required = false) String building,
            @RequestParam(required = false) IncidentStatus status
    ) {
        log.info("Querying incidents with status [{}], building [{}]", status, building);
        return ResponseEntity.ok(securityIncidentService.getIncidents(building, status));
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<?> claimIncident(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String guardEmail = authentication.getName();
        log.info("Guard [{}] attempting to claim incident [{}]", guardEmail, id);
        try {
            IncidentClaimResponse response = securityIncidentService.claimIncident(id, guardEmail);
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            log.warn("Conflict claiming incident [{}]: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "INCIDENT_ALREADY_CLAIMED",
                    "message", e.getMessage()
            ));
        }
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<IncidentDetailResponse> resolveIncident(
            @PathVariable UUID id,
            @Valid @RequestBody IncidentResolveRequest request,
            Authentication authentication
    ) {
        String guardEmail = authentication.getName();
        log.info("Guard [{}] submitting resolution for incident [{}]", guardEmail, id);
        IncidentDetailResponse response = securityIncidentService.resolveIncident(id, request, guardEmail);
        return ResponseEntity.ok(response);
    }
}
