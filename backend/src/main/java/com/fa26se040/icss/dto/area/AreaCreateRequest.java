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
public class AreaCreateRequest {
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

    public AreaCreateRequest(String name, AreaLevel areaLevel, String building, String floor) {
        this(name, areaLevel, building, floor, null);
    }
}
