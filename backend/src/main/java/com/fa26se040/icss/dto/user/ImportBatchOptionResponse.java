package com.fa26se040.icss.dto.user;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Tóm tắt lô import cho ô lọc ở màn Cấp truy cập (FM, ADMIN). Không chứa tên / email người trong lô.
 * flow (NORMAL / STAFF) và fileName lấy từ audit IMPORT / USER_IMPORT_BATCH; lô không có audit đó thì null.
 */
public record ImportBatchOptionResponse(
        UUID importBatchId,
        OffsetDateTime createdAt,
        long activeCount,
        String flow,
        String fileName
) {}
