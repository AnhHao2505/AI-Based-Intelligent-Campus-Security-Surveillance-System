package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessrequest.AccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.AccessRequestResponse;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.accessrequest.GroupAccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.IndividualAccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.MemberInfo;
import com.fa26se040.icss.dto.accessrequest.MemberLookupResult;
import com.fa26se040.icss.dto.accessrequest.ResolveMembersRequest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.ConcurrentReviewException;
import com.fa26se040.icss.exception.DuplicateResourceException;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.MemberLookupRateLimiter;
import com.fa26se040.icss.util.StringNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fa26se040.icss.enums.ConfigKey;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccessRequestService {

    private static final DateTimeFormatter VN_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private final AccessRequestRepository accessRequestRepository;
    private final AreaRepository areaRepository;
    private final UserRepository userRepository;
    private final InAppNotificationService inAppNotificationService;
    private final SystemConfigService systemConfigService;
    private final MemberLookupRateLimiter memberLookupRateLimiter;

    @Transactional
    public AccessRequestResponse createIndividualRequest(IndividualAccessRequestCreateRequest request, String actorEmail) {
        log.info("Creating individual access request by user: {}", actorEmail);

        User requester = getRequester(actorEmail);
        Area area = getArea(request.areaId());

        validateCommonRules(area, request.startTime(), request.endTime());

        // Validate overlap for requester only
        validateNoOverlap(area.getId(), request.startTime(), request.endTime(), List.of(requester));

        AccessRequest accessRequest = AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(RequestType.INDIVIDUAL)
                .purpose(request.purpose().trim())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .status(RequestStatus.PENDING)
                .members(new ArrayList<>())
                .build();

        AccessRequest saved = accessRequestRepository.save(accessRequest);
        log.info("Individual access request created with id: {}", saved.getId());

        // Bắn thông báo NEW_REQUEST_PENDING cho tất cả FACILITY_MANAGER
        try {
            List<User> fms = userRepository.findActiveUsersByRole(Role.FACILITY_MANAGER);
            if (!fms.isEmpty()) {
                String timeRange = InAppNotificationService.formatTimeRange(saved.getStartTime(), saved.getEndTime());
                String title = "Yêu cầu truy cập mới chờ duyệt";
                String message = requester.getFullName() + " vừa gửi yêu cầu truy cập cá nhân khu vực " + area.getName() + " (" + timeRange + ").";
                inAppNotificationService.createForUsers(fms, NotificationType.NEW_REQUEST_PENDING, title, message, saved.getId());
            }
        } catch (Exception e) {
            log.error("Failed to send NEW_REQUEST_PENDING notification for request {}", saved.getId(), e);
        }

        return mapToResponse(saved);
    }

    @Transactional
    public AccessRequestResponse createGroupRequest(GroupAccessRequestCreateRequest request, String actorEmail) {
        log.info("Creating group access request by user: {}", actorEmail);

        User requester = getRequester(actorEmail);
        Area area = getArea(request.areaId());

        validateCommonRules(area, request.startTime(), request.endTime());

        boolean groupAllowedInPrivate = systemConfigService.getBoolean(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE);
        if (!groupAllowedInPrivate && area.getAreaLevel() == AreaLevel.HIGHLY_CONFIDENTIAL) {
            throw new IllegalArgumentException("Khu vực bảo mật cao (HIGHLY_CONFIDENTIAL) chỉ cho phép đăng ký truy cập cá nhân (INDIVIDUAL)");
        }

        List<User> memberUsers = resolveAndValidateGroupMembers(request.memberUserCodes(), requester);

        // Validate overlap for both requester and all group members
        List<User> allParticipants = new ArrayList<>();
        allParticipants.add(requester);
        allParticipants.addAll(memberUsers);
        validateNoOverlap(area.getId(), request.startTime(), request.endTime(), allParticipants);

        AccessRequest accessRequest = AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(RequestType.GROUP)
                .purpose(request.purpose().trim())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .status(RequestStatus.PENDING)
                .members(new ArrayList<>())
                .build();

        for (User memberUser : memberUsers) {
            AccessRequestMember member = AccessRequestMember.builder()
                    .accessRequest(accessRequest)
                    .user(memberUser)
                    .build();
            accessRequest.getMembers().add(member);
        }

        AccessRequest saved = accessRequestRepository.save(accessRequest);
        log.info("Group access request created with id: {} and {} members", saved.getId(), memberUsers.size());

        // Bắn thông báo ADDED_TO_GROUP cho members và NEW_REQUEST_PENDING cho FMs
        try {
            String timeRange = InAppNotificationService.formatTimeRange(saved.getStartTime(), saved.getEndTime());

            // 1. ADDED_TO_GROUP cho từng thành viên trong nhóm (trừ người tạo)
            if (!memberUsers.isEmpty()) {
                String memberTitle = "Bạn được thêm vào nhóm yêu cầu truy cập";
                String memberMsg = requester.getFullName() + " đã thêm bạn vào yêu cầu truy cập khu vực " + area.getName() + " (" + timeRange + ").";
                inAppNotificationService.createForUsers(memberUsers, NotificationType.ADDED_TO_GROUP, memberTitle, memberMsg, saved.getId());
            }

            // 2. NEW_REQUEST_PENDING cho tất cả FACILITY_MANAGER
            List<User> fms = userRepository.findActiveUsersByRole(Role.FACILITY_MANAGER);
            if (!fms.isEmpty()) {
                String fmTitle = "Yêu cầu truy cập mới chờ duyệt";
                String fmMsg = requester.getFullName() + " vừa gửi yêu cầu truy cập nhóm (" + memberUsers.size() + " thành viên) khu vực " + area.getName() + " (" + timeRange + ").";
                inAppNotificationService.createForUsers(fms, NotificationType.NEW_REQUEST_PENDING, fmTitle, fmMsg, saved.getId());
            }
        } catch (Exception e) {
            log.error("Failed to send notifications for group request {}", saved.getId(), e);
        }

        return mapToResponse(saved);
    }

    @Transactional
    public AccessRequestResponse createRequest(AccessRequestCreateRequest request, String actorEmail) {
        if (request.requestType() == RequestType.GROUP) {
            GroupAccessRequestCreateRequest groupRequest = new GroupAccessRequestCreateRequest(
                    request.areaId(),
                    request.startTime(),
                    request.endTime(),
                    request.purpose(),
                    request.memberUserCodes()
            );
            return createGroupRequest(groupRequest, actorEmail);
        } else {
            IndividualAccessRequestCreateRequest individualRequest = new IndividualAccessRequestCreateRequest(
                    request.areaId(),
                    request.startTime(),
                    request.endTime(),
                    request.purpose()
            );
            return createIndividualRequest(individualRequest, actorEmail);
        }
    }

    @Transactional(readOnly = true)
    public List<MemberLookupResult> resolveMembers(ResolveMembersRequest request, String actorEmail) {
        memberLookupRateLimiter.checkRateLimit(actorEmail);

        if (request == null || request.userCodes() == null || request.userCodes().isEmpty()) {
            throw new IllegalArgumentException("Danh sách mã người dùng không được để trống");
        }

        int maxGroupMembers = systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS);
        if (request.userCodes().size() > maxGroupMembers) {
            throw new IllegalArgumentException("Số lượng mã cần tra cứu không được vượt quá " + maxGroupMembers + " mã");
        }

        Set<String> distinctCodes = new LinkedHashSet<>();
        for (String rawCode : request.userCodes()) {
            if (rawCode != null) {
                String norm = StringNormalizer.normCode(rawCode);
                if (!norm.isEmpty()) {
                    distinctCodes.add(norm);
                }
            }
        }

        if (distinctCodes.isEmpty()) {
            throw new IllegalArgumentException("Danh sách mã người dùng không được để trống");
        }

        List<User> foundUsers = userRepository.findAllByUserCodeIn(distinctCodes);
        Map<String, User> userMap = new HashMap<>();
        for (User user : foundUsers) {
            if (user.getUserCode() != null) {
                userMap.put(StringNormalizer.normCode(user.getUserCode()), user);
            }
        }

        final String commonFailureReason = "Không tìm thấy người dùng hợp lệ với mã này";
        List<MemberLookupResult> results = new ArrayList<>();
        for (String code : distinctCodes) {
            User user = userMap.get(code);
            if (user == null || Boolean.FALSE.equals(user.getIsActive()) || user.getDeletedAt() != null) {
                results.add(new MemberLookupResult(code, null, false, commonFailureReason));
            } else {
                results.add(new MemberLookupResult(user.getUserCode(), user.getFullName(), true, null));
            }
        }

        return results;
    }

    @Transactional(readOnly = true)
    public Page<AccessRequestResponse> getMyRequests(String actorEmail, RequestStatus status, Pageable pageable) {
        User requester = getRequester(actorEmail);
        Page<AccessRequest> page = accessRequestRepository.findMyRequests(requester.getId(), status, pageable);
        return page.map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public Page<AccessRequestResponse> getAllRequests(RequestStatus status, Pageable pageable) {
        Page<AccessRequest> page = accessRequestRepository.findAllRequests(status, pageable);
        return page.map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public AccessRequestResponse getRequestById(UUID id, String actorEmail, boolean isStaff) {
        AccessRequest accessRequest = accessRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));

        if (!isStaff) {
            boolean isRequester = accessRequest.getRequester().getEmail().equalsIgnoreCase(actorEmail);
            boolean isMember = accessRequest.getMembers() != null && accessRequest.getMembers().stream()
                    .anyMatch(m -> m.getUser() != null && m.getUser().getEmail().equalsIgnoreCase(actorEmail));

            if (!isRequester && !isMember) {
                throw new AccessDeniedException("Bạn không có quyền xem yêu cầu truy cập này");
            }
        }

        return mapToResponse(accessRequest);
    }

    @Transactional
    public AccessRequestResponse reviewRequest(UUID id, AccessRequestReviewRequest reviewRequest, String actorEmail) {
        log.info("Reviewing access request {} by reviewer {}", id, actorEmail);

        // 1. Validate input TRƯỚC khi chạm DB
        if (reviewRequest.status() != RequestStatus.APPROVED && reviewRequest.status() != RequestStatus.REJECTED) {
            throw new IllegalArgumentException("Trạng thái phê duyệt phải là APPROVED hoặc REJECTED");
        }

        if (reviewRequest.status() == RequestStatus.REJECTED) {
            if (reviewRequest.rejectionReason() == null || reviewRequest.rejectionReason().trim().isEmpty()) {
                throw new IllegalArgumentException("Vui lòng cung cấp lý do từ chối yêu cầu");
            }
        }

        // 2. Lấy thông tin reviewer từ actorEmail
        User reviewer = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người duyệt"));

        OffsetDateTime now = OffsetDateTime.now();
        String rejectionReason = reviewRequest.status() == RequestStatus.REJECTED
                ? reviewRequest.rejectionReason().trim()
                : null;

        // 3. Conditional UPDATE nguyên tử tại database (single source of truth)
        int updatedCount = accessRequestRepository.reviewIfPending(
                id,
                reviewRequest.status(),
                reviewer,
                now,
                rejectionReason,
                RequestStatus.PENDING
        );

        // 4. Nhánh thành công DUY NHẤT (rowCount == 1)
        if (updatedCount == 1) {
            // [RÀNG BUỘC NOTIFICATION / SIDE-EFFECTS]:
            // Mọi tác vụ phát sinh (gửi thông báo WebSocket, push notification, email, Kafka event, v.v.)
            // BẮT BUỘC CHỈ ĐƯỢC THỰC HIỆN TẠI ĐÂY — nhánh conditional update trả về 1 dòng thành công duy nhất.
            // TUYỆT ĐỐI KHÔNG thực hiện ở nhánh lỗi 409 hoặc trước khi câu lệnh UPDATE hoàn tất
            // để tránh gửi thông báo trùng lặp hoặc mâu thuẫn cho người dùng.
            AccessRequest updated = accessRequestRepository.findByIdWithDetails(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));
            log.info("Access request {} reviewed: {}", updated.getId(), updated.getStatus());

            try {
                List<User> recipients = new ArrayList<>();
                if (updated.getRequester() != null) {
                    recipients.add(updated.getRequester());
                }
                if (updated.getMembers() != null) {
                    for (var member : updated.getMembers()) {
                        if (member.getUser() != null) {
                            recipients.add(member.getUser());
                        }
                    }
                }

                String areaName = updated.getArea() != null ? updated.getArea().getName() : "khu vực";
                String timeRange = InAppNotificationService.formatTimeRange(updated.getStartTime(), updated.getEndTime());

                if (reviewRequest.status() == RequestStatus.APPROVED) {
                    String title = "Yêu cầu truy cập đã được phê duyệt";
                    String message = "Yêu cầu vào " + areaName + " (" + timeRange + ") đã được phê duyệt.";
                    inAppNotificationService.createForUsers(recipients, NotificationType.REQUEST_APPROVED, title, message, updated.getId());
                } else if (reviewRequest.status() == RequestStatus.REJECTED) {
                    String title = "Yêu cầu truy cập bị từ chối";
                    String message = "Yêu cầu vào " + areaName + " (" + timeRange + ") bị từ chối. Lý do: " + rejectionReason;
                    inAppNotificationService.createForUsers(recipients, NotificationType.REQUEST_REJECTED, title, message, updated.getId());
                }
            } catch (Exception e) {
                log.error("Failed to send review notification for request {}", updated.getId(), e);
            }

            return mapToResponse(updated);
        }

        // 5. Nhánh thất bại (rowCount == 0): Load lại entity để xác định nguyên nhân
        AccessRequest current = accessRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));

        if (current.getReviewer() != null && current.getReviewer().getFullName() != null) {
            String reviewerName = current.getReviewer().getFullName();
            String actionVerb = current.getStatus() == RequestStatus.APPROVED ? "phê duyệt" : "từ chối";
            OffsetDateTime time = current.getReviewedAt() != null ? current.getReviewedAt() : current.getUpdatedAt();
            String formattedTime = time != null
                    ? time.atZoneSameInstant(VN_ZONE).format(VN_DATE_TIME_FORMATTER)
                    : "";
            String timePart = !formattedTime.isEmpty() ? " lúc " + formattedTime : "";
            throw new ConcurrentReviewException(
                    "Yêu cầu này vừa được " + reviewerName + " " + actionVerb + timePart + ". Danh sách đã được làm mới."
            );
        } else {
            if (current.getStatus() == RequestStatus.CANCELLED) {
                throw new ConcurrentReviewException("Yêu cầu này đã được người tạo huỷ, không thể phê duyệt.");
            } else if (current.getStatus() == RequestStatus.EXPIRED) {
                throw new ConcurrentReviewException("Yêu cầu này đã hết hạn, không thể phê duyệt.");
            } else {
                throw new ConcurrentReviewException("Yêu cầu này đã ở trạng thái " + current.getStatus() + ", không thể phê duyệt.");
            }
        }
    }

    @Transactional
    public AccessRequestResponse cancelRequest(UUID id, String actorEmail) {
        log.info("Cancelling access request {} by user {}", id, actorEmail);

        // 1. Kiểm tra quyền chính chủ trước
        AccessRequest accessRequest = accessRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));

        if (!accessRequest.getRequester().getEmail().equalsIgnoreCase(actorEmail)) {
            throw new AccessDeniedException("Bạn không có quyền huỷ yêu cầu truy cập này");
        }

        // 2. Conditional UPDATE nguyên tử (XOÁ HOÀN TOÀN pre-check status != PENDING)
        OffsetDateTime now = OffsetDateTime.now();
        int updatedCount = accessRequestRepository.cancelIfPending(
                id,
                RequestStatus.CANCELLED,
                now,
                RequestStatus.PENDING
        );

        // 3. Nhánh thành công DUY NHẤT (rowCount == 1)
        if (updatedCount == 1) {
            // [RÀNG BUỘC NOTIFICATION / SIDE-EFFECTS]:
            // Mọi tác vụ phát sinh (gửi thông báo, email, Kafka event, v.v.)
            // CHỈ ĐƯỢC THỰC HIỆN TẠI ĐÂY. Tuyệt đối không thực hiện ở nhánh 409.
            AccessRequest updated = accessRequestRepository.findByIdWithDetails(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));
            log.info("Access request {} cancelled by requester", updated.getId());

            try {
                if (updated.getRequestType() == RequestType.GROUP && updated.getMembers() != null && !updated.getMembers().isEmpty()) {
                    List<User> membersToNotify = new ArrayList<>();
                    for (var member : updated.getMembers()) {
                        if (member.getUser() != null && !member.getUser().getId().equals(updated.getRequester().getId())) {
                            membersToNotify.add(member.getUser());
                        }
                    }
                    if (!membersToNotify.isEmpty()) {
                        String areaName = updated.getArea() != null ? updated.getArea().getName() : "khu vực";
                        String timeRange = InAppNotificationService.formatTimeRange(updated.getStartTime(), updated.getEndTime());
                        String requesterName = updated.getRequester() != null ? updated.getRequester().getFullName() : "Người tạo";
                        String title = "Yêu cầu truy cập nhóm đã bị huỷ";
                        String message = requesterName + " đã huỷ yêu cầu truy cập khu vực " + areaName + " (" + timeRange + ").";
                        inAppNotificationService.createForUsers(membersToNotify, NotificationType.REQUEST_CANCELLED, title, message, updated.getId());
                    }
                }
            } catch (Exception e) {
                log.error("Failed to send cancel notification for group request {}", updated.getId(), e);
            }

            return mapToResponse(updated);
        }

        // 4. Nhánh thất bại (rowCount == 0): Entity đã khác PENDING
        AccessRequest current = accessRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));

        if (current.getStatus() == RequestStatus.EXPIRED) {
            throw new ConcurrentReviewException("Yêu cầu này đã hết hạn, không thể huỷ.");
        }
        String reviewerName = (current.getReviewer() != null && current.getReviewer().getFullName() != null)
                ? current.getReviewer().getFullName()
                : "Facility Manager";
        String actionVerb = current.getStatus() == RequestStatus.APPROVED ? "phê duyệt" :
                (current.getStatus() == RequestStatus.REJECTED ? "từ chối" : "xử lý");
        throw new ConcurrentReviewException("Yêu cầu này vừa được " + reviewerName + " " + actionVerb + ", không thể huỷ.");
    }

    @Scheduled(cron = "${icss.scheduler.expire-overdue-cron:0 */15 * * * *}")
    @Transactional
    public int expireOverdueRequests() {
        OffsetDateTime now = OffsetDateTime.now();

        // 1. Lấy danh sách các request PENDING sắp bị expire TRƯỚC KHI bulk update để biết gửi thông báo cho ai
        List<AccessRequest> pendingOverdueRequests = List.of();
        try {
            pendingOverdueRequests = accessRequestRepository.findPendingOverdueRequests(RequestStatus.PENDING, now);
        } catch (Exception e) {
            log.error("Failed to fetch pending overdue requests before expiring", e);
        }

        // 2. Thực hiện bulk update chuyển sang EXPIRED
        int count = accessRequestRepository.expireOverdueRequests(
                RequestStatus.PENDING,
                RequestStatus.EXPIRED,
                now
        );
        if (count > 0) {
            log.info("Expired {} overdue pending access requests at {}", count, now);
        }

        // 3. Gửi thông báo ACCESS_DENIED cho requester của từng request bị expire
        if (!pendingOverdueRequests.isEmpty()) {
            for (AccessRequest req : pendingOverdueRequests) {
                try {
                    if (req.getRequester() != null) {
                        String areaName = req.getArea() != null ? req.getArea().getName() : "khu vực";
                        String timeRange = InAppNotificationService.formatTimeRange(req.getStartTime(), req.getEndTime());
                        String title = "Yêu cầu truy cập đã hết hạn";
                        String message = "Yêu cầu vào " + areaName + " (" + timeRange + ") đã hết hạn do không được xử lý trước giờ bắt đầu.";
                        inAppNotificationService.createForUser(req.getRequester(), NotificationType.ACCESS_DENIED, title, message, req.getId());
                    }
                } catch (Exception e) {
                    log.error("Failed to send ACCESS_DENIED notification for expired request {}", req.getId(), e);
                }
            }
        }

        return count;
    }

    private User getRequester(String actorEmail) {
        return userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người dùng yêu cầu"));
    }

    private Area getArea(UUID areaId) {
        return areaRepository.findByIdAndDeletedAtIsNull(areaId)
                .orElseThrow(() -> new ResourceNotFoundException("Khu vực không tồn tại hoặc đã bị vô hiệu hoá"));
    }

    @Transactional
    public AccessRequestResponse finishRequest(UUID id, String actorEmail) {
        log.info("Finishing access request {} by user {}", id, actorEmail);

        AccessRequest accessRequest = accessRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));

        if (accessRequest.getStatus() != RequestStatus.APPROVED) {
            throw new IllegalArgumentException("Chỉ yêu cầu truy cập ở trạng thái Đã duyệt (APPROVED) mới có thể chuyển sang Hoàn thành.");
        }

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người dùng"));

        boolean isRequester = accessRequest.getRequester().getEmail().equalsIgnoreCase(actorEmail);
        boolean isStaff = actor.getRole() == Role.FACILITY_MANAGER || actor.getRole() == Role.ADMIN;

        if (!isRequester && !isStaff) {
            throw new AccessDeniedException("Bạn không có quyền chuyển yêu cầu truy cập này sang Hoàn thành.");
        }

        accessRequest.setStatus(RequestStatus.FINISHED);
        accessRequest.setUpdatedAt(OffsetDateTime.now());

        AccessRequest updated = accessRequestRepository.save(accessRequest);
        log.info("Access request {} marked as FINISHED", updated.getId());

        return mapToResponse(updated);
    }

    private void validateCommonRules(Area area, OffsetDateTime startTime, OffsetDateTime endTime) {
        if (area.getAreaLevel() == AreaLevel.PUBLIC || area.getAreaLevel() == AreaLevel.INTERNAL_CONFIDENTIAL) {
            throw new IllegalArgumentException("Khu vực công cộng (PUBLIC) hoặc Nội bộ (INTERNAL_CONFIDENTIAL) không cần tạo yêu cầu truy cập.");
        }

        if (area.getAreaLevel() != AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED && area.getAreaLevel() != AreaLevel.HIGHLY_CONFIDENTIAL) {
            throw new IllegalArgumentException("Chỉ khu vực CONFIDENTIAL_CONTACT_REQUIRED hoặc HIGHLY_CONFIDENTIAL mới cho phép tạo yêu cầu truy cập.");
        }

        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("Thời gian bắt đầu phải trước thời gian kết thúc");
        }

        int bufferMinutes = systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_PAST_START_BUFFER_MINUTES);
        if (startTime.isBefore(OffsetDateTime.now().minusMinutes(bufferMinutes))) {
            throw new IllegalArgumentException("Thời gian bắt đầu không được ở trong quá khứ");
        }

        int maxAdvanceDays = systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_ADVANCE_DAYS);
        if (startTime.isAfter(OffsetDateTime.now().plusDays(maxAdvanceDays))) {
            throw new IllegalArgumentException("Thời gian bắt đầu không được vượt quá " + maxAdvanceDays + " ngày tới");
        }

        int maxDurationHours = systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_DURATION_HOURS);
        long durationMinutes = Duration.between(startTime, endTime).toMinutes();
        if (durationMinutes > (long) maxDurationHours * 60) {
            throw new IllegalArgumentException("Thời lượng truy cập tối đa không quá " + maxDurationHours + " giờ");
        }
    }

    private List<User> resolveAndValidateGroupMembers(List<String> rawCodes, User requester) {
        if (rawCodes == null || rawCodes.isEmpty()) {
            throw new IllegalArgumentException("Yêu cầu nhóm (GROUP) bắt buộc phải có danh sách thành viên");
        }

        Set<String> cleanCodes = new LinkedHashSet<>();
        for (String code : rawCodes) {
            if (code != null && !code.trim().isEmpty()) {
                String clean = code.trim();
                // Deduplicate and silently exclude requester
                if (!clean.equalsIgnoreCase(requester.getUserCode())) {
                    cleanCodes.add(clean);
                }
            }
        }

        if (cleanCodes.isEmpty()) {
            throw new IllegalArgumentException("Yêu cầu nhóm bắt buộc phải có ít nhất một thành viên khác ngoài người tạo");
        }

        int maxGroupMembers = systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS);
        if (cleanCodes.size() > maxGroupMembers) {
            throw new IllegalArgumentException("Số lượng thành viên trong nhóm tối đa " + maxGroupMembers + " người (không tính người tạo)");
        }

        List<User> memberUsers = new ArrayList<>();
        for (String code : cleanCodes) {
            User memberUser = userRepository.findByUserCode(code)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng với mã số: " + code));

            if (Boolean.FALSE.equals(memberUser.getIsActive()) || memberUser.getDeletedAt() != null) {
                throw new IllegalArgumentException("Tài khoản người dùng " + code + " đã bị vô hiệu hoá");
            }
            memberUsers.add(memberUser);
        }

        return memberUsers;
    }

    private void validateNoOverlap(UUID areaId, OffsetDateTime startTime, OffsetDateTime endTime, Collection<User> users) {
        if (users == null || users.isEmpty()) {
            return;
        }

        List<UUID> userIds = users.stream().map(User::getId).toList();
        List<RequestStatus> conflictingStatuses = List.of(RequestStatus.PENDING, RequestStatus.APPROVED);

        List<AccessRequest> overlappingRequests = accessRequestRepository.findOverlappingRequests(
                userIds,
                areaId,
                startTime,
                endTime,
                conflictingStatuses
        );

        if (overlappingRequests.isEmpty()) {
            return;
        }

        Set<String> overlappingUserDescriptions = new LinkedHashSet<>();
        for (User user : users) {
            boolean hasConflict = overlappingRequests.stream().anyMatch(ar ->
                    (ar.getRequester() != null && ar.getRequester().getId().equals(user.getId())) ||
                    (ar.getMembers() != null && ar.getMembers().stream()
                            .anyMatch(m -> m.getUser() != null && m.getUser().getId().equals(user.getId())))
            );

            if (hasConflict) {
                overlappingUserDescriptions.add(user.getUserCode() + " - " + user.getFullName());
            }
        }

        if (!overlappingUserDescriptions.isEmpty()) {
            throw new DuplicateResourceException(
                    "Yêu cầu bị trùng lịch với yêu cầu khác tại khu vực này cho người dùng: " +
                    String.join(", ", overlappingUserDescriptions)
            );
        }
    }

    private AccessRequestResponse mapToResponse(AccessRequest ar) {
        List<MemberInfo> memberInfos = List.of();
        if (ar.getMembers() != null && !ar.getMembers().isEmpty()) {
            memberInfos = ar.getMembers().stream()
                    .map(m -> new MemberInfo(
                            m.getUser() != null ? m.getUser().getId() : null,
                            m.getUser() != null ? m.getUser().getUserCode() : null,
                            m.getUser() != null ? m.getUser().getFullName() : null
                    ))
                    .toList();
        }

        return new AccessRequestResponse(
                ar.getId(),
                ar.getArea() != null ? ar.getArea().getId() : null,
                ar.getArea() != null ? ar.getArea().getCode() : null,
                ar.getArea() != null ? ar.getArea().getName() : null,
                ar.getArea() != null ? ar.getArea().getAreaLevel() : null,
                ar.getArea() != null ? ar.getArea().getBuilding() : null,
                ar.getArea() != null ? ar.getArea().getFloor() : null,
                ar.getRequester() != null ? ar.getRequester().getId() : null,
                ar.getRequester() != null ? ar.getRequester().getFullName() : null,
                ar.getRequester() != null ? ar.getRequester().getUserCode() : null,
                ar.getRequester() != null ? ar.getRequester().getEmail() : null,
                ar.getRequestType(),
                ar.getPurpose(),
                ar.getStartTime(),
                ar.getEndTime(),
                ar.getStatus(),
                ar.getReviewer() != null ? ar.getReviewer().getId() : null,
                ar.getReviewer() != null ? ar.getReviewer().getFullName() : null,
                ar.getReviewer() != null ? ar.getReviewer().getEmail() : null,
                ar.getReviewedAt(),
                ar.getRejectionReason(),
                memberInfos,
                ar.getCreatedAt(),
                ar.getUpdatedAt()
        );
    }
}
