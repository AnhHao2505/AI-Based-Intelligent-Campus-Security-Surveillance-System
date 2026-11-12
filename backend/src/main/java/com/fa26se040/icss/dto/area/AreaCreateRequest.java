package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.AreaLevel;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
public class AreaCreateRequest {
    // BE-09: độ dài 3–100 ký tự (sau chuẩn hoá) chỉ kiểm ở AreaValidator (ERR_AREA_005), không đặt giới hạn thứ hai ở DTO
    @NotBlank(message = "Tên khu vực không được để trống")
    private String name;

    @NotNull(message = "Cấp độ khu vực không được để trống")
    private AreaLevel areaLevel;

    @Size(max = 50, message = "Tên toà nhà tối đa 50 ký tự")
    private String building;

    @Size(max = 20, message = "Tầng tối đa 20 ký tự")
    private String floor;

    private UUID floorId;

    @NotNull(message = "Vĩ độ không được để trống")
    @DecimalMin(value = "-90.0", message = "Vĩ độ phải từ -90 đến 90")
    @DecimalMax(value = "90.0", message = "Vĩ độ phải từ -90 đến 90")
    private Double centerLatitude;

    @NotNull(message = "Kinh độ không được để trống")
    @DecimalMin(value = "-180.0", message = "Kinh độ phải từ -180 đến 180")
    @DecimalMax(value = "180.0", message = "Kinh độ phải từ -180 đến 180")
    private Double centerLongitude;
}
