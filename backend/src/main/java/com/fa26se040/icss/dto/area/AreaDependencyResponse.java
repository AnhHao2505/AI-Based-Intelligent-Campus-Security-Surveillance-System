package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.exception.AreaErrorCode;
import java.util.List;
import java.util.UUID;

public record AreaDependencyResponse(
    UUID areaId,
    String areaCode,
    Boolean canDeactivate,
    List<Blocker> blockers,
    List<String> warnings,
    String note
) {
    public record Blocker(AreaErrorCode errorCode, Object count, String message) {}
}
