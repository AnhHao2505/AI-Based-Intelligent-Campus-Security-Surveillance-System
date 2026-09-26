package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.accessrequest.AccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.AccessRequestResponse;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse;
import com.fa26se040.icss.dto.accessrequest.GroupAccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.IndividualAccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.MemberLookupResult;
import com.fa26se040.icss.dto.accessrequest.ResolveMembersRequest;
import com.fa26se040.icss.dto.common.ApiResponse;
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
    public ResponseEntity<ApiResponse<List<AreaSimpleResponse>>> getAvailableAreas() {
        return ResponseEntity.ok(ApiResponse.success(areaService.getAvailableAreasForRequest(), "Lấy danh sách khu vực khả dụng thành công"));
    }

    @PostMapping("/individual")
    @PreAuthorize("hasRole('NORMAL_USER')")
    public ResponseEntity<ApiResponse<AccessRequestResponse>> createIndividualRequest(
            @Valid @RequestBody IndividualAccessRequestCreateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AccessRequestResponse response = accessRequestService.createIndividualRequest(request, actorEmail);
        URI location = URI.create("/api/access-requests/" + response.id());
        return ResponseEntity.created(location).body(ApiResponse.created(response, "Tạo yêu cầu truy cập cá nhân thành công"));
    }

    @PostMapping("/group")
    @PreAuthorize("hasRole('NORMAL_USER')")
    public ResponseEntity<ApiResponse<AccessRequestResponse>> createGroupRequest(
            @Valid @RequestBody GroupAccessRequestCreateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AccessRequestResponse response = accessRequestService.createGroupRequest(request, actorEmail);
        URI location = URI.create("/api/access-requests/" + response.id());
        return ResponseEntity.created(location).body(ApiResponse.created(response, "Tạo yêu cầu truy cập nhóm thành công"));
    }

    @PostMapping
    @PreAuthorize("hasRole('NORMAL_USER')")
    public ResponseEntity<ApiResponse<AccessRequestResponse>> createRequest(
            @Valid @RequestBody AccessRequestCreateRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AccessRequestResponse response = accessRequestService.createRequest(request, actorEmail);
        URI location = URI.create("/api/access-requests/" + response.id());
        return ResponseEntity.created(location).body(ApiResponse.created(response, "Tạo yêu cầu truy cập thành công"));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('NORMAL_USER')")
    public ResponseEntity<ApiResponse<Page<AccessRequestResponse>>> getMyRequests(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        int cappedSize = Math.min(Math.max(1, size), 50);
        Pageable pageable = PageRequest.of(page, cappedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AccessRequestResponse> result = accessRequestService.getMyRequests(actorEmail, status, areaId, pageable);
        return ResponseEntity.ok(ApiResponse.success(result, "Lấy danh sách yêu cầu truy cập của tôi thành công"));
    }

    @GetMapping
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<Page<AccessRequestResponse>>> getAllRequests(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) UUID areaId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        int cappedSize = Math.min(Math.max(1, size), 50);
        Pageable pageable = PageRequest.of(page, cappedSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<AccessRequestResponse> result = accessRequestService.getAllRequests(status, areaId, pageable);
        return ResponseEntity.ok(ApiResponse.success(result, "Lấy danh sách tất cả yêu cầu truy cập thành công"));
    }

    @PostMapping("/resolve-members")
    @PreAuthorize("hasAnyRole('NORMAL_USER','FACILITY_MANAGER','ADMIN')")
    public ResponseEntity<ApiResponse<List<MemberLookupResult>>> resolveMembers(
            @Valid @RequestBody ResolveMembersRequest request,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        List<MemberLookupResult> result = accessRequestService.resolveMembers(request, actorEmail);
        return ResponseEntity.ok(ApiResponse.success(result, "Tra cứu thành viên thành công"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<AccessRequestResponse>> getRequestById(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        boolean isStaff = authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_FACILITY_MANAGER"));
        AccessRequestResponse result = accessRequestService.getRequestById(id, actorEmail, isStaff);
        return ResponseEntity.ok(ApiResponse.success(result, "Lấy chi tiết yêu cầu truy cập thành công"));
    }

    @PatchMapping("/{id}/review")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<AccessRequestResponse>> reviewRequest(
            @PathVariable UUID id,
            @Valid @RequestBody AccessRequestReviewRequest reviewRequest,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AccessRequestResponse result = accessRequestService.reviewRequest(id, reviewRequest, actorEmail);
        return ResponseEntity.ok(ApiResponse.success(result, "Phê duyệt/từ chối yêu cầu truy cập thành công"));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasRole('NORMAL_USER')")
    public ResponseEntity<ApiResponse<AccessRequestResponse>> cancelRequest(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AccessRequestResponse result = accessRequestService.cancelRequest(id, actorEmail);
        return ResponseEntity.ok(ApiResponse.success(result, "Hủy yêu cầu truy cập thành công"));
    }

    @PatchMapping("/{id}/finish")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<AccessRequestResponse>> finishRequest(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String actorEmail = authentication.getName();
        AccessRequestResponse result = accessRequestService.finishRequest(id, actorEmail);
        return ResponseEntity.ok(ApiResponse.success(result, "Hoàn tất yêu cầu truy cập thành công"));
    }
}
