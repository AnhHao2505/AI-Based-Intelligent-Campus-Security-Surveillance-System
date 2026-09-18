package com.fa26se040.icss.dto.guard;

import com.fa26se040.icss.enums.ShiftType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GuardShiftCreateRequest {

    @NotNull(message = "Bảo vệ không được để trống")
    private UUID guardId;

    @NotNull(message = "Ngày trực không được để trống")
    private LocalDate shiftDate;

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
