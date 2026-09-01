package com.fa26se040.icss.dto.camera;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import com.fa26se040.icss.entity.OperationalStatus;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CameraHealthLogResponse {
    private UUID id;
    private OperationalStatus status;
    private OffsetDateTime checkedAt;
    private Integer latencyMs;
    private BigDecimal fps;
    private String errorCode;
    private String errorMessage;
}
