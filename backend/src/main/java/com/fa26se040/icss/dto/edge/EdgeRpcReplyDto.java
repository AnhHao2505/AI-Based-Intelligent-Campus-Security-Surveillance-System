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
public class EdgeRpcReplyDto {
    private String correlationId;
    private boolean success;
    private String errorCode;
    private String errorMessage;
    private Long latencyMs;
    private Map<String, Object> data;
}
