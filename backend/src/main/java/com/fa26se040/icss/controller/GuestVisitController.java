package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.guest.GuestHostEligibilityResponse;
import com.fa26se040.icss.dto.guest.GuestVisitCancelRequest;
import com.fa26se040.icss.dto.guest.GuestVisitCreateRequest;
import com.fa26se040.icss.dto.guest.GuestVisitResponse;
import com.fa26se040.icss.dto.guest.GuestVisitReviewRequest;
import com.fa26se040.icss.dto.guest.GuestVisitRevokeRequest;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.exception.GuestErrorCode;
import com.fa26se040.icss.exception.GuestException;
import com.fa26se040.icss.service.GuestVisitService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Lượt khách (Khách G-A). Host = NORMAL_USER / FACILITY_MANAGER / ADMIN đủ điều kiện BR-GV-01.
 */
@RestController
@RequestMapping("/api/guest-visits")
public class GuestVisitController {

    private final GuestVisitService guestVisitService;
    /** Bản sao ObjectMapper từ chối trường lạ: không nhận CCCD / SĐT / địa chỉ / email (BR-GV-02). */
    private final ObjectMapper strictMapper;

    public GuestVisitController(GuestVisitService guestVisitService, ObjectMapper objectMapper) {
        this.guestVisitService = guestVisitService;
        this.strictMapper = objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<GuestVisitResponse>> create(@RequestBody JsonNode body, Authentication authentication) {
        GuestVisitCreateRequest req = readStrict(body, GuestVisitCreateRequest.class);
        GuestVisitResponse data = guestVisitService.create(req, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(data, "Đã tạo lượt khách, chờ duyệt"));
    }

    /** BR-GV-04: khu vực chọn được trong form lượt khách (INTERNAL / CONTACT đang hoạt động). Cùng quyền với tạo lượt (B-04: không có ADMIN). */
    @GetMapping("/selectable-areas")
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<java.util.List<com.fa26se040.icss.dto.accessrequest.AreaSimpleResponse>>> selectableAreas() {
        return ResponseEntity.ok(ApiResponse.success(guestVisitService.listSelectableAreas(), "Danh sách khu vực nhận khách"));
    }

    /** B-04: {canHost, myLevel, minLevel} để FE ẩn form tạo lượt khi không đủ điều kiện. Cùng quyền với tạo lượt. */
    @GetMapping("/eligibility")
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<GuestHostEligibilityResponse>> eligibility(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(guestVisitService.eligibility(authentication.getName()), "Điều kiện mời khách"));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<Page<GuestVisitResponse>>> listMine(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        Page<GuestVisitResponse> data = guestVisitService.listMine(authentication.getName(),
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return ResponseEntity.ok(ApiResponse.success(data, "Danh sách lượt khách của bạn"));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<GuestVisitResponse>> get(@PathVariable UUID id, Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(guestVisitService.get(id, authentication.getName()), "Chi tiết lượt khách"));
    }

    @PatchMapping("/{id}/cancel")
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<GuestVisitResponse>> cancel(@PathVariable UUID id,
                                                                  @RequestBody(required = false) GuestVisitCancelRequest body,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(guestVisitService.cancel(id, body, authentication.getName()), "Đã huỷ lượt khách"));
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<Page<GuestVisitResponse>>> list(
            @RequestParam(required = false) GuestVisitStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<GuestVisitResponse> data = guestVisitService.list(status,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));
        return ResponseEntity.ok(ApiResponse.success(data, "Danh sách lượt khách"));
    }

    @PatchMapping("/{id}/review")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<GuestVisitResponse>> review(@PathVariable UUID id,
                                                                  @RequestBody(required = false) GuestVisitReviewRequest body,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(guestVisitService.review(id, body, authentication.getName()), "Đã xử lý lượt khách"));
    }

    @PatchMapping("/{id}/revoke")
    @PreAuthorize("hasRole('FACILITY_MANAGER')")
    public ResponseEntity<ApiResponse<GuestVisitResponse>> revoke(@PathVariable UUID id,
                                                                  @RequestBody(required = false) GuestVisitRevokeRequest body,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(guestVisitService.revoke(id, body, authentication.getName()), "Đã thu hồi lượt khách"));
    }

    private <T> T readStrict(JsonNode body, Class<T> type) {
        try {
            return strictMapper.treeToValue(body, type);
        } catch (UnrecognizedPropertyException ex) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_025, ex.getPropertyName());
        } catch (Exception ex) {
            throw new GuestException(GuestErrorCode.ERR_GUEST_020, "body JSON hợp lệ");
        }
    }
}
