package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.repository.OnDutyGuardQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chọn người nhận cảnh báo sự cố (BR-IE-01 / BR-IE-02). Không dùng geofence / GuardLocation.
 */
@Service
@RequiredArgsConstructor
public class IncidentRecipientResolver {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final OnDutyGuardQueryRepository onDutyGuardQueryRepository;

    /**
     * BR-IE-02: bảo vệ đang trực = có ca CHECKED_IN mà {@code now} (giờ Việt Nam) nằm trong [start, end).
     * Ca qua đêm (end &lt; start) kết thúc vào ngày hôm sau, nên chỉ cần xét ca của hôm nay và hôm qua.
     * Ca CHECKED_IN đã quá giờ kết thúc (quên trả ca) bị loại. Nhiều ca khớp -> mỗi bảo vệ một lần, giữ thứ tự ca.
     */
    public List<User> findOnDutyGuards(OffsetDateTime now) {
        LocalDateTime nowLocal = now.atZoneSameInstant(VN_ZONE).toLocalDateTime();
        LocalDate today = nowLocal.toLocalDate();

        List<GuardShift> shifts = onDutyGuardQueryRepository.findByStatusAndShiftDateBetween(
                ShiftStatus.CHECKED_IN, today.minusDays(1), today);

        Map<UUID, User> guards = new LinkedHashMap<>();
        for (GuardShift shift : shifts) {
            User guard = shift.getGuard();
            if (guard == null || guard.getId() == null || !isWithinShift(shift, nowLocal)) {
                continue;
            }
            guards.putIfAbsent(guard.getId(), guard);
        }
        return new ArrayList<>(guards.values());
    }

    /** [shiftDate start, end); end &lt; start -> end thuộc ngày hôm sau. */
    static boolean isWithinShift(GuardShift shift, LocalDateTime nowLocal) {
        if (shift.getShiftDate() == null || shift.getStartTime() == null || shift.getEndTime() == null) {
            return false;
        }
        LocalDateTime start = shift.getShiftDate().atTime(shift.getStartTime());
        LocalDate endDate = shift.getEndTime().isBefore(shift.getStartTime())
                ? shift.getShiftDate().plusDays(1)
                : shift.getShiftDate();
        LocalDateTime end = endDate.atTime(shift.getEndTime());
        return !nowLocal.isBefore(start) && nowLocal.isBefore(end);
    }
}
