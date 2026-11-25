package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.chat.GuardChatMemberDto;
import com.fa26se040.icss.dto.chat.GuardChatMessageDto;
import com.fa26se040.icss.dto.chat.SendChatMessageRequest;
import com.fa26se040.icss.entity.GuardChatMessage;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.GuardChatMessageRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.mock.web.MockMultipartFile;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GuardChatServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private GuardShiftRepository guardShiftRepository;

    @Mock
    private GuardChatMessageRepository chatMessageRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private MinioStorageService minioStorageService;

    @InjectMocks
    private GuardChatService guardChatService;

    private User sender;
    private User receiver;

    @BeforeEach
    void setUp() {
        sender = User.builder()
                .id(UUID.randomUUID())
                .fullName("Guard An")
                .userCode("SEC-001")
                .email("guard.an@fpt.edu.vn")
                .role(Role.GUARD)
                .build();
        receiver = User.builder()
                .id(UUID.randomUUID())
                .fullName("Bao Ve Demo")
                .userCode("SEC-002")
                .email("guard.demo@fpt.edu.vn")
                .role(Role.GUARD)
                .build();
    }

    @Test
    void sendImageMessage_validPng_uploadsToMinioSavesAndBroadcasts() {
        byte[] pngBytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", pngBytes);

        when(userRepository.findByEmail(sender.getEmail())).thenReturn(Optional.of(sender));
        when(userRepository.findById(receiver.getId())).thenReturn(Optional.of(receiver));
        String mediaPath = "/api/media/chat-media/" + sender.getId() + "/img.png";
        when(minioStorageService.uploadChatImage(pngBytes, sender.getId(), "png", "image/png"))
                .thenReturn(mediaPath);

        UUID msgId = UUID.randomUUID();
        when(chatMessageRepository.save(any(GuardChatMessage.class))).thenAnswer(inv -> {
            GuardChatMessage msg = inv.getArgument(0);
            msg.setId(msgId);
            return msg;
        });

        GuardChatMessageDto dto = guardChatService.sendImageMessage(
                sender.getEmail(),
                receiver.getId(),
                "Check gate 2",
                file
        );

        assertEquals(msgId, dto.getId());
        assertEquals("Check gate 2", dto.getContent());
        assertEquals(mediaPath, dto.getImageUrl());
        verify(messagingTemplate).convertAndSend("/topic/guards/" + receiver.getId() + "/messages", dto);
        verify(messagingTemplate).convertAndSend("/topic/guards/" + sender.getId() + "/messages", dto);
    }

    @Test
    void sendImageMessage_invalidFileFormat_rejectsWithoutUploading() {
        MockMultipartFile textFile = new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes());

        when(userRepository.findByEmail(sender.getEmail())).thenReturn(Optional.of(sender));
        when(userRepository.findById(receiver.getId())).thenReturn(Optional.of(receiver));

        assertThrows(IllegalArgumentException.class, () ->
                guardChatService.sendImageMessage(sender.getEmail(), receiver.getId(), null, textFile));

        verify(minioStorageService, never()).uploadChatImage(any(), any(), any(), any());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    void sendMessage_emptyContentAndNoImage_throwsBadRequest() {
        SendChatMessageRequest req = SendChatMessageRequest.builder()
                .receiverId(receiver.getId())
                .content("   ")
                .build();

        assertThrows(IllegalArgumentException.class, () ->
                guardChatService.sendMessage(sender.getEmail(), req));
    }

    @Test
    void getGuardsForChat_imageOnlyMessage_showsImagePreviewLabel() {
        when(userRepository.findByEmail(sender.getEmail())).thenReturn(Optional.of(sender));
        when(userRepository.findActiveUsersByRoleIn(List.of(Role.GUARD, Role.FACILITY_MANAGER)))
                .thenReturn(List.of(sender, receiver));
        when(guardShiftRepository.findShifts(any(), any(), isNull(), isNull(), isNull())).thenReturn(List.of());
        when(chatMessageRepository.countUnreadBySender(sender.getId())).thenReturn(List.of());

        GuardChatMessage latest = GuardChatMessage.builder()
                .id(UUID.randomUUID())
                .sender(receiver)
                .receiver(sender)
                .content("")
                .imageUrl("/api/media/chat-media/x/y.jpg")
                .createdAt(OffsetDateTime.now())
                .build();
        when(chatMessageRepository.findLatestMessage(sender.getId(), receiver.getId())).thenReturn(latest);

        List<GuardChatMemberDto> list = guardChatService.getGuardsForChat(sender.getEmail(), null, null);
        assertEquals(1, list.size());
        assertEquals("[Hình ảnh]", list.get(0).getLastMessage());
    }

    @Test
    void getGuardsForChat_guardSeesFacilityManagerWithUnreadMessage() {
        User fm = User.builder()
                .id(UUID.randomUUID())
                .fullName("Trần Bình (Quản Lý CSVC)")
                .userCode("FM-001")
                .email("manager.binh@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .build();

        when(userRepository.findByEmail(sender.getEmail())).thenReturn(Optional.of(sender));
        when(userRepository.findActiveUsersByRoleIn(List.of(Role.GUARD, Role.FACILITY_MANAGER)))
                .thenReturn(List.of(sender, fm));
        when(guardShiftRepository.findShifts(any(), any(), isNull(), isNull(), isNull())).thenReturn(List.of());
        when(chatMessageRepository.countUnreadBySender(sender.getId()))
                .thenReturn(List.<Object[]>of(new Object[]{fm.getId(), 1L}));

        GuardChatMessage latest = GuardChatMessage.builder()
                .id(UUID.randomUUID())
                .sender(fm)
                .receiver(sender)
                .content("hello")
                .createdAt(OffsetDateTime.now())
                .build();
        when(chatMessageRepository.findLatestMessage(sender.getId(), fm.getId())).thenReturn(latest);

        List<GuardChatMemberDto> list = guardChatService.getGuardsForChat(sender.getEmail(), null, null);
        assertEquals(1, list.size());
        assertEquals(fm.getId(), list.get(0).getId());
        assertEquals("FACILITY_MANAGER", list.get(0).getRole());
        assertEquals(1L, list.get(0).getUnreadCount());
        assertEquals("hello", list.get(0).getLastMessage());
    }
}
