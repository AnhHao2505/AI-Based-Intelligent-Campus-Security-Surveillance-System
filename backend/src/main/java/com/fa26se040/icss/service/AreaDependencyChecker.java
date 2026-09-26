package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaDependencyResponse;
import com.fa26se040.icss.dto.area.AreaDependencyResponse.Blocker;
import com.fa26se040.icss.exception.AreaErrorCode;
import com.fa26se040.icss.repository.CameraRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AreaDependencyChecker {

    private final CameraRepository cameraRepository;

    public AreaDependencyResponse check(UUID areaId) {
        List<Blocker> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        int cameras = countAssignedCameras(areaId);
        if (cameras > 0) {
            blockers.add(new Blocker(AreaErrorCode.ERR_AREA_009, cameras, "Không thể ngừng: còn " + cameras + " camera đang gán"));
        }

        int permissions = countActiveAccessPermissions(areaId);
        if (permissions > 0) {
            blockers.add(new Blocker(AreaErrorCode.ERR_AREA_010, permissions, "Không thể ngừng: còn " + permissions + " quyền truy cập"));
        }

        boolean canDeactivate = blockers.isEmpty();
        String note = (cameras > 0 || permissions > 0)
                ? "Có tài nguyên phụ thuộc đang liên kết với khu vực này."
                : "Không có tài nguyên phụ thuộc nào đang liên kết với khu vực này.";

        return new AreaDependencyResponse(areaId, canDeactivate, blockers, warnings, note);
    }

    private int countAssignedCameras(UUID areaId) {
        return cameraRepository.countByAreaIdAndDeletedAtIsNull(areaId);
    }

    private int countActiveAccessPermissions(UUID areaId) {
        // TODO M08 — đếm access_permissions còn hiệu lực
        return 0;
    }
}
