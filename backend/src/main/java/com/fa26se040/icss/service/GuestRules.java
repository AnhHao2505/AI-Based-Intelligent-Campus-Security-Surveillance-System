package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.GuestVisitRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Quy tắc dùng chung cho lượt khách: tạo (BR-GV-01, 04, 06), duyệt lại (BR-GV-11) và quyết định cho vào (BR-GV-20).
 * Nguồn quyền của host chỉ gồm ACCESS_LEVEL và ASSIGNED_PERSONNEL, cùng điều kiện với AccessDecisionService#checkEntry
 * (không gọi / không sửa checkEntry vì hàm đó xét một thời điểm, còn đây phải phủ trọn khung giờ).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GuestRules {

    /** B-04 (Lucas 08/10): ADMIN không làm host khách — chỉ NORMAL_USER và FACILITY_MANAGER mời khách. */
    public static final Set<Role> HOST_ROLES = Set.of(Role.NORMAL_USER, Role.FACILITY_MANAGER);
    /** BR-GV-04 (V83): chỉ khu Bảo mật nhận khách; Công khai không cần, Bảo mật cao bị chặn. */
    public static final Set<AreaLevel> GUEST_AREA_LEVELS = Set.of(AreaLevel.CONFIDENTIAL);

    private final SystemConfigService systemConfigService;
    private final GuestVisitRepository guestVisitRepository;

    public int hostMinLevel() {
        return systemConfigService.getInt(ConfigKey.GUEST_HOST_MIN_LEVEL);
    }

    /** Độ dài cột guest_visits.purpose (VARCHAR(500), V60) — GUEST_PURPOSE_MAX_LENGTH không được vượt. */
    public static final int PURPOSE_STORAGE_MAX_LENGTH = 500;

    /**
     * BR-GV-03 (N1, V120): độ dài mục đích đọc từ cấu hình. Giá trị sửa thẳng trong DB lọt khỏi min_value / max_value
     * hoặc min ≥ max -> WARN + mặc định của ConfigKey (10 / 500), không để form khách bị khoá hẳn.
     */
    public com.fa26se040.icss.dto.guest.GuestPurposeLimits purposeLimits() {
        int min = systemConfigService.getIntWithinBounds(ConfigKey.GUEST_PURPOSE_MIN_LENGTH);
        int max = systemConfigService.getIntWithinBounds(ConfigKey.GUEST_PURPOSE_MAX_LENGTH);
        if (min >= max || max > PURPOSE_STORAGE_MAX_LENGTH) {
            int defMin = Integer.parseInt(ConfigKey.GUEST_PURPOSE_MIN_LENGTH.getDefaultValue());
            int defMax = Integer.parseInt(ConfigKey.GUEST_PURPOSE_MAX_LENGTH.getDefaultValue());
            log.warn("Config GUEST_PURPOSE_MIN_LENGTH [{}] / GUEST_PURPOSE_MAX_LENGTH [{}] invalid. Using defaults: {} / {}",
                    min, max, defMin, defMax);
            return new com.fa26se040.icss.dto.guest.GuestPurposeLimits(defMin, defMax);
        }
        return new com.fa26se040.icss.dto.guest.GuestPurposeLimits(min, max);
    }

    /** BR-GV-01: đang hoạt động, chưa xoá mềm, role được mời khách, cấp ≥ GUEST_HOST_MIN_LEVEL. */
    public boolean hostEligible(User host) {
        return host != null
                && Boolean.TRUE.equals(host.getIsActive())
                && host.getDeletedAt() == null
                && HOST_ROLES.contains(host.getRole())
                && host.getAccessLevel() != null
                && host.getAccessLevel() >= hostMinLevel();
    }

    public boolean areaActive(Area area) {
        return area != null && Boolean.TRUE.equals(area.getIsActive()) && area.getDeletedAt() == null;
    }

    /** BR-GV-04: chỉ khu Bảo mật nhận khách. */
    public boolean areaTypeAllowed(Area area) {
        return area != null && GUEST_AREA_LEVELS.contains(area.getAreaLevel());
    }

    /**
     * BR-GV-06: host vào được khu vực trong TRỌN [from, to) bằng ACCESS_LEVEL (cấp host ≥ cấp của loại khu vực)
     * hoặc AP chưa thu hồi phủ trọn khung. Không tính OPEN_EVENT và đơn truy cập.
     */
    public boolean hostCoversArea(User host, Area area, OffsetDateTime from, OffsetDateTime to) {
        if (host.getAccessLevel() != null && area.getAreaAccessLevel() != null
                && host.getAccessLevel() >= area.getAreaAccessLevel()) {
            return true;
        }
        return guestVisitRepository.existsAssignedPersonnelCovering(host.getId(), area.getId(), from, to);
    }
}
