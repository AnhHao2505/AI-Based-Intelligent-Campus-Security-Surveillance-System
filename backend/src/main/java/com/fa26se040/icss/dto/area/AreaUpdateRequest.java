package com.fa26se040.icss.dto.area;

import com.fa26se040.icss.enums.AreaLevel;
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
public class AreaUpdateRequest {
    @NotBlank(message = "Tên khu vực không được để trống")
    @Size(max = 150, message = "Tên khu vực tối đa 150 ký tự")
    private String name;

    @NotNull(message = "Cấp độ khu vực không được để trống")
    private AreaLevel areaLevel;

    @Size(max = 50, message = "Tên toà nhà tối đa 50 ký tự")
    private String building;

    @Size(max = 20, message = "Tầng tối đa 20 ký tự")
    private String floor;

    private UUID floorId;

    @NotNull(message = "Vĩ độ không được để trống")
    @jakarta.validation.constraints.DecimalMin(value = "-90.0", message = "Vĩ độ phải từ -90 đến 90")
    @jakarta.validation.constraints.DecimalMax(value = "90.0", message = "Vĩ độ phải từ -90 đến 90")
    private Double centerLatitude;

    @NotNull(message = "Kinh độ không được để trống")
    @jakarta.validation.constraints.DecimalMin(value = "-180.0", message = "Kinh độ phải từ -180 đến 180")
    @jakarta.validation.constraints.DecimalMax(value = "180.0", message = "Kinh độ phải từ -180 đến 180")
    private Double centerLongitude;

    // Step 5b (BR-TC-02): lý do đổi loại khu vực (10–500 ký tự, chỉ bắt buộc khi đổi loại)
    private String reason;

    // Step 5b (BR-TC-13): version của khu vực mà client đang xem
    private Long version;
}
