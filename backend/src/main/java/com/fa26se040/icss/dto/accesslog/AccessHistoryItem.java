package com.fa26se040.icss.dto.accesslog;

import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.enums.AccessResult;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Một dòng lịch sử ra vào toàn hệ thống (GET /api/access-history, FACILITY_MANAGER / ADMIN).
 * Chỉ thêm mã + họ tên người so với MyAccessHistoryItem; không trả user_access_level hay trường cá nhân khác.
 */
public record AccessHistoryItem(
        UUID id,
        OffsetDateTime loggedAt,
        UUID areaId,
        String areaName,
        String cameraCode,
        String userCode,
        String fullName,
        AccessResult accessResult,
        String resultDescription,
        String reasonDetails
) {
    public static AccessHistoryItem from(CameraAccessLog log) {
        return new AccessHistoryItem(
                log.getId(),
                log.getLoggedAt(),
                log.getArea() != null ? log.getArea().getId() : null,
                log.getArea() != null ? log.getArea().getName() : null,
                log.getCameraCode(),
                log.getUserCode(),
                log.getFullName(),
                log.getAccessResult(),
                log.getAccessResult() != null ? log.getAccessResult().getDescription() : null,
                log.getReasonDetails()
        );
    }
}
