package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.accessrequest.AccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.AccessRequestResponse;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.service.AccessRequestService;
import com.fa26se040.icss.service.AreaService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/access-requests")
@RequiredArgsConstructor
public class AccessRequestController {

    private final AccessRequestService accessRequestService;
    private final AreaService areaService;

    @GetMapping("/available-areas")
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<List<AreaSimpleResponse>> getAvailableAreas() {
        return ResponseEntity.ok(areaService.getAvailableAreasForRequest());
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<AccessRequestResponse> createRequest(
            @Valid @RequestBody AccessRequestCreateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AccessRequestResponse response = accessRequestService.createRequest(request, actorEmail);
        URI location = URI.create("/api/access-requests/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<Page<AccessRequestResponse>> getMyRequests(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        int cappedSize = Math.min(Math.max(1, size), 50);
        Pageable pageable = PageRequest.of(page, cappedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(accessRequestService.getMyRequests(actorEmail, status, pageable));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<Page<AccessRequestResponse>> getAllRequests(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int cappedSize = Math.min(Math.max(1, size), 50);
        Pageable pageable = PageRequest.of(page, cappedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ResponseEntity.ok(accessRequestService.getAllRequests(status, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<AccessRequestResponse> getRequestById(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        boolean isStaff = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_FACILITY_MANAGER") || a.getAuthority().equals("ROLE_ADMIN"));
        return ResponseEntity.ok(accessRequestService.getRequestById(id, actorEmail, isStaff));
    }

    @PatchMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<AccessRequestResponse> reviewRequest(
            @PathVariable UUID id,
            @Valid @RequestBody AccessRequestReviewRequest reviewRequest,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        return ResponseEntity.ok(accessRequestService.reviewRequest(id, reviewRequest, actorEmail));
    }
}
