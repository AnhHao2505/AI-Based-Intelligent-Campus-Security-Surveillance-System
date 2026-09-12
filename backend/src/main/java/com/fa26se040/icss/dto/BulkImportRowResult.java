package com.fa26se040.icss.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BulkImportRowResult {
    private int rowIndex;
    private String userCode;
    private String fullName;
    private String email;
    private String role;
    private String status;
    private String errorMessage;
}
