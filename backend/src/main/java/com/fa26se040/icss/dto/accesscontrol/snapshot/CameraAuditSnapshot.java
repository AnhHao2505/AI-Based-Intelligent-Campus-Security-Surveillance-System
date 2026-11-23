package com.fa26se040.icss.dto.accesscontrol.snapshot;

import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.CameraType;
import com.fa26se040.icss.enums.OperationalStatus;

import java.util.UUID;

public record CameraAuditSnapshot(
        UUID id,
        String cameraCode,
        String name,
        CameraType cameraType,
        CameraStatus status,
        OperationalStatus operationalStatus,
        UUID areaId,
        boolean deleted
) implements AuditSnapshot {

    public static CameraAuditSnapshot from(Camera camera) {
        if (camera == null) {
            return null;
        }
        return new CameraAuditSnapshot(
                camera.getId(),
                camera.getCameraCode(),
                camera.getName(),
                camera.getCameraType(),
                camera.getStatus(),
                camera.getOperationalStatus(),
                camera.getArea() != null ? camera.getArea().getId() : null,
                camera.getDeletedAt() != null
        );
    }
}
