package com.fa26se040.icss.dto.geofence;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CampusGeofenceUpdateRequest {

    @NotBlank(message = "Tên khuôn viên không được để trống")
    private String campusName;

    private String description;

    private PointDto center;

    @NotEmpty(message = "Đa giác ranh giới Geofence phải có ít nhất 3 điểm")
    @Size(min = 3, message = "Đa giác ranh giới Geofence phải có ít nhất 3 điểm")
    private List<@Valid PointDto> polygon;
}
