package com.fa26se040.icss.dto.building;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FloorDto {
    private UUID id;
    private UUID buildingId;
    private String buildingCode;
    private String buildingName;
    private String floorCode;
    private String name;
    private Integer floorOrder;
    private String imageKey;
    private Integer originalWidth;
    private Integer originalHeight;
    private Boolean isActive;
    private int areaCount;
}
