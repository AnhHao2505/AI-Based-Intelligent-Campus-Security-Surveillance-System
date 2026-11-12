package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.chat.GuardChatMemberDto;
import com.fa26se040.icss.dto.chat.GuardChatMessageDto;
import com.fa26se040.icss.dto.chat.SendChatMessageRequest;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.service.GuardChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
@RequestMapping("/api/guard-chat")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('GUARD', 'FACILITY_MANAGER')")
public class GuardChatController {

    private final GuardChatService guardChatService;

    @GetMapping("/guards")
    public ResponseEntity<ApiResponse<List<GuardChatMemberDto>>> getGuards(
            @RequestParam(required = false) Boolean onDutyOnly,
            @RequestParam(required = false) String keyword,
            Authentication authentication) {
        String email = authentication.getName();
        List<GuardChatMemberDto> guards = guardChatService.getGuardsForChat(email, onDutyOnly, keyword);
        return ResponseEntity.ok(ApiResponse.success(guards, "Lấy danh sách nhân viên bảo vệ thành công"));
    }

    @GetMapping("/messages")
    public ResponseEntity<ApiResponse<Page<GuardChatMessageDto>>> getMessages(
            @RequestParam UUID peerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            Authentication authentication) {
        String email = authentication.getName();
        int cappedSize = Math.min(Math.max(1, size), 100);
        Pageable pageable = PageRequest.of(Math.max(0, page), cappedSize);
        Page<GuardChatMessageDto> messages = guardChatService.getConversation(email, peerId, pageable);
        return ResponseEntity.ok(ApiResponse.success(messages, "Lấy tin nhắn hội thoại thành công"));
    }

    @PostMapping("/messages")
    public ResponseEntity<ApiResponse<GuardChatMessageDto>> sendMessage(
            @Valid @RequestBody SendChatMessageRequest request,
            Authentication authentication) {
        String email = authentication.getName();
        GuardChatMessageDto message = guardChatService.sendMessage(email, request);
        return new ResponseEntity<>(ApiResponse.created(message, "Gửi tin nhắn thành công"), HttpStatus.CREATED);
    }

    @PostMapping("/read/{peerId}")
    public ResponseEntity<ApiResponse<Void>> markAsRead(
            @PathVariable UUID peerId,
            Authentication authentication) {
        String email = authentication.getName();
        guardChatService.markConversationAsRead(email, peerId);
        return ResponseEntity.ok(ApiResponse.success("Đánh dấu đã đọc thành công"));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getUnreadCount(Authentication authentication) {
        String email = authentication.getName();
        long unread = guardChatService.getUnreadCount(email);
        return ResponseEntity
                .ok(ApiResponse.success(Map.of("unreadCount", unread), "Lấy số tin nhắn chưa đọc thành công"));
    }
}
