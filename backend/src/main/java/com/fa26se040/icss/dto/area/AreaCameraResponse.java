package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.dto.camera.CameraSimpleResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AreaCameraResponse {
    private UUID areaId;
    private String areaCode;
    private String areaName;
    private List<CameraSimpleResponse> cameras;
}
