package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.incident.IncidentClaimResponse;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.dto.incident.IncidentResolveRequest;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.exception.IncidentNotOfferedException;
import com.fa26se040.icss.exception.IncidentOfferNotPendingException;
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

@Slf4j
@RestController
@RequestMapping("/api/incidents")
@RequiredArgsConstructor
public class SecurityIncidentController {

    private final SecurityIncidentService securityIncidentService;

    @GetMapping("/active")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<List<IncidentDetailResponse>>> getActiveIncidents(
            @RequestParam(required = false) String building,
            @RequestParam(required = false, defaultValue = "false") boolean assignedShiftOnly,
            Authentication authentication
    ) {
        log.info("Querying active incidents for building [{}], assignedShiftOnly [{}]", building, assignedShiftOnly);
        String userEmail = authentication != null ? authentication.getName() : null;
        List<IncidentDetailResponse> incidents = assignedShiftOnly
                ? securityIncidentService.getActiveIncidents(building, true, userEmail)
                : securityIncidentService.getActiveIncidents(building);
        return ResponseEntity.ok(ApiResponse.success(incidents, "Lấy danh sách sự cố đang diễn ra thành công"));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<List<IncidentDetailResponse>>> getIncidents(
            @RequestParam(required = false) String building,
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false, defaultValue = "false") boolean assignedShiftOnly,
            Authentication authentication
    ) {
        log.info("Querying incidents with status [{}], building [{}], assignedShiftOnly [{}]", status, building, assignedShiftOnly);
        String userEmail = authentication != null ? authentication.getName() : null;
        List<IncidentDetailResponse> incidents = assignedShiftOnly
                ? securityIncidentService.getIncidents(building, status, true, userEmail)
                : securityIncidentService.getIncidents(building, status);
        return ResponseEntity.ok(ApiResponse.success(incidents, "Lấy danh sách sự cố an ninh thành công"));
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
        } catch (IncidentNotOfferedException e) {
            // V93 (BR-DSP-06): sự cố đang ở bước 1, bảo vệ này không có lời mời còn chờ. Mã lỗi ở field errorCode
            log.warn("Guard [{}] claimed incident [{}] without a pending offer: {}", guardEmail, id, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(HttpStatus.FORBIDDEN.value(), IncidentNotOfferedException.ERROR_CODE, e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("Conflict claiming incident [{}]: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(HttpStatus.CONFLICT.value(), "INCIDENT_ALREADY_CLAIMED", e.getMessage()));
        }
    }

    /**
     * V93 (K5): bảo vệ từ chối lời mời bước 1 của mình. Không còn lời mời chờ -> 409, errorCode INCIDENT_OFFER_NOT_PENDING.
     */
    @PostMapping("/{id}/offers/decline")
    @PreAuthorize("hasRole('GUARD')")
    public ResponseEntity<ApiResponse<String>> declineOffer(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String guardEmail = authentication.getName();
        log.info("Guard [{}] declining offer for incident [{}]", guardEmail, id);
        try {
            securityIncidentService.declineOffer(id, guardEmail);
            return ResponseEntity.ok(ApiResponse.success("DECLINED", "Đã từ chối lời mời xử lý sự cố"));
        } catch (IncidentOfferNotPendingException e) {
            log.warn("Guard [{}] has no pending offer for incident [{}]", guardEmail, id);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(HttpStatus.CONFLICT.value(), IncidentOfferNotPendingException.ERROR_CODE, e.getMessage()));
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

    @PostMapping("/{id}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<String>> acknowledgeIncident(
            @PathVariable String id
    ) {
        log.info("Guard acknowledged incident [{}] (marking as seen does not cut recording)", id);
        // Guard marking incident alert as seen does NOT cut video anymore.
        // Incident recording is managed by AI Service (starts 2s after detection, ends when detection ceases).
        return ResponseEntity.ok(ApiResponse.success("ACKNOWLEDGED", "Đã xác nhận xem sự cố"));
    }

    @PostMapping("/acknowledge-all")
    @PreAuthorize("hasAnyRole('ADMIN', 'GUARD')")
    public ResponseEntity<ApiResponse<String>> acknowledgeAllIncidents() {
        log.info("Guard acknowledged all incidents (marking as seen does not cut recording)");
        // Guard marking incident alert as seen does NOT cut video anymore.
        // Incident recording is managed by AI Service (starts 2s after detection, ends when detection ceases).
        return ResponseEntity.ok(ApiResponse.success("ALL_ACKNOWLEDGED", "Đã xác nhận xem tất cả sự cố"));
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
        if (response == null) {
            return ResponseEntity.status(HttpStatus.OK)
                    .body(ApiResponse.success(null, "Camera đã ngừng hoạt động (DECOMMISSIONED), sự cố được bỏ qua"));
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response, "Sự cố an ninh đã được tiếp nhận và phát sóng toàn hệ thống"));
    }
}
