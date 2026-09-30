package com.fa26se040.icss.dto.area;

/**
 * Step 6 (BR-AD-01): body của POST /api/areas/{id}/deactivate.
 * Không gắn annotation validate: lý do sai -> ERR_AREA_050, thiếu version -> ERR_AREA_044 (kiểm trong service,
 * cùng quy ước với đổi loại khu vực của 5b).
 */
public record AreaDeactivateRequest(
        String reason,
        Long version
) {
}
