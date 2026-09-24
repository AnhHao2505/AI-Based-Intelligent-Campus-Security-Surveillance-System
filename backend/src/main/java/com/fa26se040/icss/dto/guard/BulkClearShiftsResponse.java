package com.fa26se040.icss.dto.guard;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BulkClearShiftsResponse {
    private int clearedCount;
    private String message;
}
