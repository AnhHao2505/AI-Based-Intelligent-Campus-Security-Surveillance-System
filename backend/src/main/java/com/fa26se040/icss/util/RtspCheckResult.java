package com.fa26se040.icss.util;

import com.fa26se040.icss.enums.RtspConnectionErrorCode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RtspCheckResult {
    private boolean reachable;
    private long latencyMs;
    private int statusCode;
    private String statusText;
    private RtspConnectionErrorCode errorCode;
    private String errorMessage;
}
