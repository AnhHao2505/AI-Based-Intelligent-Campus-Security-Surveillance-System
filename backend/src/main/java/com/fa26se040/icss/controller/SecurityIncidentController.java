package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.incident.IncidentClaimResponse;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
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
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class SecurityIncidentController {

    private final SecurityIncidentService securityIncidentService;

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<List<IncidentDetailResponse>>> getActiveIncidents(
            @RequestParam(required = false) String building
    ) {
        log.info("Querying active incidents for building [{}]", building);
        return ResponseEntity.ok(ApiResponse.success(securityIncidentService.getActiveIncidents(building), "Lấy danh sách sự cố đang diễn ra thành công"));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<List<IncidentDetailResponse>>> getIncidents(
            @RequestParam(required = false) String building,
            @RequestParam(required = false) IncidentStatus status
    ) {
        log.info("Querying incidents with status [{}], building [{}]", status, building);
        return ResponseEntity.ok(ApiResponse.success(securityIncidentService.getIncidents(building, status), "Lấy danh sách sự cố an ninh thành công"));
    }

    @PostMapping("/{id}/claim")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<ApiResponse<IncidentClaimResponse>> claimIncident(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String guardEmail = authentication.getName();
        log.info("Guard [{}] attempting to claim incident [{}]", guardEmail, id);
        try {
            IncidentClaimResponse response = securityIncidentService.claimIncident(id, guardEmail);
            return ResponseEntity.ok(ApiResponse.success(response, "Tiếp nhận xử lý sự cố thành công"));
        } catch (IllegalStateException e) {
            log.warn("Conflict claiming incident [{}]: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(HttpStatus.CONFLICT.value(), "INCIDENT_ALREADY_CLAIMED", e.getMessage()));
        }
    }

    @PostMapping("/{id}/resolve")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<ApiResponse<IncidentDetailResponse>> resolveIncident(
            @PathVariable UUID id,
            @Valid @RequestBody IncidentResolveRequest request,
            Authentication authentication
    ) {
        String guardEmail = authentication.getName();
        log.info("Guard [{}] submitting resolution for incident [{}]", guardEmail, id);
        try {
            IncidentDetailResponse response = securityIncidentService.resolveIncident(id, request, guardEmail);
            return ResponseEntity.ok(ApiResponse.success(response, "Xử lý và đóng sự cố thành công"));
        } catch (IllegalStateException e) {
            log.warn("Illegal state resolving incident [{}]: {}", id, e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("đã được xử lý")) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(HttpStatus.CONFLICT.value(), "INCIDENT_ALREADY_RESOLVED", e.getMessage()));
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "INVALID_STATE", e.getMessage()));
        }
    }

    @PostMapping("/test-alert")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<IncidentDetailResponse>> triggerTestAlert(
            @RequestBody IncidentEventDto testEvent
    ) {
        log.info("Triggering single test alert for camera [{}] type [{}]", testEvent.getCameraCode(), testEvent.getEventType());
        IncidentDetailResponse response = securityIncidentService.ingestIncident(testEvent);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Test alert broadcast thành công"));
    }

    @PostMapping("/test-alert/batch")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<List<IncidentDetailResponse>>> triggerBatchTestAlerts(
            @RequestBody List<IncidentEventDto> testEvents
    ) {
        log.info("Triggering batch test alerts: count [{}]", testEvents.size());
        List<IncidentDetailResponse> results = testEvents.stream()
                .map(securityIncidentService::ingestIncident)
                .collect(Collectors.toList());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(results, results.size() + " test alerts broadcast thành công"));
    }

    /**
     * [PRODUCTION HOOK] Endpoint chính thức để AI Service (Python) bắn sự cố trực tiếp qua HTTP REST (Webhook)
     * Chạy song song hoặc dự phòng cho Kafka Consumer khi tích hợp luồng phát hiện AI thực tế.
     */
    @PostMapping("/ingest")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<IncidentDetailResponse>> ingestDirectFromAiService(
            @RequestBody IncidentEventDto event
    ) {
        log.info("Direct ingest incident from AI Service for camera [{}] type [{}]", event.getCameraCode(), event.getEventType());
        IncidentDetailResponse response = securityIncidentService.ingestIncident(event);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Sự cố an ninh đã được tiếp nhận và phát sóng toàn hệ thống"));
    }
}
