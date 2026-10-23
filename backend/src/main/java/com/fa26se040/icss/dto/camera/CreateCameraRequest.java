package com.fa26se040.icss.dto.camera;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    @NotNull(message = "Camera phải được gán vào một khu vực")
    private UUID areaId;
}
