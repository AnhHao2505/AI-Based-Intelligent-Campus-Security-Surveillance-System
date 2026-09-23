package com.fa26se040.icss.dto.guard;

import com.fa26se040.icss.enums.ShiftType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuardTeamDispatchCreateRequest {
    @NotEmpty(message = "Danh sách bảo vệ điều động không được rỗng")
    private List<UUID> guardIds;

    @NotNull(message = "ID đội nhận tăng cường không được để trống")
    private UUID toTeamId;

    @NotNull(message = "Ngày bắt đầu tăng cường không được để trống")
    private LocalDate startDate;

    @NotNull(message = "Ngày kết thúc tăng cường không được để trống")
    private LocalDate endDate;

    private ShiftType shiftType;

    private String reason;
}
