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
public class GuardChatMemberDto {
    private UUID id;
    private String fullName;
    private String userCode;
    private String email;
    private String phoneNumber;
    private String teamName;
    private boolean onDuty;
    private String shiftName;
    private String buildingName;
    private String dutyPostCode;
    private long unreadCount;
    private String lastMessage;
    private OffsetDateTime lastMessageAt;
}

