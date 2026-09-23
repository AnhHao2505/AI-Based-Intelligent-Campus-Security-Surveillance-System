package com.fa26se040.icss.dto.building;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BuildingDto {
    private UUID id;
    private String code;
    private String name;
    private String description;
    private Integer totalFloors;
    private Boolean isActive;
    @Builder.Default
    private List<FloorDto> floors = new ArrayList<>();
}
