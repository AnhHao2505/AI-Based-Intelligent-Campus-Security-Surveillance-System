package com.fa26se040.icss.dto.notification;

import com.fa26se040.icss.enums.NotificationType;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String message,
        UUID referenceId,
        String referenceType,
        @JsonProperty("isRead") Boolean isRead,
        OffsetDateTime createdAt
) {}
