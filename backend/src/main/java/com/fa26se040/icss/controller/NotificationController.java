package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.notification.NotificationResponse;
import com.fa26se040.icss.service.InAppNotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final InAppNotificationService inAppNotificationService;

    @GetMapping("/my")
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> getMyNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        String email = authentication.getName();
        int cappedSize = Math.min(Math.max(1, size), 50);
        Pageable pageable = PageRequest.of(Math.max(0, page), cappedSize);
        Page<NotificationResponse> result = inAppNotificationService.getMyNotifications(email, pageable);
        return ResponseEntity.ok(ApiResponse.success(result, "Lấy danh sách thông báo thành công"));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getUnreadCount(Authentication authentication) {
        String email = authentication.getName();
        long count = inAppNotificationService.getUnreadCount(email);
        return ResponseEntity.ok(ApiResponse.success(Map.of("count", count), "Lấy số lượng thông báo chưa đọc thành công"));
    }

    @PatchMapping("/{id}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(
            @PathVariable UUID id,
            Authentication authentication
    ) {
        String email = authentication.getName();
        NotificationResponse response = inAppNotificationService.markAsRead(id, email);
        return ResponseEntity.ok(ApiResponse.success(response, "Đánh dấu thông báo đã đọc thành công"));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Map<String, Object>>> markAllAsRead(Authentication authentication) {
        String email = authentication.getName();
        int updatedCount = inAppNotificationService.markAllAsRead(email);
        return ResponseEntity.ok(ApiResponse.success(Map.of("updated", updatedCount), "Đánh dấu tất cả thông báo đã đọc thành công"));
    }
}
