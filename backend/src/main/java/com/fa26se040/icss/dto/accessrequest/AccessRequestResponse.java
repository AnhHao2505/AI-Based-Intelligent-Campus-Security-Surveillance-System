package com.fa26se040.icss.dto.accessrequest;

import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AccessRequestResponse(
    UUID id,
    UUID areaId,
    String areaCode,
    String areaName,
    AreaLevel areaLevel,
    String building,
    String floor,
    UUID requesterId,
    String requesterName,
    String requesterCode,
    String requesterEmail,
    RequestType requestType,
    String purpose,
    OffsetDateTime startTime,
    OffsetDateTime endTime,
    RequestStatus status,
    UUID reviewerId,
    String reviewerName,
    String reviewerEmail,
    OffsetDateTime reviewedAt,
    String rejectionReason,
    List<MemberInfo> members,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}
