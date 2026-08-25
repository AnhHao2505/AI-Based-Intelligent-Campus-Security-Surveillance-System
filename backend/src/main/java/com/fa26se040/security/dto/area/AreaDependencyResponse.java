package com.fa26se040.security.dto.area;

import com.fa26se040.security.exception.AreaErrorCode;
import java.util.List;

public record AreaDependencyResponse(
    Integer areaId,
    String areaCode,
    Boolean canDeactivate,
    List<Blocker> blockers,
    List<String> warnings,
    String note
) {
    public record Blocker(AreaErrorCode errorCode, Object count, String message) {}
}
