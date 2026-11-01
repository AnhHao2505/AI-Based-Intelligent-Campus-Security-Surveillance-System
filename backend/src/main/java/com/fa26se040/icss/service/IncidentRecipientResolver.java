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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Chọn người nhận cảnh báo sự cố theo ca trực (BR-IE-01 / BR-IE-02).
 */
@Service
@RequiredArgsConstructor
public class IncidentRecipientResolver {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final OnDutyGuardQueryRepository onDutyGuardQueryRepository;

    /**
     * BR-IE-02: bảo vệ đang trực = có ca CHECKED_IN mà {@code now} (giờ Việt Nam) nằm trong [start, end).
     * Ca qua đêm (end &lt; start) kết thúc vào ngày hôm sau, nên chỉ cần xét ca của hôm nay và hôm qua.
     * Ca CHECKED_IN đã quá giờ kết thúc (quên trả ca) bị loại. Tài khoản bị vô hiệu hoá hoặc đã xoá mềm bị loại
     * dù còn ca CHECKED_IN. Nhiều ca khớp -> mỗi bảo vệ một lần, giữ thứ tự ca.
     */
    public List<User> findOnDutyGuards(OffsetDateTime now) {
        LocalDateTime nowLocal = now.atZoneSameInstant(VN_ZONE).toLocalDateTime();
        LocalDate today = nowLocal.toLocalDate();

        List<GuardShift> shifts = onDutyGuardQueryRepository.findByStatusAndShiftDateBetween(
                ShiftStatus.CHECKED_IN, today.minusDays(1), today);

        Map<UUID, User> guards = new LinkedHashMap<>();
        for (GuardShift shift : shifts) {
            User guard = shift.getGuard();
            if (guard == null || guard.getId() == null || !isActiveAccount(guard) || !isWithinShift(shift, nowLocal)) {
                continue;
            }
            guards.putIfAbsent(guard.getId(), guard);
        }
        return new ArrayList<>(guards.values());
    }

    /**
     * V93 (BR-DSP-02, BR-DSP-13, BR-GB-07): ứng viên bước 1 của một sự cố. Không đổi {@link #findOnDutyGuards}.
     *
     * <p>Bắt đầu từ cùng tập ca như {@link #findOnDutyGuards} (CHECKED_IN, trong khung giờ, tài khoản hoạt động), rồi giữ
     * bảo vệ: ca có toà và toà của ca = {@code incidentBuildingId} (ca không có toà -> không vào bước 1) · có phiên STOMP
     * từ mobile · không nằm trong {@code excludedGuardIds} (đang giữ sự cố CLAIMED, đang có lời mời chờ) · khác người bị
     * nhận diện trong sự cố ({@code subjectUserCode}, so không phân biệt hoa thường).</p>
     *
     * <p>Vị trí trực (E11): trong số còn lại, chỉ giữ người có vị trí trực thuộc {@code dispatchablePostCodes}; nếu không
     * còn ai thì bỏ điều kiện vị trí. Vị trí trực đọc qua {@link #currentDutyPost}. Thứ tự giữ theo ca; xếp hạng ở
     * IncidentDispatchService.</p>
     *
     * @param incidentBuildingId toà của khu vực xảy ra sự cố; null -> không có ứng viên
     */
    public List<User> findDispatchCandidates(OffsetDateTime now, UUID incidentBuildingId, String subjectUserCode,
                                             Set<UUID> excludedGuardIds, Predicate<UUID> hasMobileSession,
                                             Set<String> dispatchablePostCodes) {
        if (incidentBuildingId == null) {
            return List.of();
        }
        LocalDateTime nowLocal = now.atZoneSameInstant(VN_ZONE).toLocalDateTime();
        LocalDate today = nowLocal.toLocalDate();
        String subject = subjectUserCode == null || subjectUserCode.isBlank()
                ? null : subjectUserCode.trim().toUpperCase(Locale.ROOT);

        List<GuardShift> shifts = onDutyGuardQueryRepository.findByStatusAndShiftDateBetween(
                ShiftStatus.CHECKED_IN, today.minusDays(1), today);

        Map<UUID, User> eligible = new LinkedHashMap<>();
        Map<UUID, User> atDispatchablePost = new LinkedHashMap<>();
        for (GuardShift shift : shifts) {
            User guard = shift.getGuard();
            if (guard == null || guard.getId() == null || !isActiveAccount(guard) || !isWithinShift(shift, nowLocal)) {
                continue;
            }
            if (shift.getBuilding() == null || !incidentBuildingId.equals(shift.getBuilding().getId())) {
                continue;
            }
            if (excludedGuardIds != null && excludedGuardIds.contains(guard.getId())) {
                continue;
            }
            if (subject != null && guard.getUserCode() != null
                    && subject.equals(guard.getUserCode().trim().toUpperCase(Locale.ROOT))) {
                continue;
            }
            if (hasMobileSession == null || !hasMobileSession.test(guard.getId())) {
                continue;
            }
            eligible.putIfAbsent(guard.getId(), guard);
            String post = currentDutyPost(shift);
            if (dispatchablePostCodes != null && post != null && dispatchablePostCodes.contains(post)) {
                atDispatchablePost.putIfAbsent(guard.getId(), guard);
            }
        }
        return new ArrayList<>(atDispatchablePost.isEmpty() ? eligible.values() : atDispatchablePost.values());
    }

    /**
     * K10-a: vị trí trực hiện tại của bảo vệ = vị trí của ca đang trực. Mọi chỗ đọc vị trí trực đi qua method này để sau
     * nâng lên phương án bảo vệ tự đổi vị trí trong ca mà không sửa chỗ gọi.
     */
    static String currentDutyPost(GuardShift shift) {
        return shift.getDutyPostCode();
    }

    /** isActive = true và chưa xoá mềm (deletedAt IS NULL). */
    static boolean isActiveAccount(User guard) {
        return Boolean.TRUE.equals(guard.getIsActive()) && guard.getDeletedAt() == null;
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
