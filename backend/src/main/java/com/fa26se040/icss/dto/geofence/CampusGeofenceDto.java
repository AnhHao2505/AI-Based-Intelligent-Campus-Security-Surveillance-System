package com.fa26se040.icss.dto.geofence;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampusGeofenceDto {
    private String campusName;
    private String description;
    private PointDto center;
    private List<PointDto> polygon;
    private Double radiusMeters;
}
