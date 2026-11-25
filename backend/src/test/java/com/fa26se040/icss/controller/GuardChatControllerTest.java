package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.chat.GuardChatMessageDto;
import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.service.GuardChatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class GuardChatControllerTest {

    @Mock
    private GuardChatService guardChatService;

    @InjectMocks
    private GuardChatController guardChatController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(guardChatController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void sendImageMessage_multipartRequest_returnsCreatedDtoWithImageUrl() throws Exception {
        UUID receiverId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        UUID senderId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "gate.jpg",
                "image/jpeg",
                new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}
        );

        GuardChatMessageDto responseDto = GuardChatMessageDto.builder()
                .id(messageId)
                .senderId(senderId)
                .receiverId(receiverId)
                .content("Gate A")
                .imageUrl("/api/media/chat-media/" + senderId + "/gate.jpg")
                .isRead(false)
                .createdAt(OffsetDateTime.now())
                .build();

        when(guardChatService.sendImageMessage(eq("guard.an@fpt.edu.vn"), eq(receiverId), eq("Gate A"), any()))
                .thenReturn(responseDto);

        Authentication auth = new UsernamePasswordAuthenticationToken("guard.an@fpt.edu.vn", "n/a");

        mockMvc.perform(multipart("/api/guard-chat/messages/image")
                        .file(file)
                        .param("receiverId", receiverId.toString())
                        .param("content", "Gate A")
                        .principal(auth))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(messageId.toString()))
                .andExpect(jsonPath("$.data.imageUrl").value("/api/media/chat-media/" + senderId + "/gate.jpg"))
                .andExpect(jsonPath("$.data.content").value("Gate A"));

        verify(guardChatService).sendImageMessage(eq("guard.an@fpt.edu.vn"), eq(receiverId), eq("Gate A"), any());
    }
}
