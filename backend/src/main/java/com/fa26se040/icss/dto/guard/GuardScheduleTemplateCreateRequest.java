package com.fa26se040.icss.dto.guard;

import com.fa26se040.icss.enums.ShiftType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardScheduleTemplateCreateRequest {

    @NotNull(message = "Bảo vệ không được để trống")
    private UUID guardId;

    @NotNull(message = "Thứ trong tuần không được để trống")
    @Min(value = 1, message = "Thứ trong tuần từ 1 (Chủ nhật) đến 7 (Thứ 7)")
    @Max(value = 7, message = "Thứ trong tuần từ 1 (Chủ nhật) đến 7 (Thứ 7)")
    private Integer dayOfWeek;

    @NotNull(message = "Loại ca không được để trống")
    private ShiftType shiftType;

    @NotNull(message = "Giờ bắt đầu không được để trống")
    private LocalTime startTime;

    @NotNull(message = "Giờ kết thúc không được để trống")
    private LocalTime endTime;

    private UUID areaId;
    private String radioChannel;
    private String notes;
}
