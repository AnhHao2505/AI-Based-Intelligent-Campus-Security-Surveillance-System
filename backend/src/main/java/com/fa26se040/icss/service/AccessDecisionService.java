package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessdecision.AccessDecision;
import com.fa26se040.icss.dto.accessdecision.CheckCameraEntryResponse;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessSource;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.CameraType;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.GuestEntryDenyReason;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.GuardIncidentAccessRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import com.fa26se040.icss.dto.guest.GuestAccessDecision;
import com.fa26se040.icss.entity.Guest;
import com.fa26se040.icss.repository.GuestRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Điểm xét quyền ra vào DUY NHẤT của hệ thống.
 *
 * <p>Đây là điểm bàn giao cho module AI: phục vụ xét quyền ra vào khuôn viên trường học
 * thông qua phương thức {@link #checkEntry(UUID, UUID, OffsetDateTime)} và {@link #checkCameraEntry(String, String, OffsetDateTime)}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccessDecisionService {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter TIME_DATE_FORMATTER = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(VN_ZONE);
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(VN_ZONE);

    private final UserRepository userRepository;
    private final AreaRepository areaRepository;
    private final CameraRepository cameraRepository;
    private final AreaAssignedPersonnelRepository assignedPersonnelRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final com.fa26se040.icss.repository.AreaEventScheduleRepository areaEventScheduleRepository;
    private final com.fa26se040.icss.repository.AreaEventSessionRepository areaEventSessionRepository;
    private final GuestRepository guestRepository;
    private final GuestAccessDecisionService guestAccessDecisionService;
    private final CameraAccessLogService cameraAccessLogService;
    private final GuardIncidentAccessRepository guardIncidentAccessRepository;
    private final SystemConfigService systemConfigService;

    @Autowired(required = false)
    private GuardShiftRepository guardShiftRepository;

    /**
     * Xét user có được vào area tại thời điểm at hay không. Thứ tự kiểm:
     * <ol>
     *   <li>User không tồn tại / inactive / xoá mềm → denied, NONE</li>
     *   <li>Area không tồn tại / inactive / xoá mềm → denied, NONE</li>
     *   <li>Có Assigned Personnel chưa thu hồi, validFrom &lt;= at &lt; validTo (NULL = vô hạn) → allowed, ASSIGNED_PERSONNEL</li>
     *   <li>user.accessLevel &gt;= cấp của loại khu vực (V83: Công khai 1, Bảo mật 2, Bảo mật cao 3) → allowed, ACCESS_LEVEL</li>
     *   <li>Khu Bảo mật có phiên sự kiện phủ at hoặc trong khung lịch sự kiện → allowed, OPEN_EVENT</li>
     *   <li>GUARD đã tiếp nhận sự cố tại area, at nằm trong cửa sổ xử lý sự cố → allowed, INCIDENT_RESPONSE (BR-GD-02/03)</li>
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
        if (userOpt.isEmpty() || userOpt.get().getDeletedAt() != null) {
            return AccessDecision.denied("Người dùng không tồn tại");
        }
        User user = userOpt.get();
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            return AccessDecision.denied("Tài khoản đã bị vô hiệu hoá");
        }

        // 2. Area
        Optional<Area> areaOpt = areaRepository.findById(areaId);
        if (areaOpt.isEmpty() || areaOpt.get().getDeletedAt() != null) {
            return AccessDecision.denied("Khu vực không tồn tại");
        }
        Area area = areaOpt.get();
        if (!Boolean.TRUE.equals(area.getIsActive())) {
            return AccessDecision.denied("Khu vực đã ngừng hoạt động");
        }

        // 3. Assigned Personnel còn hiệu lực tại at (validTo NULL = không hạn)
        List<AreaAssignedPersonnel> assignments = assignedPersonnelRepository.findEffectiveAt(userId, areaId, at);
        if (!assignments.isEmpty()) {
            AreaAssignedPersonnel ap = assignments.get(0);
            return AccessDecision.allowed(
                    AccessSource.ASSIGNED_PERSONNEL,
                    ap.getId(),
                    "Người dùng được gán vào khu vực",
                    latestCoverageEnd(user, area, userId, areaId, at)
            );
        }

        // 4. Access Level: cấp người ≥ cấp của loại khu vực (V83, loại ⇔ cấp, không còn cờ chỉ định đích danh)
        if (user.getAccessLevel() != null
                && area.getAreaAccessLevel() != null
                && user.getAccessLevel() >= area.getAreaAccessLevel()) {
            return AccessDecision.allowed(
                    AccessSource.ACCESS_LEVEL,
                    null,
                    "Cấp độ truy cập của người dùng phù hợp với khu vực"
            );
        }

        // 4b. Chế độ sự kiện: chỉ khu Bảo mật, có phiên sự kiện phủ at (started_at <= at < actual_end / planned_end).
        // Không dùng cờ open_until trên areas: cờ chỉ phản ánh hiện tại, không biết sự kiện mở từ lúc nào và đã đóng khi nào.
        boolean eventApplicable = area.getAreaLevel() == AreaLevel.CONFIDENTIAL;
        if (eventApplicable && areaEventSessionRepository != null
                && areaEventSessionRepository.existsSessionCoveringAt(areaId, at)) {
            return AccessDecision.allowed(
                    AccessSource.OPEN_EVENT,
                    null,
                    "Khu vực đang mở chế độ sự kiện cho thành viên"
            );
        }

        // 4c. Lịch sự kiện đã đặt trước (BR-ES-10, A3)
        if (eventApplicable && areaEventScheduleRepository != null
                && areaEventScheduleRepository.existsByAreaIdAndStatusAndStartAtLessThanEqualAndEndAtGreaterThan(
                areaId, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED, at, at)) {
            AreaEventSchedule covering = findCoveringSchedules(areaId, at).stream().findFirst().orElse(null);
            return AccessDecision.allowed(
                    AccessSource.OPEN_EVENT,
                    covering != null ? covering.getId() : null,
                    "Khu vực trong khung lịch sự kiện",
                    latestCoverageEnd(user, area, userId, areaId, at)
            );
        }

        // 4d. Bảo vệ đang xử lý sự cố mình đã tiếp nhận tại khu (BR-GD-02 / BR-GD-03), không cần đơn.
        // Đặt sau bước 4/4b/4c: khu GUARD đã vào được bằng cấp độ vẫn ghi nguồn ACCESS_LEVEL như cũ,
        // rule này chỉ mở khu GUARD không tự vào được bằng cấp (với cấp mặc định 2 của GUARD: khu Bảo mật cao).
        if (user.getRole() == Role.GUARD) {
            Optional<SecurityIncident> incident = findIncidentCoveringGuard(userId, areaId, at);
            if (incident.isPresent()) {
                return AccessDecision.allowed(
                        AccessSource.INCIDENT_RESPONSE,
                        incident.get().getId(),
                        "Bảo vệ đang xử lý sự cố tại khu vực"
                );
            }
        }

        // 5. Đơn access_requests đã APPROVED (requester hoặc member đơn nhóm)
        // status == APPROVED (FINISHED, PENDING, REJECTED, CANCELLED... đều KHÔNG tính)
        List<AccessRequest> requests = accessRequestRepository.findCoveringRequestsForUser(
                userId, areaId, at, RequestStatus.APPROVED);
        if (!requests.isEmpty()) {
            AccessRequest ar = requests.get(0);
            return AccessDecision.allowed(
                    AccessSource.ACCESS_REQUEST,
                    ar.getId(),
                    "Có đơn xin quyền đã được duyệt trong khung thời gian",
                    latestCoverageEnd(user, area, userId, areaId, at)
            );
        }

        // 6. Phân tích nguyên nhân từ chối chi tiết (Hỗ trợ phân biệt Sai giờ vs Sai quyền)
        // A. Kiểm tra có đơn APPROVED cho khu vực này trong CÙNG NGÀY nhưng không đúng khung thời gian (đến sớm hoặc quá hạn)
        if (accessRequestRepository != null) {
            LocalDate accessDate = at.atZoneSameInstant(VN_ZONE).toLocalDate();
            List<AccessRequest> approvedRequests = accessRequestRepository.findAnyApprovedRequestsForUser(
                    userId, areaId, RequestStatus.APPROVED);
            if (approvedRequests != null) {
                AccessRequest upcomingRequest = null;
                AccessRequest expiredRequest = null;
                for (AccessRequest ar : approvedRequests) {
                    if (ar.getStartTime() == null || ar.getEndTime() == null) {
                        continue;
                    }
                    LocalDate reqStartDate = ar.getStartTime().atZoneSameInstant(VN_ZONE).toLocalDate();
                    LocalDate reqEndDate = ar.getEndTime().atZoneSameInstant(VN_ZONE).toLocalDate();

                    // Chỉ áp dụng tag sai giờ khi đơn và thời điểm truy cập diễn ra trong cùng ngày
                    boolean isSameDay = !accessDate.isBefore(reqStartDate) && !accessDate.isAfter(reqEndDate);
                    if (isSameDay) {
                        if (at.isBefore(ar.getStartTime())) {
                            if (upcomingRequest == null || ar.getStartTime().isBefore(upcomingRequest.getStartTime())) {
                                upcomingRequest = ar;
                            }
                        } else if (!at.isBefore(ar.getEndTime())) {
                            if (expiredRequest == null || ar.getEndTime().isAfter(expiredRequest.getEndTime())) {
                                expiredRequest = ar;
                            }
                        }
                    }
                }
                // Ưu tiên đơn sắp diễn ra (upcoming) trước để người đến sớm được cấp startsAt chuẩn xác
                if (upcomingRequest != null) {
                    return AccessDecision.denied("Chưa tới khung giờ được cấp phép theo đơn (Bắt đầu lúc: "
                            + TIME_DATE_FORMATTER.format(upcomingRequest.getStartTime()) + ")", upcomingRequest.getStartTime());
                } else if (expiredRequest != null) {
                    return AccessDecision.denied("Đơn xin quyền đã hết hạn sử dụng trong ngày (Đã kết thúc lúc: "
                            + TIME_DATE_FORMATTER.format(expiredRequest.getEndTime()) + ")");
                }
            }
        }

        // B. Kiểm tra phân công nhân sự nhưng chưa tới hoặc đã hết hạn
        if (assignedPersonnelRepository != null) {
            List<AreaAssignedPersonnel> assignmentsAll = assignedPersonnelRepository.findByAreaIdWithDetails(areaId);
            if (assignmentsAll != null) {
                AreaAssignedPersonnel upcomingAssignment = null;
                AreaAssignedPersonnel expiredAssignment = null;
                for (AreaAssignedPersonnel ap : assignmentsAll) {
                    if (ap.getUser() != null && ap.getUser().getId().equals(userId) && ap.getRevokedAt() == null) {
                        if (ap.getValidFrom() != null && at.isBefore(ap.getValidFrom())) {
                            if (upcomingAssignment == null || ap.getValidFrom().isBefore(upcomingAssignment.getValidFrom())) {
                                upcomingAssignment = ap;
                            }
                        } else if (ap.getValidTo() != null && !at.isBefore(ap.getValidTo())) {
                            if (expiredAssignment == null || ap.getValidTo().isAfter(expiredAssignment.getValidTo())) {
                                expiredAssignment = ap;
                            }
                        }
                    }
                }
                if (upcomingAssignment != null) {
                    return AccessDecision.denied("Phân công nhân sự chưa tới ngày hiệu lực (Bắt đầu: "
                            + DATE_FORMATTER.format(upcomingAssignment.getValidFrom()) + ")", upcomingAssignment.getValidFrom());
                } else if (expiredAssignment != null) {
                    return AccessDecision.denied("Phân công nhân sự đã hết hạn hiệu lực (Đã kết thúc: "
                            + DATE_FORMATTER.format(expiredAssignment.getValidTo()) + ")");
                }
            }
        }

        // C. Kiểm tra sự kiện đã lên lịch cho hôm nay nhưng chưa tới giờ bắt đầu
        if (eventApplicable && areaEventScheduleRepository != null) {
            List<AreaEventSchedule> schedules = areaEventScheduleRepository.findByAreaIdAndStatusOrderByStartAtAsc(
                    areaId, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED);
            if (schedules != null) {
                LocalDate accessDate = at.atZoneSameInstant(VN_ZONE).toLocalDate();
                for (AreaEventSchedule sched : schedules) {
                    if (sched.getStartAt() != null && at.isBefore(sched.getStartAt())) {
                        LocalDate schedDate = sched.getStartAt().atZoneSameInstant(VN_ZONE).toLocalDate();
                        if (accessDate.equals(schedDate)) {
                            return AccessDecision.denied(
                                    "Chưa tới khung giờ diễn ra sự kiện (Bắt đầu lúc: " + TIME_DATE_FORMATTER.format(sched.getStartAt()) + ")",
                                    sched.getStartAt()
                            );
                        }
                    }
                }
            }
        }

        // C. Sai quyền do cấp độ
        if (user.getAccessLevel() != null && area.getAreaAccessLevel() != null && user.getAccessLevel() < area.getAreaAccessLevel()) {
            return AccessDecision.denied("Cấp độ truy cập không đủ (Khu vực Cấp " + area.getAreaAccessLevel() + ", người dùng Cấp " + user.getAccessLevel() + ")");
        }

        // 7. Mặc định: Không có nguồn cấp quyền nào -> deny NONE
        return AccessDecision.denied("Không có quyền ra vào khu vực này");
    }

    /**
     * BR-GD-02 / BR-GD-03: sự cố mới nhất tại khu do bảo vệ này tiếp nhận, nếu at nằm trong cửa sổ [claimedAt, end).
     * <ul>
     *   <li>Chưa xử lý xong: end = claimedAt + GUARD_INCIDENT_ACCESS_MINUTES</li>
     *   <li>Đã xử lý xong: end = min(claimedAt + GUARD_INCIDENT_ACCESS_MINUTES, resolvedAt + GUARD_INCIDENT_EXIT_GRACE_MINUTES)</li>
     * </ul>
     * Số phút đọc qua SystemConfigService mỗi lần gọi nên đổi cấu hình có hiệu lực ngay.
     */
    /** Các lịch sự kiện SCHEDULED của area phủ thời điểm at (startAt &lt;= at &lt; endAt). */
    private List<AreaEventSchedule> findCoveringSchedules(UUID areaId, OffsetDateTime at) {
        if (areaEventScheduleRepository == null) {
            return List.of();
        }
        List<AreaEventSchedule> schedules = areaEventScheduleRepository.findByAreaIdAndStatusOrderByStartAtAsc(
                areaId, com.fa26se040.icss.enums.AreaEventScheduleStatus.SCHEDULED);
        if (schedules == null) {
            return List.of();
        }
        return schedules.stream()
                .filter(s -> s.getStartAt() != null && s.getEndAt() != null
                        && !at.isBefore(s.getStartAt()) && at.isBefore(s.getEndAt()))
                .toList();
    }

    /**
     * Mốc hết hiệu lực muộn nhất trong mọi nguồn đang cho phép user vào area tại at, để AI hẹn giờ hỏi lại backend.
     * Trả null khi có ít nhất một nguồn không giới hạn thời gian (cấp độ, phân công không hạn, phiên sự kiện,
     * bảo vệ xử lý sự cố): khi đó AI không hẹn giờ, quyền vẫn được xét lại theo TTL cache như cũ.
     * Lấy mốc muộn nhất (không phải mốc của nguồn đầu tiên) để không báo hết hạn khi còn nguồn khác phủ.
     */
    private OffsetDateTime latestCoverageEnd(User user, Area area, UUID userId, UUID areaId, OffsetDateTime at) {
        if (user.getAccessLevel() != null && area.getAreaAccessLevel() != null
                && user.getAccessLevel() >= area.getAreaAccessLevel()) {
            return null;
        }
        OffsetDateTime latest = null;
        List<AreaAssignedPersonnel> assignments = assignedPersonnelRepository.findEffectiveAt(userId, areaId, at);
        if (assignments != null) {
            for (AreaAssignedPersonnel ap : assignments) {
                if (ap.getValidTo() == null) {
                    return null;
                }
                latest = later(latest, ap.getValidTo());
            }
        }
        boolean eventApplicable = area.getAreaLevel() == AreaLevel.CONFIDENTIAL;
        if (eventApplicable) {
            if (areaEventSessionRepository != null && areaEventSessionRepository.existsSessionCoveringAt(areaId, at)) {
                return null;
            }
            for (AreaEventSchedule sched : findCoveringSchedules(areaId, at)) {
                latest = later(latest, sched.getEndAt());
            }
        }
        if (user.getRole() == Role.GUARD && findIncidentCoveringGuard(userId, areaId, at).isPresent()) {
            return null;
        }
        List<AccessRequest> requests = accessRequestRepository.findCoveringRequestsForUser(
                userId, areaId, at, RequestStatus.APPROVED);
        if (requests != null) {
            for (AccessRequest ar : requests) {
                if (ar.getEndTime() == null) {
                    return null;
                }
                latest = later(latest, ar.getEndTime());
            }
        }
        return latest;
    }

    private static OffsetDateTime later(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) {
            return b;
        }
        return b != null && b.isAfter(a) ? b : a;
    }

    private Optional<SecurityIncident> findIncidentCoveringGuard(UUID userId, UUID areaId, OffsetDateTime at) {
        if (guardIncidentAccessRepository == null || systemConfigService == null) {
            return Optional.empty();
        }
        List<SecurityIncident> latest = guardIncidentAccessRepository.findLatestClaimedByUserInArea(
                userId, areaId, PageRequest.of(0, 1));
        if (latest.isEmpty()) {
            return Optional.empty();
        }
        SecurityIncident incident = latest.get(0);
        OffsetDateTime start = incident.getClaimedAt();
        OffsetDateTime end = start.plusMinutes(systemConfigService.getInt(ConfigKey.GUARD_INCIDENT_ACCESS_MINUTES));
        if (incident.getStatus() != IncidentStatus.CLAIMED) {
            // Đã xử lý xong mà thiếu resolvedAt thì không tính được ân hạn → không cấp quyền
            if (incident.getResolvedAt() == null) {
                return Optional.empty();
            }
            OffsetDateTime graceEnd = incident.getResolvedAt()
                    .plusMinutes(systemConfigService.getInt(ConfigKey.GUARD_INCIDENT_EXIT_GRACE_MINUTES));
            if (graceEnd.isBefore(end)) {
                end = graceEnd;
            }
        }
        return !at.isBefore(start) && at.isBefore(end) ? Optional.of(incident) : Optional.empty();
    }

    /**
     * Xét quyền ra vào dựa trên mã Camera và mã Người dùng (phục vụ module AI Face Recognition).
     */
    @Transactional
    public CheckCameraEntryResponse checkCameraEntry(String cameraCode, String userCode, OffsetDateTime at) {
        return checkCameraEntry(cameraCode, userCode, at, null, null, null);
    }

    /**
     * Xét quyền ra vào tại vị trí Camera và ghi log sự kiện qua vùng ROI (VÀO / RA).
     */
    @Transactional
    public CheckCameraEntryResponse checkCameraEntry(
            String cameraCode,
            String userCode,
            OffsetDateTime at,
            String roiLabel,
            String eventDirection,
            Double durationSeconds
    ) {
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
            return CheckCameraEntryResponse.denied(cameraCode, userCode, "Camera không tồn tại");
        }
        Camera camera = cameraOpt.get();
        if (camera.getStatus() == CameraStatus.DECOMMISSIONED) {
            return CheckCameraEntryResponse.denied(cameraCode, userCode, "Camera đã ngừng hoạt động (DECOMMISSIONED)");
        }
        Area area = camera.getArea();
        if (area == null) {
            if (camera.getCameraType() == CameraType.CORRIDOR) {
                return checkCorridorCameraEntry(camera, userCode, at, roiLabel, eventDirection);
            }
            return CheckCameraEntryResponse.denied(cameraCode, userCode, "Camera chưa được gán vào khu vực hoạt động nào");
        }

        boolean isExit = eventDirection != null && eventDirection.equalsIgnoreCase("EXIT");
        if (isExit) {
            // Nghiệp vụ: Không ghi log ra vào và không phát sinh thông báo khi rời khỏi vùng ROI
            return CheckCameraEntryResponse.builder()
                    .allowed(true)
                    .source(AccessSource.NONE)
                    .reason("Rời khu vực")
                    .cameraCode(camera.getCameraCode())
                    .areaId(area.getId())
                    .areaName(area.getName())
                    .userCode(userCode != null && !userCode.isBlank() ? userCode : "UNKNOWN")
                    .fullName(userCode != null && !userCode.isBlank() ? userCode : "Người lạ / Chưa rõ mặt")
                    .build();
        }

        String cleanRoi = (roiLabel != null && !roiLabel.isBlank()) ? roiLabel.trim() : null;

        // Từ đây đã xác định được camera + khu: mọi kết quả DENIED đều gắn khu để ghi được nhật ký ra vào
        if (!Boolean.TRUE.equals(area.getIsActive()) || area.getDeletedAt() != null) {
            String reason = cleanRoi != null ? "[VÀO ROI: " + cleanRoi + "] Khu vực đã ngừng hoạt động" : "Khu vực đã ngừng hoạt động";
            String effectiveUserCode = (userCode != null && !userCode.isBlank()) ? userCode : "UNKNOWN";
            CheckCameraEntryResponse inactive = deniedAtCamera(camera, area, effectiveUserCode, reason);
            recordAccessLogSafely(inactive, at);
            return inactive;
        }

        // 2. Tìm người dùng theo userCode
        Optional<User> userOpt = userRepository.findByUserCodeAndDeletedAtIsNull(userCode);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            AccessDecision decision = checkEntry(user.getId(), area.getId(), at);
            String entryReason = decision.reason();
            if (cleanRoi != null) {
                entryReason = "[VÀO ROI: " + cleanRoi + "] " + entryReason;
            }
            Double expiresAtSec = null;
            if (decision.expiresAt() != null) {
                expiresAtSec = (double) decision.expiresAt().toInstant().toEpochMilli() / 1000.0;
            }
            Double startsAtSec = null;
            if (decision.startsAt() != null) {
                startsAtSec = (double) decision.startsAt().toInstant().toEpochMilli() / 1000.0;
            }
            CheckCameraEntryResponse response = CheckCameraEntryResponse.builder()
                    .allowed(decision.allowed())
                    .source(decision.source())
                    .expiresAt(expiresAtSec)
                    .startsAt(startsAtSec)
                    .reason(entryReason)
                    .cameraCode(camera.getCameraCode())
                    .areaId(area.getId())
                    .areaName(area.getName())
                    .userId(user.getId())
                    .userCode(user.getUserCode())
                    .fullName(user.getFullName())
                    .build();
            recordAccessLogSafely(response, at);
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

                    if (cleanRoi != null) {
                        reasonText = "[VÀO ROI: " + cleanRoi + "] " + reasonText;
                    }

                    Double guestExpiresAtSec = null;
                    if (guestDecision.allowed() && guest.getVisit() != null && guest.getVisit().getEndTime() != null) {
                        guestExpiresAtSec = (double) guest.getVisit().getEndTime().toInstant().toEpochMilli() / 1000.0;
                    }

                    Double guestStartsAtSec = null;
                    if (!guestDecision.allowed() && guestDecision.reason() == GuestEntryDenyReason.OUTSIDE_WINDOW
                            && guest.getVisit() != null && guest.getVisit().getStatus() == GuestVisitStatus.APPROVED
                            && guest.getVisit().getStartTime() != null && at.isBefore(guest.getVisit().getStartTime())) {
                        boolean areaInVisit = guest.getVisit().getAreas() != null &&
                                guest.getVisit().getAreas().stream().anyMatch(a -> a.getId().equals(area.getId()));
                        if (areaInVisit) {
                            LocalDate accessDate = at.atZoneSameInstant(VN_ZONE).toLocalDate();
                            LocalDate visitDate = guest.getVisit().getStartTime().atZoneSameInstant(VN_ZONE).toLocalDate();
                            if (accessDate.equals(visitDate)) {
                                guestStartsAtSec = (double) guest.getVisit().getStartTime().toInstant().toEpochMilli() / 1000.0;
                            }
                        }
                    }

                    CheckCameraEntryResponse response = CheckCameraEntryResponse.builder()
                            .allowed(guestDecision.allowed())
                            .source(guestDecision.allowed() ? AccessSource.GUEST_VISIT : AccessSource.NONE)
                            .expiresAt(guestExpiresAtSec)
                            .startsAt(guestStartsAtSec)
                            .reason(reasonText)
                            .denyReason(guestDenyReasonCode(guestDecision))
                            .cameraCode(camera.getCameraCode())
                            .areaId(area.getId())
                            .areaName(area.getName())
                            .userId(guest.getId())
                            .userCode("GUEST-" + guest.getId().toString().substring(0, 8))
                            .fullName(guest.getFullName() + " (Khách)")
                            .build();
                    recordAccessLogSafely(response, at);
                    return response;
                }
            } catch (IllegalArgumentException ignored) {
                // userCode không phải định dạng UUID của khách
            }
        }

        String finalDeniedReason = cleanRoi != null ? "[VÀO ROI: " + cleanRoi + "] Người dùng không tồn tại" : "Người dùng không tồn tại";
        String effectiveUserCode = (userCode != null && !userCode.isBlank()) ? userCode : "UNKNOWN";
        CheckCameraEntryResponse denied = deniedAtCamera(camera, area, effectiveUserCode, finalDeniedReason);
        recordAccessLogSafely(denied, at);
        return denied;
    }

    /**
     * DENIED tại một camera đã xác định được khu: gắn areaId / areaName / cameraCode để nhật ký ghi được (area_id NOT NULL).
     */
    /** L4: mã lý do từ chối khách gửi cho AI service hiển thị đúng lý do; null khi khách được phép. */
    private static String guestDenyReasonCode(GuestAccessDecision decision) {
        if (decision.allowed() || decision.reason() == null) {
            return null;
        }
        return decision.reason().name();
    }

    private static CheckCameraEntryResponse deniedAtCamera(Camera camera, Area area, String userCode, String reason) {
        String effectiveUserCode = (userCode != null && !userCode.isBlank()) ? userCode : "UNKNOWN";
        return CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason(reason)
                .cameraCode(camera.getCameraCode())
                .areaId(area.getId())
                .areaName(area.getName())
                .userCode(effectiveUserCode)
                // Chỉ người chưa nhận diện mới mang tên chuẩn hoá; user có thật để null để nhật ký lấy tên từ tài khoản
                .fullName("UNKNOWN".equals(effectiveUserCode) ? "Người lạ / Chưa rõ mặt" : null)
                .build();
    }

    private CheckCameraEntryResponse checkCorridorCameraEntry(
            Camera camera,
            String userCode,
            OffsetDateTime at,
            String roiLabel,
            String eventDirection
    ) {
        String corridorName = (camera.getName() != null && !camera.getName().isBlank())
                ? camera.getName().trim()
                : "Hành lang";
        boolean isExit = eventDirection != null && eventDirection.equalsIgnoreCase("EXIT");
        if (isExit) {
            return CheckCameraEntryResponse.builder()
                    .allowed(true)
                    .source(AccessSource.NONE)
                    .reason("Rời khu vực")
                    .cameraCode(camera.getCameraCode())
                    .areaId(null)
                    .areaName(corridorName)
                    .userCode(userCode != null && !userCode.isBlank() ? userCode : "UNKNOWN")
                    .fullName(userCode != null && !userCode.isBlank() ? userCode : "Người lạ / Chưa rõ mặt")
                    .build();
        }

        String cleanRoi = (roiLabel != null && !roiLabel.isBlank()) ? roiLabel.trim() : null;
        Optional<User> userOpt = userRepository.findByUserCodeAndDeletedAtIsNull(userCode);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            if (!Boolean.TRUE.equals(user.getIsActive())) {
                String msg = cleanRoi != null ? "[VÀO ROI: " + cleanRoi + "] Tài khoản đã bị vô hiệu hoá" : "Tài khoản đã bị vô hiệu hoá";
                CheckCameraEntryResponse denied = CheckCameraEntryResponse.builder()
                        .allowed(false)
                        .source(AccessSource.NONE)
                        .reason(msg)
                        .cameraCode(camera.getCameraCode())
                        .areaName(corridorName)
                        .userId(user.getId())
                        .userCode(user.getUserCode())
                        .build();
                recordAccessLogSafely(denied, at);
                return denied;
            }

            if (user.getRole() == Role.GUARD) {
                boolean onShift = guardShiftRepository == null || isGuardOnShift(user.getId(), at);
                String guardReason = onShift
                        ? "Bảo vệ đang trong ca trực tuần tra hành lang"
                        : "Bảo vệ không có ca trực trong khung giờ này";
                if (cleanRoi != null) {
                    guardReason = "[VÀO ROI: " + cleanRoi + "] " + guardReason;
                }
                CheckCameraEntryResponse resp = CheckCameraEntryResponse.builder()
                        .allowed(onShift)
                        .source(onShift ? AccessSource.ACCESS_LEVEL : AccessSource.NONE)
                        .reason(guardReason)
                        .cameraCode(camera.getCameraCode())
                        .areaName(corridorName)
                        .userId(user.getId())
                        .userCode(user.getUserCode())
                        .fullName(user.getFullName())
                        .build();
                recordAccessLogSafely(resp, at);
                return resp;
            }

            boolean afterHours = isAfterHoursForCorridor(at);
            String msg = afterHours
                    ? "Hành lang ngoài giờ hoạt động, không có quyền lưu thông"
                    : "Hành lang công cộng trong giờ hoạt động";
            if (cleanRoi != null) {
                msg = "[VÀO ROI: " + cleanRoi + "] " + msg;
            }
            CheckCameraEntryResponse resp = CheckCameraEntryResponse.builder()
                    .allowed(!afterHours)
                    .source(!afterHours ? AccessSource.ACCESS_LEVEL : AccessSource.NONE)
                    .reason(msg)
                    .cameraCode(camera.getCameraCode())
                    .areaName(corridorName)
                    .userId(user.getId())
                    .userCode(user.getUserCode())
                    .fullName(user.getFullName())
                    .build();
            recordAccessLogSafely(resp, at);
            return resp;
        }

        String deniedReason = cleanRoi != null ? "[VÀO ROI: " + cleanRoi + "] Người dùng không tồn tại" : "Người dùng không tồn tại";
        String effectiveUserCode = (userCode != null && !userCode.isBlank()) ? userCode : "UNKNOWN";
        CheckCameraEntryResponse denied = CheckCameraEntryResponse.builder()
                .allowed(false)
                .source(AccessSource.NONE)
                .reason(deniedReason)
                .cameraCode(camera.getCameraCode())
                .areaName(corridorName)
                .userCode(effectiveUserCode)
                .fullName("UNKNOWN".equals(effectiveUserCode) ? "Người lạ / Chưa rõ mặt" : null)
                .build();
        recordAccessLogSafely(denied, at);
        return denied;
    }

    private boolean isGuardOnShift(UUID guardId, OffsetDateTime at) {
        if (guardShiftRepository == null || guardId == null || at == null) {
            return false;
        }
        LocalDate date = at.atZoneSameInstant(VN_ZONE).toLocalDate();
        LocalTime time = at.atZoneSameInstant(VN_ZONE).toLocalTime();
        List<GuardShift> shifts = guardShiftRepository
                .findByGuardIdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(guardId, date.minusDays(1), date);
        if (shifts == null) {
            return false;
        }
        for (GuardShift shift : shifts) {
            if (shift.getStatus() != ShiftStatus.SCHEDULED && shift.getStatus() != ShiftStatus.CHECKED_IN) {
                continue;
            }
            LocalTime start = shift.getStartTime();
            LocalTime end = shift.getEndTime();
            LocalDate shiftDate = shift.getShiftDate();
            if (start != null && end != null && shiftDate != null) {
                if (!start.isAfter(end)) {
                    if (shiftDate.equals(date) && !time.isBefore(start) && !time.isAfter(end)) {
                        return true;
                    }
                } else {
                    if (shiftDate.equals(date) && !time.isBefore(start)) {
                        return true;
                    } else if (shiftDate.equals(date.minusDays(1)) && !time.isAfter(end)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isAfterHoursForCorridor(OffsetDateTime at) {
        if (systemConfigService == null || at == null) {
            return false;
        }
        try {
            LocalTime start = parseAfterHourTime(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START), ConfigKey.AI_AFTER_HOUR_START.getDefaultValue());
            LocalTime end = parseAfterHourTime(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_END), ConfigKey.AI_AFTER_HOUR_END.getDefaultValue());
            LocalTime now = at.atZoneSameInstant(VN_ZONE).toLocalTime();
            if (start.isBefore(end)) {
                return !now.isBefore(start) && now.isBefore(end);
            }
            if (start.isAfter(end)) {
                return !now.isBefore(start) || now.isBefore(end);
            }
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static LocalTime parseAfterHourTime(String raw, String fallback) {
        try {
            return LocalTime.parse(raw != null && !raw.isBlank() ? raw.trim() : fallback);
        } catch (DateTimeParseException e) {
            return LocalTime.parse(fallback);
        }
    }

    /**
     * Ghi nhật ký ra vào không được làm hỏng quyết định: lỗi ghi log (ràng buộc DB, mất kết nối...) chỉ log.error,
     * quyết định vẫn trả về cho AI. recordAccessLog chạy REQUIRES_NEW nên lỗi của nó không đánh dấu rollback giao dịch này.
     */
    private void recordAccessLogSafely(CheckCameraEntryResponse response, OffsetDateTime at) {
        if (cameraAccessLogService == null) {
            return;
        }
        try {
            cameraAccessLogService.recordAccessLog(response, at);
        } catch (RuntimeException e) {
            log.error("Không ghi được nhật ký ra vào tại camera [{}] cho [{}]: {}",
                    response.getCameraCode(), response.getUserCode(), e.getMessage(), e);
        }
    }
}
