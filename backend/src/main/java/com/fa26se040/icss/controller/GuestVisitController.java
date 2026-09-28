package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.guest.GuestVisitCancelRequest;
import com.fa26se040.icss.dto.guest.GuestVisitCreateRequest;
import com.fa26se040.icss.dto.guest.GuestVisitResponse;
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
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<GuestVisitResponse>> create(@RequestBody JsonNode body, Authentication authentication) {
        GuestVisitCreateRequest req = readStrict(body, GuestVisitCreateRequest.class);
        GuestVisitResponse data = guestVisitService.create(req, authentication.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(data, "Đã tạo lượt khách, chờ duyệt"));
    }

    @GetMapping("/my")
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER', 'ADMIN')")
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
    @PreAuthorize("hasAnyRole('NORMAL_USER', 'FACILITY_MANAGER', 'ADMIN')")
    public ResponseEntity<ApiResponse<GuestVisitResponse>> cancel(@PathVariable UUID id,
                                                                  @RequestBody(required = false) GuestVisitCancelRequest body,
                                                                  Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(guestVisitService.cancel(id, body, authentication.getName()), "Đã huỷ lượt khách"));
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
