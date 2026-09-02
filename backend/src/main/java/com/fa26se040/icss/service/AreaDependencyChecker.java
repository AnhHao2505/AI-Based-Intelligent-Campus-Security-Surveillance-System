package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaDependencyResponse;
import com.fa26se040.icss.dto.area.AreaDependencyResponse.Blocker;
import com.fa26se040.icss.exception.AreaErrorCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class AreaDependencyChecker {

    public AreaDependencyResponse check(UUID areaId, String areaCode) {
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
        String note = "Chưa có module nào tham chiếu tới khu vực. Kiểm tra sẽ được bổ sung ở M07, M08.";

        return new AreaDependencyResponse(areaId, areaCode, canDeactivate, blockers, warnings, note);
    }

    private int countAssignedCameras(UUID areaId) {
        // TODO M07 — đếm cameras đang gán vào khu vực
        return 0;
    }

    private int countActiveAccessPermissions(UUID areaId) {
        // TODO M08 — đếm access_permissions còn hiệu lực
        return 0;
    }
}
