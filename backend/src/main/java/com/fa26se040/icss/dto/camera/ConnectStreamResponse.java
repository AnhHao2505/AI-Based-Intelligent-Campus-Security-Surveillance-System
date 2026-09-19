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
public class ConnectStreamResponse {
    private boolean success;
    private String snapshotBase64;
    private Integer width;
    private Integer height;
    private Long latencyMs;
    private OperationalStatus operationalStatus;
    private String errorMessage;
}
