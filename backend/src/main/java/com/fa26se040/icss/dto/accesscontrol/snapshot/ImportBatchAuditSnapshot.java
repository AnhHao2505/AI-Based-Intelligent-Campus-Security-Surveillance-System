package com.fa26se040.icss.dto.accesscontrol.snapshot;

import java.util.List;

/**
 * PR-AUDIT: audit USER_IMPORT_BATCH. IMPORT: tên file, luồng NORMAL / STAFF, tổng / thành công / thất bại.
 * DELETE: danh sách mã bị gỡ. RESTORE: số khôi phục / bỏ qua và mã đã khôi phục.
 */
public record ImportBatchAuditSnapshot(
        String flow,
        String fileName,
        Integer totalRows,
        Integer successCount,
        Integer failureCount,
        Integer affectedCount,
        Integer skippedCount,
        List<String> userCodes
) implements AuditSnapshot {

    public static ImportBatchAuditSnapshot imported(String flow, String fileName, int total, int success, int failure) {
        return new ImportBatchAuditSnapshot(flow, fileName, total, success, failure, null, null, null);
    }

    public static ImportBatchAuditSnapshot deleted(int affected, List<String> userCodes) {
        return new ImportBatchAuditSnapshot(null, null, null, null, null, affected, null, userCodes);
    }

    public static ImportBatchAuditSnapshot restored(int restored, int skipped, List<String> userCodes) {
        return new ImportBatchAuditSnapshot(null, null, null, null, null, restored, skipped, userCodes);
    }
}
