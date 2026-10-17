package com.fa26se040.icss.dto.accesslog;

import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.enums.AccessResult;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Một dòng lịch sử ra vào của chính người đang đăng nhập (GET /api/access-history/my).
 * Dữ liệu cá nhân tối thiểu: không trả user_access_level, không trả mã / họ tên (là chính người gọi).
 */
public record MyAccessHistoryItem(
        UUID id,
        OffsetDateTime loggedAt,
        UUID areaId,
        String areaName,
        String cameraCode,
        AccessResult accessResult,
        String resultDescription,
        String reasonDetails
) {
    public static MyAccessHistoryItem from(CameraAccessLog log) {
        return new MyAccessHistoryItem(
                log.getId(),
                log.getLoggedAt(),
                log.getArea() != null ? log.getArea().getId() : null,
                log.getArea() != null ? log.getArea().getName() : null,
                log.getCameraCode(),
                log.getAccessResult(),
                log.getAccessResult() != null ? log.getAccessResult().getDescription() : null,
                log.getReasonDetails()
        );
    }
}
