package com.fa26se040.icss.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkImportResponseDto {
    private int totalProcessed;
    private int successCount;
    private int failedCount;
    private List<String> importedCodes;
    private List<String> errorMessages;
}
