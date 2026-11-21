package com.fa26se040.icss.dto.camera;

import com.fa26se040.icss.enums.CameraType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateCameraRequest {

    @Size(max = 50, message = "Mã camera không được vượt quá 50 ký tự")
    private String cameraCode;

    @NotBlank(message = "Tên camera không được để trống")
    @Size(max = 100, message = "Tên camera không được vượt quá 100 ký tự")
    private String name;

    private CameraType cameraType;

    private UUID areaId;

    private Double mapLongitude;
    private Double mapLatitude;
    private String mapBuilding;
    private Integer mapFloorOrder;

    @JsonIgnore
    @AssertTrue(message = "Camera phải được gán vào một khu vực")
    public boolean isAreaAssignmentValid() {
        return cameraType == CameraType.CORRIDOR || areaId != null;
    }
}
