package com.fa26se040.icss.dto.edge;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EdgeRpcRequestDto {
    private String correlationId;
    private String action;
    private String cameraCode;
    private Map<String, Object> payload;
    private Long timestamp;
}
