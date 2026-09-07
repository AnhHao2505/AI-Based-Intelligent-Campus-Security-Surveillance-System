package com.fa26se040.icss.dto.ai;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AiConfigRequest {

    @NotNull(message = "Ngưỡng khớp khuôn mặt không được để trống")
    @DecimalMin(value = "0.00", message = "Ngưỡng khớp khuôn mặt tối thiểu là 0.00")
    @DecimalMax(value = "1.00", message = "Ngưỡng khớp khuôn mặt tối đa là 1.00")
    private BigDecimal faceMatchThreshold;

    @NotNull(message = "Số FPS suy luận không được để trống")
    @Min(value = 1, message = "Số FPS suy luận tối thiểu là 1")
    @Max(value = 60, message = "Số FPS suy luận tối đa là 60")
    private Integer inferenceFps;
}
