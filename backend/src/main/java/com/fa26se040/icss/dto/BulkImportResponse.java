package com.fa26se040.icss.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkImportResponse {
    private int totalRows;
    private int successCount;
    private int failureCount;
    private List<BulkImportRowResult> results;
}
