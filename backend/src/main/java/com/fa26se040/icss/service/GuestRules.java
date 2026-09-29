package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.GuestVisitRepository;
import lombok.RequiredArgsConstructor;
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
public class GuestRules {

    public static final Set<Role> HOST_ROLES = Set.of(Role.NORMAL_USER, Role.FACILITY_MANAGER, Role.ADMIN);
    public static final Set<AreaLevel> GUEST_AREA_LEVELS = Set.of(AreaLevel.INTERNAL_CONFIDENTIAL, AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED);

    private final SystemConfigService systemConfigService;
    private final GuestVisitRepository guestVisitRepository;

    public int hostMinLevel() {
        return systemConfigService.getInt(ConfigKey.GUEST_HOST_MIN_LEVEL);
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

    /** BR-GV-04: chỉ INTERNAL hoặc CONTACT nhận khách. */
    public boolean areaTypeAllowed(Area area) {
        return area != null && GUEST_AREA_LEVELS.contains(area.getAreaLevel());
    }

    /**
     * BR-GV-06: host vào được khu vực trong TRỌN [from, to) bằng ACCESS_LEVEL (khu vực không bắt buộc chỉ định và
     * cấp host ≥ cấp khu vực) hoặc AP chưa thu hồi phủ trọn khung. Không tính OPEN_EVENT và đơn truy cập.
     */
    public boolean hostCoversArea(User host, Area area, OffsetDateTime from, OffsetDateTime to) {
        boolean explicitRequired = Boolean.TRUE.equals(area.getExplicitAuthorizationRequired());
        if (!explicitRequired && host.getAccessLevel() != null && area.getAreaAccessLevel() != null
                && host.getAccessLevel() >= area.getAreaAccessLevel()) {
            return true;
        }
        return guestVisitRepository.existsAssignedPersonnelCovering(host.getId(), area.getId(), from, to);
    }
}
