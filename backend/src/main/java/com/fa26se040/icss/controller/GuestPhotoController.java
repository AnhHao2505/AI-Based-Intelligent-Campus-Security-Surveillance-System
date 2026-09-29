package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.guest.GuestPhotoUrlResponse;
import com.fa26se040.icss.dto.guest.GuestResponse;
import com.fa26se040.icss.service.GuestPhotoService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Ảnh khách (BR-GV-14..18). Chỉ ADMIN. Không endpoint nào trả object URL / key ảnh.
 */
@RestController
@RequestMapping("/api/guest-visits/{visitId}/guests/{guestId}")
@RequiredArgsConstructor
public class GuestPhotoController {

    private final GuestPhotoService guestPhotoService;

    @PostMapping(value = "/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<GuestResponse>> attach(@PathVariable UUID visitId,
                                                             @PathVariable UUID guestId,
                                                             @RequestParam(value = "file", required = false) MultipartFile file,
                                                             @RequestParam(value = "consentConfirmed", required = false) Boolean consentConfirmed,
                                                             @RequestParam(value = "consentNoticeVersion", required = false) String consentNoticeVersion,
                                                             Authentication authentication) {
        GuestResponse data = guestPhotoService.attach(visitId, guestId, file, consentConfirmed, consentNoticeVersion, authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(data, "Đã gắn ảnh khách"));
    }

    @PostMapping("/photo-url")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<GuestPhotoUrlResponse>> viewUrl(@PathVariable UUID visitId,
                                                                      @PathVariable UUID guestId,
                                                                      Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(guestPhotoService.issueViewUrl(visitId, guestId, authentication.getName()),
                "URL xem ảnh có hạn"));
    }
}
