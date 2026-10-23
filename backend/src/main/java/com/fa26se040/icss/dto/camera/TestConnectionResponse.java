package com.fa26se040.icss.dto.camera;

import com.fa26se040.icss.enums.OperationalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TestConnectionResponse {
    private boolean success;
    private Long latencyMs;
    private String errorCode;
    private String message;
    private OperationalStatus operationalStatus;
}
