package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.dto.accessdecision.CheckCameraEntryResponse;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import com.fa26se040.icss.dto.guest.GuestAccessDecision;
import com.fa26se040.icss.entity.Guest;
import com.fa26se040.icss.repository.GuestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Điểm xét quyền ra vào DUY NHẤT của hệ thống.
 *
 * <p>Đây là điểm bàn giao cho module AI: phục vụ xét quyền ra vào khuôn viên trường học
 * thông qua phương thức {@link #checkEntry(UUID, UUID, OffsetDateTime)} và {@link #checkCameraEntry(String, String, OffsetDateTime)}.</p>
 */
@Service
@RequiredArgsConstructor
public class AccessDecisionService {

    private final UserRepository userRepository;
    private final AreaRepository areaRepository;
    private final CameraRepository cameraRepository;
    private final AreaAssignedPersonnelRepository assignedPersonnelRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final com.fa26se040.icss.repository.AreaEventScheduleRepository areaEventScheduleRepository;
    private final GuestRepository guestRepository;
    private final GuestAccessDecisionService guestAccessDecisionService;
    private final CameraAccessLogService cameraAccessLogService;

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

        // 4c. Lịch sự kiện đã đặt trước (BR-ES-10, A3)
        if (isInternalOrContact && areaEventScheduleRepository != null
                && areaEventScheduleRepository.existsByAreaIdAndStatusAndStartAtLessThanEqualAndEndAtGreaterThan(
                areaId, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED, at, at)) {
            return AccessDecision.allowed(
                    AccessSource.OPEN_EVENT,
                    null,
                    "Khu vực trong khung lịch sự kiện"
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

        // 6. Phân tích nguyên nhân từ chối chi tiết (Hỗ trợ phân biệt Sai giờ vs Sai quyền)
        // A. Kiểm tra có đơn APPROVED cho khu vực này nhưng không đúng khung thời gian (đến sớm hoặc quá hạn)
        if (accessRequestRepository != null) {
            List<AccessRequest> approvedRequests = accessRequestRepository.findAnyApprovedRequestsForUser(
                    userId, areaId, RequestStatus.APPROVED);
            if (approvedRequests != null && !approvedRequests.isEmpty()) {
                AccessRequest ar = approvedRequests.get(0);
                if (ar.getStartTime() != null && at.isBefore(ar.getStartTime())) {
                    return AccessDecision.denied("Chưa tới khung giờ được cấp phép theo đơn (Bắt đầu lúc: "
                            + ar.getStartTime().toLocalTime() + ")");
                } else if (ar.getEndTime() != null && !at.isBefore(ar.getEndTime())) {
                    return AccessDecision.denied("Đơn xin quyền đã hết hạn sử dụng (Đã kết thúc lúc: "
                            + ar.getEndTime().toLocalTime() + ")");
                }
            }
        }

        // B. Kiểm tra phân công nhân sự nhưng chưa tới hoặc đã hết hạn
        if (assignedPersonnelRepository != null) {
            List<AreaAssignedPersonnel> assignmentsAll = assignedPersonnelRepository.findByAreaIdWithDetails(areaId);
            if (assignmentsAll != null) {
                for (AreaAssignedPersonnel ap : assignmentsAll) {
                    if (ap.getUser() != null && ap.getUser().getId().equals(userId) && ap.getRevokedAt() == null) {
                        if (ap.getValidFrom() != null && at.isBefore(ap.getValidFrom())) {
                            return AccessDecision.denied("Phân công nhân sự chưa tới ngày hiệu lực (Bắt đầu: " + ap.getValidFrom().toLocalDate() + ")");
                        }
                        if (ap.getValidTo() != null && !at.isBefore(ap.getValidTo())) {
                            return AccessDecision.denied("Phân công nhân sự đã hết hạn hiệu lực (Đã kết thúc: " + ap.getValidTo().toLocalDate() + ")");
                        }
                    }
                }
            }
        }

        // C. Phân biệt sai quyền do cấp độ hoặc do yêu cầu đích danh
        if (explicitRequired) {
            return AccessDecision.denied("Khu vực yêu cầu chỉ định đích danh (cần đơn xin quyền hoặc được phân công)");
        }
        if (user.getAccessLevel() != null && area.getAreaAccessLevel() != null && user.getAccessLevel() < area.getAreaAccessLevel()) {
            return AccessDecision.denied("Cấp độ truy cập không đủ (Khu vực Cấp " + area.getAreaAccessLevel() + ", người dùng Cấp " + user.getAccessLevel() + ")");
        }

        // 7. Mặc định: Không có nguồn cấp quyền nào -> deny NONE
        return AccessDecision.denied("Không có quyền ra vào khu vực này");
    }

    /**
     * Xét quyền ra vào dựa trên mã Camera và mã Người dùng (phục vụ module AI Face Recognition).
     */
    @Transactional
    public CheckCameraEntryResponse checkCameraEntry(String cameraCode, String userCode, OffsetDateTime at) {
        if (at == null) {
            at = OffsetDateTime.now();
        }
        if (cameraCode == null || cameraCode.isBlank()) {
            return CheckCameraEntryResponse.denied(cameraCode, userCode, "Mã camera không được để trống");
        }
        if (userCode == null || userCode.isBlank()) {
            return CheckCameraEntryResponse.denied(cameraCode, userCode, "Mã người dùng không được để trống");
        }

        // 1. Tìm Camera và Khu vực tương ứng
        Optional<Camera> cameraOpt = cameraRepository.findByCameraCodeAndDeletedAtIsNull(cameraCode);
        if (cameraOpt.isEmpty()) {
            return CheckCameraEntryResponse.denied(cameraCode, userCode, "Camera không tồn tại hoặc đã bị xoá");
        }
        Camera camera = cameraOpt.get();
        Area area = camera.getArea();
        if (area == null || !Boolean.TRUE.equals(area.getIsActive()) || area.getDeletedAt() != null) {
            return CheckCameraEntryResponse.denied(cameraCode, userCode, "Camera chưa được gán vào khu vực hoạt động nào");
        }

        // 2. Tìm người dùng theo userCode
        Optional<User> userOpt = userRepository.findByUserCodeAndDeletedAtIsNull(userCode);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            AccessDecision decision = checkEntry(user.getId(), area.getId(), at);
            CheckCameraEntryResponse response = CheckCameraEntryResponse.builder()
                    .allowed(decision.allowed())
                    .source(decision.source())
                    .reason(decision.reason())
                    .cameraCode(camera.getCameraCode())
                    .areaId(area.getId())
                    .areaName(area.getName())
                    .userId(user.getId())
                    .userCode(user.getUserCode())
                    .fullName(user.getFullName())
                    .build();
            if (cameraAccessLogService != null) {
                cameraAccessLogService.recordAccessLog(response, at);
            }
            return response;
        }

        // 3. Nếu không phải người dùng nội bộ, kiểm tra xem có phải Khách tham quan (Guest) không
        if (guestRepository != null && guestAccessDecisionService != null) {
            try {
                String guestIdStr = userCode.startsWith("GUEST-") ? userCode.substring(6) : userCode;
                UUID guestId = UUID.fromString(guestIdStr);
                Optional<Guest> guestOpt = guestRepository.findById(guestId);
                if (guestOpt.isPresent()) {
                    Guest guest = guestOpt.get();
                    GuestAccessDecision guestDecision = guestAccessDecisionService.checkGuestEntry(guestId, area.getId(), at);
                    String reasonText = guestDecision.allowed()
                            ? "Khách tham quan hợp lệ trong lượt tiếp khách"
                            : (guestDecision.reason() != null ? switch (guestDecision.reason()) {
                                case VISIT_NOT_ACTIVE -> "Lượt tiếp khách chưa được duyệt hoặc không còn hiệu lực";
                                case OUTSIDE_WINDOW -> "Khách vào ngoài khung giờ được tiếp đón";
                                case AREA_NOT_IN_VISIT -> "Khu vực này không nằm trong danh sách tiếp đón của khách";
                                case AREA_INACTIVE -> "Khu vực đang tạm dừng hoạt động";
                                case AREA_TYPE_NOT_ALLOWED -> "Loại khu vực không tiếp nhận khách";
                                case HOST_LOST_ACCESS -> "Người tiếp đón (Host) không còn quyền truy cập khu vực";
                                case BIOMETRIC_DELETED -> "Dữ liệu sinh trắc học của khách đã bị xoá";
                                case BIOMETRIC_NOT_READY -> "Dữ liệu sinh trắc học của khách chưa sẵn sàng";
                                default -> "Khách không có quyền vào khu vực này";
                            } : "Khách không có quyền vào khu vực này");

                    CheckCameraEntryResponse response = CheckCameraEntryResponse.builder()
                            .allowed(guestDecision.allowed())
                            .source(guestDecision.allowed() ? AccessSource.GUEST_VISIT : AccessSource.NONE)
                            .reason(reasonText)
                            .cameraCode(camera.getCameraCode())
                            .areaId(area.getId())
                            .areaName(area.getName())
                            .userId(guest.getId())
                            .userCode("GUEST-" + guest.getId().toString().substring(0, 8))
                            .fullName(guest.getFullName() + " (Khách)")
                            .build();
                    if (cameraAccessLogService != null) {
                        cameraAccessLogService.recordAccessLog(response, at);
                    }
                    return response;
                }
            } catch (IllegalArgumentException ignored) {
                // userCode không phải định dạng UUID của khách
            }
        }

        CheckCameraEntryResponse denied = CheckCameraEntryResponse.denied(cameraCode, userCode, "Người dùng không tồn tại hoặc đã bị xoá");
        if (cameraAccessLogService != null) {
            cameraAccessLogService.recordAccessLog(denied, at);
        }
        return denied;
    }
}
