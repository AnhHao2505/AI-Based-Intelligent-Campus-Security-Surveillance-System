package com.fa26se040.icss.dto.camera;

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
    private String message;
}
