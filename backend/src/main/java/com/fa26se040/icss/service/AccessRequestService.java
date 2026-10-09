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
import com.fa26se040.icss.context.AuditContext;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AccessRequestSnapshot;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.exception.AccessControlErrorCode;
import com.fa26se040.icss.exception.AccessControlException;
import com.fa26se040.icss.exception.ConcurrentReviewException;
import com.fa26se040.icss.exception.DuplicateResourceException;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AccessRequestSpecification;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.MemberLookupRateLimiter;
import com.fa26se040.icss.util.StringNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
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
    private final com.fa26se040.icss.repository.AccessRequestMemberRepository accessRequestMemberRepository;
    private final InAppNotificationService inAppNotificationService;
    private final SystemConfigService systemConfigService;
    private final MemberLookupRateLimiter memberLookupRateLimiter;
    private final AuditService auditService;

    @Transactional
    public AccessRequestResponse createIndividualRequest(IndividualAccessRequestCreateRequest request, String actorEmail) {
        log.info("Creating individual access request by user: {}", actorEmail);

        User requester = getRequester(actorEmail);
        Area area = getArea(request.areaId());

        validateCommonRules(area, request.startTime(), request.endTime());
        
        int requiredLevel = area.getAreaAccessLevel() != null ? area.getAreaAccessLevel() : 1;
        int requesterLevel = requester.getAccessLevel() != null ? requester.getAccessLevel() : 1;
        if (requesterLevel < requiredLevel) {
            throw new IllegalArgumentException(
                    "Người tạo đơn không đủ cấp độ truy cập vào khu vực (yêu cầu Level " + requiredLevel + "): "
                            + requester.getFullName() + " (" + requester.getUserCode() + ")"
            );
        }
        requireAreaAvailableForRequest(area, requester);

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

        auditService.record(
                AuditTargetType.ACCESS_REQUEST,
                AuditAction.CREATE,
                saved.getId().toString(),
                saved.getArea(),
                saved.getRequester(),
                null,
                AccessRequestSnapshot.from(saved),
                null,
                requester
        );

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
        if (!AreaService.isGroupRequestAllowed(area.getAreaLevel(), groupAllowedInPrivate)) {
            throw new IllegalArgumentException("Khu vực bảo mật cao (HIGHLY_CONFIDENTIAL) chỉ cho phép đăng ký truy cập cá nhân (INDIVIDUAL)");
        }

        // Kiểm tra cấp của người tạo đơn
        int requiredLevel = area.getAreaAccessLevel() != null ? area.getAreaAccessLevel() : 1;
        int requesterLevel = requester.getAccessLevel() != null ? requester.getAccessLevel() : 1;
        if (requesterLevel < requiredLevel) {
            throw new IllegalArgumentException(
                    "Người tạo đơn không đủ cấp độ truy cập vào khu vực (yêu cầu Level " + requiredLevel + "): "
                            + requester.getFullName() + " (" + requester.getUserCode() + ")"
            );
        }
        requireAreaAvailableForRequest(area, requester);

        List<User> memberUsers = resolveAndValidateGroupMembers(request.memberUserCodes(), requester);

        boolean isSponsorshipAllowed = systemConfigService.getSponsorAllowedAreaLevels().contains(area.getAreaLevel());
        if (!isSponsorshipAllowed) {
            List<String> unqualifiedMembers = new ArrayList<>();
            for (User memberUser : memberUsers) {
                int memberLevel = memberUser.getAccessLevel() != null ? memberUser.getAccessLevel() : 1;
                if (memberLevel < requiredLevel) {
                    unqualifiedMembers.add(memberUser.getFullName() + " (" + memberUser.getUserCode() + ")");
                }
            }
            if (!unqualifiedMembers.isEmpty()) {
                throw new IllegalArgumentException(
                        "Thành viên không đủ cấp độ truy cập vào khu vực (yêu cầu Level " + requiredLevel + "): "
                                + String.join(", ", unqualifiedMembers)
                );
            }
        }

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
            int memberLevel = memberUser.getAccessLevel() != null ? memberUser.getAccessLevel() : 1;
            boolean isSponsored = isSponsorshipAllowed && (memberLevel < requiredLevel);

            AccessRequestMember member = AccessRequestMember.builder()
                    .accessRequest(accessRequest)
                    .user(memberUser)
                    .sponsored(isSponsored)
                    .build();
            accessRequest.getMembers().add(member);
        }

        AccessRequest saved = accessRequestRepository.save(accessRequest);
        log.info("Group access request created with id: {} and {} members", saved.getId(), memberUsers.size());

        auditService.record(
                AuditTargetType.ACCESS_REQUEST,
                AuditAction.CREATE,
                saved.getId().toString(),
                saved.getArea(),
                saved.getRequester(),
                null,
                AccessRequestSnapshot.from(saved),
                null,
                requester
        );

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

        List<MemberLookupResult> results = new ArrayList<>();
        for (String code : distinctCodes) {
            User user = userMap.get(code);
            if (!isEligibleMember(user)) {
                results.add(new MemberLookupResult(code, null, false, INVALID_MEMBER_REASON));
            } else {
                results.add(new MemberLookupResult(user.getUserCode(), user.getFullName(), true, null));
            }
        }

        return results;
    }

    /** Giới hạn kỹ thuật của danh sách gợi ý trên UI (không phải tham số nghiệp vụ). */
    private static final int MEMBER_SUGGESTION_LIMIT = 10;
    /** Số bản ghi "thành viên gần đây" đọc tối đa trước khi gộp trùng theo người. */
    private static final int MEMBER_SUGGESTION_RECENT_SCAN = 200;
    private static final java.util.regex.Pattern MEMBER_SUGGESTION_QUERY =
            java.util.regex.Pattern.compile("^[A-Za-z0-9-]{1,20}$");

    /**
     * Lý do chung khi một mã không dùng được làm thành viên (không tồn tại / vô hiệu hoá / đã xoá / không phải
     * NORMAL_USER) — cùng một câu cho mọi trường hợp để không lộ tài khoản nào tồn tại (CLAUDE.md 9a).
     */
    static final String INVALID_MEMBER_REASON = "Không tìm thấy người dùng hợp lệ với mã này";

    /** BR-RQ-SP-05: lý do hệ thống huỷ đơn khi người tạo đơn bị vô hiệu hoá / xoá. */
    public static final String REQUESTER_INACTIVE_CANCEL_REASON = "Người tạo đơn không còn hoạt động";
    /** Nguồn audit (actor SYSTEM) khi huỷ đơn do người tạo bị vô hiệu hoá / xoá. */
    public static final String REQUESTER_DEACTIVATION_SOURCE = "REQUESTER_DEACTIVATION";

    /** BR-RQ-33: khu vực không nằm trong danh sách khu vực được xin (available-areas). */
    static final String AREA_NOT_REQUESTABLE_MESSAGE = "Khu vực này không nằm trong danh sách khu vực được phép xin truy cập";

    /**
     * BR-RQ-MEM-01: thành viên đơn nhóm phải là NORMAL_USER, đang hoạt động (isActive khác false), chưa xoá mềm.
     * Dùng chung cho resolveMembers, tạo đơn nhóm và gợi ý mã thành viên.
     */
    static boolean isEligibleMember(User user) {
        return user != null
                && user.getRole() == Role.NORMAL_USER
                && !Boolean.FALSE.equals(user.getIsActive())
                && user.getDeletedAt() == null;
    }

    /**
     * Gợi ý mã thành viên cho form đơn nhóm. q trống -> thành viên gần đây trong đơn của chính người gọi;
     * q có giá trị -> mã bắt đầu bằng q. Dùng chung rate limit với resolve-members (vượt ngưỡng -> 429).
     * Chỉ trả mã + họ tên, loại chính người gọi, tối đa MEMBER_SUGGESTION_LIMIT.
     */
    @Transactional(readOnly = true)
    public List<com.fa26se040.icss.dto.accessrequest.MemberSuggestion> suggestMembers(String q, String actorEmail) {
        memberLookupRateLimiter.checkRateLimit(actorEmail);
        User caller = getRequester(actorEmail);
        String query = q == null ? "" : q.trim();

        List<User> candidates;
        if (query.isEmpty()) {
            candidates = accessRequestMemberRepository
                    .findRecentByRequesterIdWithUser(caller.getId(), Role.NORMAL_USER,
                            PageRequest.of(0, MEMBER_SUGGESTION_RECENT_SCAN))
                    .stream()
                    .map(AccessRequestMember::getUser)
                    .toList();
        } else {
            if (!MEMBER_SUGGESTION_QUERY.matcher(query).matches()) {
                throw new IllegalArgumentException("Mã tìm kiếm chỉ gồm chữ, số hoặc dấu gạch ngang, dài 1–20 ký tự");
            }
            candidates = userRepository
                    .findTop50ByUserCodeStartingWithIgnoreCaseAndRoleAndIsActiveTrueAndDeletedAtIsNullOrderByUserCodeAsc(
                            query.toUpperCase(java.util.Locale.ROOT), Role.NORMAL_USER);
        }

        Map<UUID, com.fa26se040.icss.dto.accessrequest.MemberSuggestion> picked = new java.util.LinkedHashMap<>();
        for (User user : candidates) {
            if (!isEligibleMember(user) || user.getId().equals(caller.getId())) {
                continue;
            }
            picked.putIfAbsent(user.getId(),
                    new com.fa26se040.icss.dto.accessrequest.MemberSuggestion(user.getUserCode(), user.getFullName()));
            if (picked.size() >= MEMBER_SUGGESTION_LIMIT) {
                break;
            }
        }
        return new ArrayList<>(picked.values());
    }

    @Transactional(readOnly = true)
    public Page<AccessRequestResponse> getMyRequests(String actorEmail, RequestStatus status, UUID areaId, Pageable pageable) {
        User requester = getRequester(actorEmail);
        Pageable effectivePageable = pageable;
        if (pageable.getSort().isUnsorted()) {
            effectivePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        Specification<AccessRequest> spec = AccessRequestSpecification.filter(requester.getId(), status, areaId);
        Page<AccessRequest> page = accessRequestRepository.findAll(spec, effectivePageable);
        return page.map(ar -> {
            boolean isReq = ar.getRequester() != null && requester.getId().equals(ar.getRequester().getId());
            return mapToResponse(ar, isReq);
        });
    }

    @Transactional(readOnly = true)
    public Page<AccessRequestResponse> getMyRequests(String actorEmail, RequestStatus status, Pageable pageable) {
        return getMyRequests(actorEmail, status, null, pageable);
    }

    @Transactional(readOnly = true)
    public Page<AccessRequestResponse> getAllRequests(RequestStatus status, UUID areaId, Pageable pageable) {
        Pageable effectivePageable = pageable;
        if (pageable.getSort().isUnsorted()) {
            effectivePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "createdAt"));
        }
        Specification<AccessRequest> spec = AccessRequestSpecification.filter(null, status, areaId);
        Page<AccessRequest> page = accessRequestRepository.findAll(spec, effectivePageable);
        return page.map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public Page<AccessRequestResponse> getAllRequests(RequestStatus status, Pageable pageable) {
        return getAllRequests(status, null, pageable);
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
            int len = reviewRequest.rejectionReason().trim().length();
            if (len < 10 || len > 500) {
                throw new IllegalArgumentException("Lý do từ chối phải có từ 10 đến 500 ký tự");
            }
        }

        // BR-RQ-06: người duyệt phải khác người tạo đơn — áp cho cả APPROVED và REJECTED, kiểm trước mọi ghi DB nên đơn giữ PENDING.
        // Không phải pre-check trạng thái: kết quả conditional UPDATE vẫn là nguồn chân lý cho 409.
        User reviewer = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người duyệt"));
        AccessRequest targetReq = accessRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));
        if (targetReq.getRequester() != null && targetReq.getRequester().getId().equals(reviewer.getId())) {
            throw new AccessControlException(AccessControlErrorCode.ERR_AC_006);
        }

        // BR-RQ-02: Kiểm tra lại cấp độ truy cập và cấu hình nhóm khi FM phê duyệt (APPROVED)
        if (reviewRequest.status() == RequestStatus.APPROVED) {
            AccessRequest pendingReq = accessRequestRepository.findByIdWithDetails(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));

            if (pendingReq.getStatus() == RequestStatus.PENDING) {
                Area currentArea = areaRepository.findById(pendingReq.getArea().getId())
                        .orElse(pendingReq.getArea());

                boolean groupAllowedInPrivate = systemConfigService.getBoolean(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE);
                if (pendingReq.getRequestType() == RequestType.GROUP
                        && currentArea.getAreaLevel() == AreaLevel.HIGHLY_CONFIDENTIAL
                        && !groupAllowedInPrivate) {
                    throw new IllegalArgumentException("Khu vực bảo mật cao (HIGHLY_CONFIDENTIAL) không cho phép duyệt đơn truy cập nhóm (GROUP)");
                }

                int requiredLevel = currentArea.getAreaAccessLevel() != null ? currentArea.getAreaAccessLevel() : 1;

                if (pendingReq.getRequester() != null) {
                    User freshRequester = userRepository.findById(pendingReq.getRequester().getId())
                            .orElse(pendingReq.getRequester());
                    int requesterLevel = freshRequester.getAccessLevel() != null ? freshRequester.getAccessLevel() : 1;
                    if (requesterLevel < requiredLevel) {
                        throw new IllegalArgumentException(
                                "Người tạo đơn không còn đủ cấp độ truy cập vào khu vực (yêu cầu Level " + requiredLevel + "): "
                                        + freshRequester.getFullName() + " (" + freshRequester.getUserCode() + ")"
                        );
                    }
                }

                if (pendingReq.getRequestType() == RequestType.GROUP && pendingReq.getMembers() != null) {
                    boolean isSponsorshipAllowed = systemConfigService.getSponsorAllowedAreaLevels().contains(currentArea.getAreaLevel());
                    List<String> unqualifiedMembers = new ArrayList<>();

                    for (AccessRequestMember m : pendingReq.getMembers()) {
                        if (m.getUser() != null) {
                            User freshMember = userRepository.findById(m.getUser().getId())
                                    .orElse(m.getUser());

                            if (Boolean.FALSE.equals(freshMember.getIsActive()) || freshMember.getDeletedAt() != null) {
                                throw new IllegalArgumentException("Thành viên " + freshMember.getUserCode() + " đã bị vô hiệu hoá hoặc xoá khỏi hệ thống");
                            }

                            boolean isSponsored = Boolean.TRUE.equals(m.getSponsored());
                            if (!isSponsorshipAllowed || !isSponsored) {
                                int memberLevel = freshMember.getAccessLevel() != null ? freshMember.getAccessLevel() : 1;
                                if (memberLevel < requiredLevel) {
                                    unqualifiedMembers.add(freshMember.getFullName() + " (" + freshMember.getUserCode() + ")");
                                }
                            }
                        }
                    }

                    if (!unqualifiedMembers.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Thành viên không đủ cấp độ truy cập vào khu vực (yêu cầu Level " + requiredLevel + "): "
                                        + String.join(", ", unqualifiedMembers)
                        );
                    }
                }
            }
        }

        // 2. reviewer đã lấy ở bước BR-RQ-06 phía trên

        OffsetDateTime now = OffsetDateTime.now();
        String rejectionReason = reviewRequest.status() == RequestStatus.REJECTED
                ? reviewRequest.rejectionReason().trim()
                : null;

        AccessRequest beforeReq = accessRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));
        AccessRequestSnapshot beforeSnapshot = AccessRequestSnapshot.from(beforeReq);

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

            AccessRequestSnapshot afterSnapshot = AccessRequestSnapshot.from(updated);
            AuditAction auditAction = reviewRequest.status() == RequestStatus.APPROVED
                    ? AuditAction.APPROVE
                    : AuditAction.REJECT;

            auditService.record(
                    AuditTargetType.ACCESS_REQUEST,
                    auditAction,
                    updated.getId().toString(),
                    updated.getArea(),
                    updated.getRequester(),
                    beforeSnapshot,
                    afterSnapshot,
                    rejectionReason,
                    reviewer
            );

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

        AccessRequestSnapshot beforeSnapshot = AccessRequestSnapshot.from(accessRequest);

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
            // Step 5b (BR-TC-15): người dùng tự huỷ -> cancel_source USER, cancelled_by = người huỷ
            accessRequestRepository.recordCancellation(
                    id, com.fa26se040.icss.enums.CancelSource.USER, accessRequest.getRequester(), null, RequestStatus.CANCELLED);
            AccessRequest updated = accessRequestRepository.findByIdWithDetails(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));
            log.info("Access request {} cancelled by requester", updated.getId());

            auditService.record(
                    AuditTargetType.ACCESS_REQUEST,
                    AuditAction.CANCEL,
                    updated.getId().toString(),
                    updated.getArea(),
                    updated.getRequester(),
                    beforeSnapshot,
                    AccessRequestSnapshot.from(updated),
                    null,
                    accessRequest.getRequester()
            );

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
        return AuditContext.runAsSystem("EXPIRE_OVERDUE_REQUESTS_JOB", () -> {
            OffsetDateTime now = OffsetDateTime.now();

            // 1. Khoá (PESSIMISTIC_WRITE) đúng các đơn sẽ bị expire. Lỗi ở bước này phải ném ra để
            //    cả transaction rollback: không được expire đơn nào khi chưa ghi được audit (LA4).
            List<AccessRequest> overdueRequests = accessRequestRepository
                    .findPendingOverdueRequestsForUpdate(RequestStatus.PENDING, now);
            if (overdueRequests == null || overdueRequests.isEmpty()) {
                return 0;
            }

            // 2. Chỉ update theo danh sách id đã khoá -> tập bị EXPIRED trùng khớp tập được ghi audit
            List<UUID> ids = overdueRequests.stream().map(AccessRequest::getId).toList();
            int count = accessRequestRepository.expireOverdueRequestsByIds(
                    ids,
                    RequestStatus.PENDING,
                    RequestStatus.EXPIRED,
                    now
            );
            if (count != ids.size()) {
                log.error("Expire overdue requests mismatch: locked {} but updated {} — rolling back", ids.size(), count);
                throw new IllegalStateException("Số đơn cập nhật (" + count + ") khác số đơn đã khoá (" + ids.size() + ")");
            }
            log.info("Expired {} overdue pending access requests at {}", count, now);

            // 3. Ghi audit cho đúng các đơn đã expire (cùng transaction; lỗi -> rollback toàn bộ)
            for (AccessRequest req : overdueRequests) {
                AccessRequestSnapshot beforeSnapshot = AccessRequestSnapshot.from(req);
                AccessRequestSnapshot afterSnapshot = new AccessRequestSnapshot(
                        req.getId(),
                        req.getRequestType() != null ? req.getRequestType().name() : null,
                        RequestStatus.EXPIRED.name(),
                        req.getArea() != null ? req.getArea().getId() : null,
                        req.getRequester() != null ? req.getRequester().getId() : null,
                        req.getStartTime(),
                        req.getEndTime(),
                        req.getPurpose(),
                        req.getRejectionReason()
                );

                auditService.record(
                        AuditTargetType.ACCESS_REQUEST,
                        AuditAction.EXPIRE,
                        req.getId().toString(),
                        req.getArea(),
                        req.getRequester(),
                        beforeSnapshot,
                        afterSnapshot,
                        "Hết hạn tự động do quá giờ bắt đầu"
                );
            }

            // 4. Thông báo chỉ gửi SAU KHI commit thành công -> không báo "đã hết hạn" cho đơn bị rollback
            List<ExpiredNotice> notices = overdueRequests.stream()
                    .filter(req -> req.getRequester() != null)
                    .map(req -> new ExpiredNotice(
                            req.getRequester(),
                            req.getId(),
                            req.getArea() != null ? req.getArea().getName() : "khu vực",
                            InAppNotificationService.formatTimeRange(req.getStartTime(), req.getEndTime())))
                    .toList();
            runAfterCommit(() -> notices.forEach(this::sendExpiredNotice));

            return count;
        });
    }

    /**
     * BR-RQ-SP-05: người tạo đơn bị vô hiệu hoá / xoá -> hệ thống huỷ mọi đơn người đó đứng tên đang PENDING / APPROVED
     * và chưa kết thúc (end_time > now). Chạy trong transaction của thao tác vô hiệu hoá / xoá (lỗi -> rollback cả hai).
     * Tái sử dụng cơ chế huỷ của hệ thống: conditional UPDATE {@code cancelBySystemIfStatus} (CancelSource.SYSTEM),
     * audit ACCESS_REQUEST / CANCEL với actor SYSTEM, thông báo REQUEST_SYSTEM_CANCELLED gửi SAU commit cho thành viên.
     * Đơn vừa bị thao tác khác đổi trạng thái thì bỏ qua. Kích hoạt lại tài khoản không khôi phục đơn đã huỷ.
     *
     * @return số đơn đã huỷ
     */
    @Transactional
    public int cancelActiveRequestsOfRequester(UUID requesterId, String reason) {
        OffsetDateTime now = OffsetDateTime.now();
        List<AccessRequest> candidates = accessRequestRepository.findNotEndedByRequesterWithParticipants(
                requesterId, List.of(RequestStatus.PENDING, RequestStatus.APPROVED), now);

        List<SystemCancelledNotice> notices = new ArrayList<>();
        int cancelled = 0;
        for (AccessRequest r : candidates) {
            AccessRequestSnapshot before = AccessRequestSnapshot.from(r);
            int updated = accessRequestRepository.cancelBySystemIfStatus(
                    r.getId(), r.getStatus(), RequestStatus.CANCELLED, com.fa26se040.icss.enums.CancelSource.SYSTEM, reason, now);
            if (updated != 1) {
                log.info("Access request {} changed concurrently, skip system cancel ({})", r.getId(), REQUESTER_DEACTIVATION_SOURCE);
                continue;
            }
            // Đồng bộ entity trong persistence context với dòng vừa UPDATE
            r.setStatus(RequestStatus.CANCELLED);
            r.setCancelSource(com.fa26se040.icss.enums.CancelSource.SYSTEM);
            r.setCancelledBy(null);
            r.setCancelReason(reason);
            r.setUpdatedAt(now);
            auditService.record(
                    AuditTargetType.ACCESS_REQUEST,
                    AuditAction.CANCEL,
                    r.getId().toString(),
                    r.getArea(),
                    r.getRequester(),
                    before,
                    AccessRequestSnapshot.from(r),
                    reason,
                    com.fa26se040.icss.dto.audit.AuditActor.system(REQUESTER_DEACTIVATION_SOURCE)
            );
            cancelled++;

            // Người nhận: thành viên của đơn (người tạo đã không còn hoạt động)
            List<User> members = new ArrayList<>();
            if (r.getMembers() != null) {
                for (AccessRequestMember m : r.getMembers()) {
                    User u = m.getUser();
                    if (u != null && (r.getRequester() == null || !u.getId().equals(r.getRequester().getId()))) {
                        members.add(u);
                    }
                }
            }
            if (!members.isEmpty()) {
                notices.add(new SystemCancelledNotice(r.getId(), members,
                        "Đơn truy cập khu vực " + (r.getArea() != null ? r.getArea().getName() : "khu vực") + " ("
                                + InAppNotificationService.formatTimeRange(r.getStartTime(), r.getEndTime())
                                + ") đã bị hệ thống huỷ. Lý do: " + reason + "."));
            }
        }
        if (cancelled > 0) {
            log.info("Cancelled {} active access requests of requester {} ({})", cancelled, requesterId, REQUESTER_DEACTIVATION_SOURCE);
        }
        runAfterCommit(() -> notices.forEach(this::sendSystemCancelledNotice));
        return cancelled;
    }

    private record SystemCancelledNotice(UUID requestId, List<User> recipients, String message) {}

    /** Cùng loại / tiêu đề / dạng câu với thông báo hệ thống huỷ đơn khi đổi loại khu vực (AreaService, BR-TC-10). */
    private void sendSystemCancelledNotice(SystemCancelledNotice n) {
        try {
            inAppNotificationService.createForUsers(n.recipients(), NotificationType.REQUEST_SYSTEM_CANCELLED,
                    "Đơn truy cập bị hệ thống huỷ", n.message(), n.requestId(), InAppNotificationService.REF_TYPE_ACCESS_REQUEST);
        } catch (Exception e) {
            log.error("Failed to send REQUEST_SYSTEM_CANCELLED for request {}: {}", n.requestId(), e.getMessage(), e);
        }
    }

    private record ExpiredNotice(User requester, UUID requestId, String areaName, String timeRange) {}

    private void sendExpiredNotice(ExpiredNotice n) {
        try {
            String title = "Yêu cầu truy cập đã hết hạn";
            String message = "Yêu cầu vào " + n.areaName() + " (" + n.timeRange() + ") đã hết hạn do không được xử lý trước giờ bắt đầu.";
            inAppNotificationService.createForUser(n.requester(), NotificationType.ACCESS_DENIED, title, message, n.requestId());
        } catch (Exception e) {
            log.error("Failed to send ACCESS_DENIED notification for expired request {}", n.requestId(), e);
        }
    }

    private void runAfterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }

    private User getRequester(String actorEmail) {
        return userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người dùng yêu cầu"));
    }

    private Area getArea(UUID areaId) {
        return areaRepository.findByIdAndDeletedAtIsNull(areaId)
                .orElseThrow(() -> new ResourceNotFoundException("Khu vực không tồn tại hoặc đã bị vô hiệu hoá"));
    }

    /** BR-RQ-33: cùng tiêu chí với danh sách available-areas (AreaService.isAvailableForRequest) -> 400. */
    private void requireAreaAvailableForRequest(Area area, User requester) {
        if (!AreaService.isAvailableForRequest(area, requester, systemConfigService.getSponsorAllowedAreaLevels())) {
            throw new IllegalArgumentException(AREA_NOT_REQUESTABLE_MESSAGE);
        }
    }

    @Transactional
    public AccessRequestResponse finishRequest(UUID id, String reason, String actorEmail) {
        log.info("Finishing access request {} by user {}", id, actorEmail);

        AccessRequest accessRequest = accessRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));

        if (accessRequest.getStatus() != RequestStatus.APPROVED) {
            throw new IllegalArgumentException("Chỉ yêu cầu truy cập ở trạng thái Đã duyệt (APPROVED) mới có thể chuyển sang Hoàn thành.");
        }

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người dùng"));

        // BR-RQ-44: chỉ FACILITY_MANAGER (khớp @PreAuthorize của controller); requester / ADMIN không được hoàn thành đơn
        if (actor.getRole() != Role.FACILITY_MANAGER) {
            throw new AccessDeniedException("Bạn không có quyền chuyển yêu cầu truy cập này sang Hoàn thành.");
        }

        // BR-RQ-46: chỉ hoàn thành đơn đã tới giờ bắt đầu (now >= startTime); chưa bắt đầu thì không phải "kết thúc"
        OffsetDateTime now = OffsetDateTime.now();
        if (accessRequest.getStartTime() != null && now.isBefore(accessRequest.getStartTime())) {
            throw new AccessControlException(AccessControlErrorCode.ERR_AC_007,
                    (Object) accessRequest.getStartTime().atZoneSameInstant(VN_ZONE).format(VN_DATE_TIME_FORMATTER));
        }
        String finishReason = reason != null ? reason.trim() : null;

        AccessRequestSnapshot beforeSnapshot = AccessRequestSnapshot.from(accessRequest);

        accessRequest.setStatus(RequestStatus.FINISHED);
        accessRequest.setUpdatedAt(now);

        AccessRequest updated = accessRequestRepository.save(accessRequest);
        log.info("Access request {} marked as FINISHED", updated.getId());

        auditService.record(
                AuditTargetType.ACCESS_REQUEST,
                AuditAction.FINISH,
                updated.getId().toString(),
                updated.getArea(),
                updated.getRequester(),
                beforeSnapshot,
                AccessRequestSnapshot.from(updated),
                finishReason,
                actor
        );

        // BR-RQ-46: báo người tạo đơn + thành viên nhóm (đơn hết hiệu lực sớm, họ không còn vào được theo đơn này).
        // Lỗi gửi thông báo không làm hỏng thao tác hoàn thành (InAppNotificationService chạy REQUIRES_NEW).
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
            String title = "Yêu cầu truy cập đã được kết thúc";
            String message = "Yêu cầu vào " + areaName + " (" + timeRange + ") đã được " + actor.getFullName()
                    + " chuyển sang Hoàn thành, không còn hiệu lực để ra vào. Lý do: " + finishReason;
            inAppNotificationService.createForUsers(recipients, NotificationType.REQUEST_FINISHED, title, message, updated.getId());
        } catch (Exception e) {
            log.error("Failed to send finish notification for request {}", updated.getId(), e);
        }

        return mapToResponse(updated);
    }

    /**
     * BR-RQ-47: FM huỷ đơn APPROVED chưa tới giờ bắt đầu (bổ sung B-03: chưa bắt đầu thì không "Hoàn thành" mà huỷ).
     * Conditional UPDATE nguyên tử (status = APPROVED AND startTime > now) là nguồn chân lý duy nhất, không pre-check.
     * Nguồn huỷ ghi STAFF (V73 thêm vào CHECK) với cancelled_by = FM và cancel_reason = lý do.
     */
    @Transactional
    public AccessRequestResponse cancelApprovedRequest(UUID id, String reason, String actorEmail) {
        log.info("Staff cancelling approved access request {} by user {}", id, actorEmail);

        AccessRequest accessRequest = accessRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));

        User actor = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người dùng"));
        // Khớp @PreAuthorize của controller: chỉ FACILITY_MANAGER
        if (actor.getRole() != Role.FACILITY_MANAGER) {
            throw new AccessDeniedException("Bạn không có quyền huỷ yêu cầu truy cập đã được duyệt.");
        }

        AccessRequestSnapshot beforeSnapshot = AccessRequestSnapshot.from(accessRequest);
        String cancelReason = reason != null ? reason.trim() : null;
        OffsetDateTime now = OffsetDateTime.now();

        int updatedCount = accessRequestRepository.cancelIfApprovedNotStarted(
                id, RequestStatus.CANCELLED, now, RequestStatus.APPROVED);

        if (updatedCount == 1) {
            // [RÀNG BUỘC NOTIFICATION / SIDE-EFFECTS]: chỉ ở nhánh 1 dòng
            accessRequestRepository.recordCancellation(
                    id, com.fa26se040.icss.enums.CancelSource.STAFF, actor, cancelReason, RequestStatus.CANCELLED);
            AccessRequest updated = accessRequestRepository.findByIdWithDetails(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));
            log.info("Approved access request {} cancelled by FM {}", updated.getId(), actor.getId());

            auditService.record(
                    AuditTargetType.ACCESS_REQUEST,
                    AuditAction.CANCEL,
                    updated.getId().toString(),
                    updated.getArea(),
                    updated.getRequester(),
                    beforeSnapshot,
                    AccessRequestSnapshot.from(updated),
                    cancelReason,
                    actor
            );

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
                String title = "Yêu cầu truy cập đã duyệt bị huỷ";
                String message = "Yêu cầu vào " + areaName + " (" + timeRange + ") đã được " + actor.getFullName()
                        + " huỷ trước giờ bắt đầu. Lý do: " + cancelReason;
                inAppNotificationService.createForUsers(recipients, NotificationType.REQUEST_CANCELLED, title, message, updated.getId());
            } catch (Exception e) {
                log.error("Failed to send staff-cancel notification for request {}", updated.getId(), e);
            }

            return mapToResponse(updated);
        }

        // 0 dòng: đọc lại để báo đúng nguyên nhân
        AccessRequest current = accessRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));
        if (current.getStatus() == RequestStatus.APPROVED) {
            String start = current.getStartTime() != null
                    ? current.getStartTime().atZoneSameInstant(VN_ZONE).format(VN_DATE_TIME_FORMATTER)
                    : "";
            throw new AccessControlException(AccessControlErrorCode.ERR_AC_008, (Object) start);
        }
        if (current.getStatus() == RequestStatus.PENDING) {
            throw new IllegalArgumentException("Chỉ huỷ được yêu cầu đã được duyệt; yêu cầu đang chờ duyệt hãy dùng Từ chối.");
        }
        throw new ConcurrentReviewException("Yêu cầu này đã ở trạng thái " + current.getStatus() + ", không thể huỷ.");
    }

    private void validateCommonRules(Area area, OffsetDateTime startTime, OffsetDateTime endTime) {
        if (area.getAreaLevel() == AreaLevel.PUBLIC) {
            throw new IllegalArgumentException("Khu vực công cộng (PUBLIC) không cần tạo yêu cầu truy cập.");
        }

        if (!startTime.isBefore(endTime)) {
            throw new IllegalArgumentException("Thời gian bắt đầu phải trước thời gian kết thúc");
        }

        int bufferMinutes = systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_PAST_START_BUFFER_MINUTES);
        if (startTime.isBefore(OffsetDateTime.now().minusMinutes(bufferMinutes))) {
            throw new IllegalArgumentException("Thời gian bắt đầu không được ở trong quá khứ");
        }

        int maxAdvanceDays = systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_ADVANCE_DAYS);
        OffsetDateTime maxAllowedTime = OffsetDateTime.now().plusDays(maxAdvanceDays);
        if (startTime.isAfter(maxAllowedTime)) {
            long actualDays = java.time.temporal.ChronoUnit.DAYS.between(OffsetDateTime.now().toLocalDate(), startTime.toLocalDate());
            if (actualDays <= maxAdvanceDays) {
                actualDays = maxAdvanceDays + 1;
            }
            throw new IllegalArgumentException("Thời gian bắt đầu không được vượt quá " + maxAdvanceDays + " ngày tới (bạn chọn " + actualDays + " ngày)");
        }

        int maxDurationHours = systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_DURATION_HOURS);
        long durationMinutes = Duration.between(startTime, endTime).toMinutes();
        if (durationMinutes > (long) maxDurationHours * 60) {
            long inputHours = durationMinutes % 60 == 0 ? durationMinutes / 60 : (durationMinutes + 59) / 60;
            throw new IllegalArgumentException("Thời lượng truy cập tối đa không quá " + maxDurationHours + " giờ (bạn nhập " + inputHours + " giờ)");
        }
    }

    private void validateAccessLevels(Area area, List<User> participants) {
        if (area.getAreaAccessLevel() == null || participants == null || participants.isEmpty()) {
            return;
        }
        int requiredLevel = area.getAreaAccessLevel();
        List<String> unqualified = new ArrayList<>();
        for (User user : participants) {
            int userLevel = user.getAccessLevel() != null ? user.getAccessLevel() : 1;
            if (userLevel < requiredLevel) {
                unqualified.add(user.getFullName() + " (" + user.getUserCode() + ")");
            }
        }
        if (!unqualified.isEmpty()) {
            throw new IllegalArgumentException(
                    "Người dùng không đủ cấp độ truy cập vào khu vực (yêu cầu Level " + requiredLevel + "): "
                            + String.join(", ", unqualified)
            );
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
            throw new IllegalArgumentException("Số lượng thành viên trong nhóm tối đa " + maxGroupMembers + " người (không tính người tạo) (bạn đã chọn " + cleanCodes.size() + " người)");
        }

        List<User> memberUsers = new ArrayList<>();
        for (String code : cleanCodes) {
            // Không tồn tại / vô hiệu hoá / đã xoá / role khác NORMAL_USER (BR-RQ-MEM-01): CÙNG mã lỗi (400) và CÙNG câu
            // với resolve-members, không để lộ mã nào tồn tại hay tồn tại nhưng bị khoá (CLAUDE.md 9a)
            User memberUser = userRepository.findByUserCode(code).orElse(null);
            if (!isEligibleMember(memberUser)) {
                throw new IllegalArgumentException(code + ": " + INVALID_MEMBER_REASON);
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
        return mapToResponse(ar, null);
    }

    private AccessRequestResponse mapToResponse(AccessRequest ar, Boolean isRequester) {
        List<MemberInfo> memberInfos = List.of();
        if (ar.getMembers() != null && !ar.getMembers().isEmpty()) {
            memberInfos = ar.getMembers().stream()
                    .map(m -> new MemberInfo(
                            m.getUser() != null ? m.getUser().getId() : null,
                            m.getUser() != null ? m.getUser().getUserCode() : null,
                            m.getUser() != null ? m.getUser().getFullName() : null,
                            m.getSponsored() != null ? m.getSponsored() : false
                    ))
                    .toList();
        }

        return new AccessRequestResponse(
                ar.getId(),
                ar.getArea() != null ? ar.getArea().getId() : null,
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
                ar.getUpdatedAt(),
                isRequester,
                ar.getCancelSource(),
                ar.getCancelReason(),
                ar.getCancelledBy() != null ? ar.getCancelledBy().getId() : null
        );
    }
}
