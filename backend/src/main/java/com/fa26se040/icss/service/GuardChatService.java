package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.chat.GuardChatMemberDto;
import com.fa26se040.icss.dto.chat.GuardChatMessageDto;
import com.fa26se040.icss.dto.chat.SendChatMessageRequest;
import com.fa26se040.icss.entity.GuardChatMessage;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.repository.GuardChatMessageRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardChatService {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final UserRepository userRepository;
    private final GuardShiftRepository guardShiftRepository;
    private final GuardChatMessageRepository chatMessageRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @Transactional(readOnly = true)
    public List<GuardChatMemberDto> getGuardsForChat(String currentUserEmail, Boolean onDutyOnly, String keyword) {
        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản"));

        List<User> allGuards = userRepository.findActiveUsersByRole(Role.GUARD);

        LocalDateTime nowLocal = OffsetDateTime.now().atZoneSameInstant(VN_ZONE).toLocalDateTime();
        LocalDate today = nowLocal.toLocalDate();

        List<GuardShift> recentShifts = guardShiftRepository.findShifts(today.minusDays(1), today, null, null, null);

        // Map unread counts for current user
        Map<UUID, Long> unreadMap = new HashMap<>();
        List<Object[]> unreadCounts = chatMessageRepository.countUnreadBySender(currentUser.getId());
        for (Object[] row : unreadCounts) {
            if (row != null && row.length >= 2 && row[0] instanceof UUID senderId && row[1] instanceof Number count) {
                unreadMap.put(senderId, count.longValue());
            }
        }

        String kw = (keyword != null && !keyword.isBlank()) ? keyword.trim().toLowerCase() : null;

        List<GuardChatMemberDto> result = new ArrayList<>();
        for (User g : allGuards) {
            if (g.getId().equals(currentUser.getId())) {
                continue; // Do not list self
            }

            if (kw != null) {
                boolean matchName = g.getFullName() != null && g.getFullName().toLowerCase().contains(kw);
                boolean matchCode = g.getUserCode() != null && g.getUserCode().toLowerCase().contains(kw);
                boolean matchEmail = g.getEmail() != null && g.getEmail().toLowerCase().contains(kw);
                if (!matchName && !matchCode && !matchEmail) {
                    continue;
                }
            }

            // Find if guard is currently on duty in shift window
            GuardShift activeShift = null;
            for (GuardShift s : recentShifts) {
                if (s.getGuard() != null && g.getId().equals(s.getGuard().getId())
                        && IncidentRecipientResolver.isWithinShift(s, nowLocal)
                        && (s.getStatus() == ShiftStatus.CHECKED_IN || s.getStatus() == ShiftStatus.SCHEDULED)) {
                    activeShift = s;
                    break;
                }
            }

            boolean isOnDuty = activeShift != null;
            if (Boolean.TRUE.equals(onDutyOnly) && !isOnDuty) {
                continue;
            }

            String shiftName = null;
            String buildingName = null;
            String dutyPost = null;
            if (activeShift != null) {
                shiftName = (activeShift.getShiftType() != null ? activeShift.getShiftType().name() : "Ca trực")
                        + " (" + (activeShift.getStartTime() != null ? activeShift.getStartTime() : "")
                        + " - " + (activeShift.getEndTime() != null ? activeShift.getEndTime() : "") + ")";
                if (activeShift.getBuilding() != null) {
                    buildingName = activeShift.getBuilding().getName();
                } else if (activeShift.getArea() != null) {
                    buildingName = activeShift.getArea().getName();
                }
                dutyPost = activeShift.getDutyPostCode();
            }

            String teamName = g.getTeam() != null ? g.getTeam().getTeamName() : null;
            long unread = unreadMap.getOrDefault(g.getId(), 0L);

            GuardChatMessage latestMsg = chatMessageRepository.findLatestMessage(currentUser.getId(), g.getId());

            result.add(GuardChatMemberDto.builder()
                    .id(g.getId())
                    .fullName(g.getFullName())
                    .userCode(g.getUserCode())
                    .email(g.getEmail())
                    .teamName(teamName)
                    .onDuty(isOnDuty)
                    .shiftName(shiftName)
                    .buildingName(buildingName)
                    .dutyPostCode(dutyPost)
                    .unreadCount(unread)
                    .lastMessage(latestMsg != null ? latestMsg.getContent() : null)
                    .lastMessageAt(latestMsg != null ? latestMsg.getCreatedAt() : null)
                    .build());
        }

        // Sắp xếp: có tin chưa đọc trước -> đang trực trước -> theo tên
        result.sort((a, b) -> {
            if (a.getUnreadCount() > 0 && b.getUnreadCount() == 0) return -1;
            if (a.getUnreadCount() == 0 && b.getUnreadCount() > 0) return 1;
            if (a.isOnDuty() && !b.isOnDuty()) return -1;
            if (!a.isOnDuty() && b.isOnDuty()) return 1;
            if (a.getLastMessageAt() != null && b.getLastMessageAt() != null) {
                return b.getLastMessageAt().compareTo(a.getLastMessageAt());
            }
            if (a.getLastMessageAt() != null) return -1;
            if (b.getLastMessageAt() != null) return 1;
            return String.valueOf(a.getFullName()).compareToIgnoreCase(String.valueOf(b.getFullName()));
        });

        return result;
    }

    @Transactional
    public Page<GuardChatMessageDto> getConversation(String currentUserEmail, UUID peerId, Pageable pageable) {
        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản"));

        User peer = userRepository.findById(peerId)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy nhân viên bảo vệ đối phương"));

        // Đánh dấu đã đọc các tin nhắn gửi tới mình
        chatMessageRepository.markAsRead(peer.getId(), currentUser.getId());

        Page<GuardChatMessage> pageResult = chatMessageRepository.findConversation(currentUser.getId(), peer.getId(), pageable);
        List<GuardChatMessageDto> dtos = pageResult.getContent().stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());

        return new PageImpl<>(dtos, pageable, pageResult.getTotalElements());
    }

    @Transactional
    public GuardChatMessageDto sendMessage(String senderEmail, SendChatMessageRequest request) {
        User sender = userRepository.findByEmail(senderEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người gửi"));

        User receiver = userRepository.findById(request.getReceiverId())
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy người nhận"));

        if (sender.getId().equals(receiver.getId())) {
            throw new IllegalArgumentException("Không thể tự gửi tin nhắn cho chính mình");
        }

        GuardChatMessage message = GuardChatMessage.builder()
                .sender(sender)
                .receiver(receiver)
                .content(request.getContent().trim())
                .isRead(false)
                .createdAt(OffsetDateTime.now())
                .build();

        GuardChatMessage saved = chatMessageRepository.save(message);
        GuardChatMessageDto dto = mapToDto(saved);

        // Phát STOMP tới kênh riêng của người nhận và người gửi để đồng bộ real-time
        try {
            messagingTemplate.convertAndSend("/topic/guards/" + receiver.getId() + "/messages", dto);
            messagingTemplate.convertAndSend("/topic/guards/" + sender.getId() + "/messages", dto);
        } catch (Exception e) {
            log.warn("Không gửi được STOMP message: {}", e.getMessage());
        }

        return dto;
    }

    @Transactional
    public void markConversationAsRead(String currentUserEmail, UUID peerId) {
        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản"));
        chatMessageRepository.markAsRead(peerId, currentUser.getId());
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(String currentUserEmail) {
        User currentUser = userRepository.findByEmail(currentUserEmail)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy thông tin tài khoản"));
        return chatMessageRepository.countUnreadTotal(currentUser.getId());
    }

    private GuardChatMessageDto mapToDto(GuardChatMessage m) {
        return GuardChatMessageDto.builder()
                .id(m.getId())
                .senderId(m.getSender() != null ? m.getSender().getId() : null)
                .senderName(m.getSender() != null ? m.getSender().getFullName() : null)
                .senderCode(m.getSender() != null ? m.getSender().getUserCode() : null)
                .receiverId(m.getReceiver() != null ? m.getReceiver().getId() : null)
                .receiverName(m.getReceiver() != null ? m.getReceiver().getFullName() : null)
                .receiverCode(m.getReceiver() != null ? m.getReceiver().getUserCode() : null)
                .content(m.getContent())
                .isRead(Boolean.TRUE.equals(m.getIsRead()))
                .createdAt(m.getCreatedAt())
                .build();
    }
}

