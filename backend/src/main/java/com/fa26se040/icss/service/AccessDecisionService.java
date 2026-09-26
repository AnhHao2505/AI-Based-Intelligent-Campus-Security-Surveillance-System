package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Điểm xét quyền ra vào DUY NHẤT của hệ thống.
 *
 * <p>Đây là điểm bàn giao cho module AI (làm sau): hiện CHƯA có endpoint nào gọi, và không nơi nào
 * khác trong backend được tự xét quyền ra vào. Module nào cần biết "user X có được vào area Y lúc T
 * không" thì gọi {@link #checkEntry(UUID, UUID, OffsetDateTime)}.</p>
 */
@Service
@RequiredArgsConstructor
public class AccessDecisionService {

    private final UserRepository userRepository;
    private final AreaRepository areaRepository;
    private final AreaAssignedPersonnelRepository assignedPersonnelRepository;
    private final AccessRequestRepository accessRequestRepository;

    /**
     * Xét user có được vào area tại thời điểm at hay không. Thứ tự kiểm:
     * <ol>
     *   <li>User không tồn tại / inactive / xoá mềm → denied, NONE</li>
     *   <li>Area không tồn tại / inactive / xoá mềm → denied, NONE</li>
     *   <li>Có Assigned Personnel chưa thu hồi, validFrom &lt;= at &lt; validTo (NULL = vô hạn) → allowed, ASSIGNED_PERSONNEL</li>
     *   <li>explicit_authorization_required = false VÀ user.accessLevel &gt;= area.areaAccessLevel → allowed, ACCESS_LEVEL</li>
     *   <li>Có đơn APPROVED của area, startTime &lt;= at &lt; endTime, user là requester hoặc member → allowed, ACCESS_REQUEST</li>
     *   <li>Còn lại → denied, NONE</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public AccessDecision checkEntry(UUID userId, UUID areaId, OffsetDateTime at) {
        if (userId == null || areaId == null || at == null) {
            throw new IllegalArgumentException("userId, areaId và at không được để trống");
        }

        // 1. User
        Optional<User> userOpt = userRepository.findById(userId);
        if (userOpt.isEmpty()) {
            return AccessDecision.denied("Người dùng không tồn tại");
        }
        User user = userOpt.get();
        if (!Boolean.TRUE.equals(user.getIsActive()) || user.getDeletedAt() != null) {
            return AccessDecision.denied("Người dùng đã bị vô hiệu hoá hoặc đã bị xoá");
        }

        // 2. Area
        Optional<Area> areaOpt = areaRepository.findById(areaId);
        if (areaOpt.isEmpty()) {
            return AccessDecision.denied("Khu vực không tồn tại");
        }
        Area area = areaOpt.get();
        if (!Boolean.TRUE.equals(area.getIsActive()) || area.getDeletedAt() != null) {
            return AccessDecision.denied("Khu vực đã ngừng hoạt động hoặc đã bị xoá");
        }

        // 3. Assigned Personnel còn hiệu lực tại at (validTo NULL = không hạn)
        List<AreaAssignedPersonnel> assignments = assignedPersonnelRepository.findEffectiveAt(userId, areaId, at);
        if (!assignments.isEmpty()) {
            return AccessDecision.allowed(
                    AccessSource.ASSIGNED_PERSONNEL,
                    assignments.get(0).getId(),
                    "Người dùng được gán vào khu vực"
            );
        }

        // 4. Access Level (khi khu vực không yêu cầu chỉ định đích danh)
        // Khi explicit == true thì level KHÔNG bao giờ cho vào, kể cả level cao nhất.
        boolean explicitRequired = Boolean.TRUE.equals(area.getExplicitAuthorizationRequired());
        if (!explicitRequired
                && user.getAccessLevel() != null
                && area.getAreaAccessLevel() != null
                && user.getAccessLevel() >= area.getAreaAccessLevel()) {
            return AccessDecision.allowed(
                    AccessSource.ACCESS_LEVEL,
                    null,
                    "Cấp độ truy cập của người dùng phù hợp với khu vực"
            );
        }

        // 4b. Chế độ sự kiện (Event Mode / open_to_members)
        boolean isInternalOrContact = area.getAreaLevel() == AreaLevel.INTERNAL_CONFIDENTIAL
                || area.getAreaLevel() == AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED;
        if (area.isEventActive(at) && isInternalOrContact) {
            return AccessDecision.allowed(
                    AccessSource.OPEN_EVENT,
                    null,
                    "Khu vực đang mở chế độ sự kiện cho thành viên"
            );
        }

        // 5. Đơn access_requests đã APPROVED (requester hoặc member đơn nhóm)
        // status == APPROVED (FINISHED, PENDING, REJECTED, CANCELLED... đều KHÔNG tính)
        List<AccessRequest> requests = accessRequestRepository.findCoveringRequestsForUser(
                userId, areaId, at, RequestStatus.APPROVED);
        if (!requests.isEmpty()) {
            return AccessDecision.allowed(
                    AccessSource.ACCESS_REQUEST,
                    requests.get(0).getId(),
                    "Có đơn xin quyền đã được duyệt trong khung thời gian"
            );
        }

        // 6. Không có nguồn cấp quyền nào -> deny NONE
        return AccessDecision.denied("Không có quyền ra vào khu vực tại thời điểm này");
    }
}
