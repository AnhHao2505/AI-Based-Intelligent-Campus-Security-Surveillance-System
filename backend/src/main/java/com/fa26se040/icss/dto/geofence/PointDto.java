package com.fa26se040.icss.dto.geofence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PointDto {
    private Double latitude;
    private Double longitude;
}
