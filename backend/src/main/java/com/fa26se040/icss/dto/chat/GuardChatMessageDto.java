package com.fa26se040.icss.dto.chat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardChatMessageDto {
    private UUID id;
    private UUID senderId;
    private String senderName;
    private String senderCode;
    private UUID receiverId;
    private String receiverName;
    private String receiverCode;
    private String content;
    private boolean isRead;
    private OffsetDateTime createdAt;
}

