package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.AreaLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AreaMapPinResponse {
    private UUID id;
    private String name;
    private AreaLevel areaLevel;
    private Integer areaAccessLevel;
    private String building;
    private String floor;
    private Double centerLatitude;
    private Double centerLongitude;
    private Boolean isActive;
}
