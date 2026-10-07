package com.fa26se040.icss.dto.area;

/**
 * Step 6 (BR-AD-07): body của POST /api/areas/{id}/restore.
 * Lý do sai -> ERR_AREA_050, thiếu version -> ERR_AREA_044 (kiểm trong service).
 */
public record AreaRestoreRequest(
        String reason,
        Long version
) {
}
