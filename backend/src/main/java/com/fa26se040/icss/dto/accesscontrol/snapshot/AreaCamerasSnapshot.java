package com.fa26se040.icss.dto.accesscontrol.snapshot;

import java.util.List;
import java.util.UUID;

public record AreaCamerasSnapshot(
        List<UUID> cameraIds
) implements AuditSnapshot {

    public static AreaCamerasSnapshot from(List<UUID> cameraIds) {
        return new AreaCamerasSnapshot(
                cameraIds != null ? List.copyOf(cameraIds) : List.of()
        );
    }
}
