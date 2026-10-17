package com.fa26se040.icss.dto.accesslog;

import com.fa26se040.icss.entity.CameraAccessLog;
import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.AccessSource;
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
public class CameraAccessLogResponse {
    private UUID id;
    private String cameraCode;
    private UUID areaId;
    private String areaName;
    private String userCode;
    private String fullName;
    private Integer userAccessLevel;
    private AccessResult accessResult;
    private String resultDescription;
    private String colorCode;
    private AccessSource accessSource;
    private UUID sourceReferenceId;
    private String reasonDetails;
    private OffsetDateTime loggedAt;

    public static CameraAccessLogResponse from(CameraAccessLog log) {
        if (log == null) {
            return null;
        }
        return CameraAccessLogResponse.builder()
                .id(log.getId())
                .cameraCode(log.getCameraCode())
                .areaId(log.getArea() != null ? log.getArea().getId() : null)
                .areaName(log.getArea() != null ? log.getArea().getName() : null)
                .userCode(log.getUserCode())
                .fullName(log.getFullName())
                .userAccessLevel(log.getUserAccessLevel())
                .accessResult(log.getAccessResult())
                .resultDescription(log.getAccessResult() != null ? log.getAccessResult().getDescription() : null)
                .colorCode(log.getAccessResult() != null ? log.getAccessResult().getColorCode() : null)
                .accessSource(log.getAccessSource())
                .sourceReferenceId(log.getSourceReferenceId())
                .reasonDetails(log.getReasonDetails())
                .loggedAt(log.getLoggedAt())
                .build();
    }
}
