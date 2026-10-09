package com.fa26se040.icss.dto.accessrequest;

import com.fa26se040.icss.enums.AreaLevel;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

public record AreaSimpleResponse(
    UUID id,
    String name,
    AreaLevel areaLevel,
    String building,
    String floor,
    // A-06: chỉ điền ở available-areas — khu vực này có nhận đơn nhóm không (theo ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE).
    // Nơi khác (camera, khách) để null và không trả ra.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    Boolean groupRequestAllowed
) {
    public AreaSimpleResponse(UUID id, String name, AreaLevel areaLevel, String building, String floor) {
        this(id, name, areaLevel, building, floor, null);
    }
}
