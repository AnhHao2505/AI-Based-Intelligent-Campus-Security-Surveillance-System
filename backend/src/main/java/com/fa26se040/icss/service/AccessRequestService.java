package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessrequest.AccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.AccessRequestResponse;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.accessrequest.MemberInfo;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.AccessRequestMemberRepository;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccessRequestService {

    private final AccessRequestRepository accessRequestRepository;
    private final AccessRequestMemberRepository accessRequestMemberRepository;
    private final AreaRepository areaRepository;
    private final UserRepository userRepository;

    @Transactional
    public AccessRequestResponse createRequest(AccessRequestCreateRequest request, String actorEmail) {
        log.info("Creating access request by user: {}", actorEmail);

        User requester = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người dùng yêu cầu"));

        Area area = areaRepository.findByIdAndDeletedAtIsNull(request.areaId())
                .orElseThrow(() -> new ResourceNotFoundException("Khu vực không tồn tại hoặc đã bị vô hiệu hoá"));

        // Only SEMI_PRIVATE or PRIVATE areas require access requests
        if (area.getAreaLevel() != AreaLevel.SEMI_PRIVATE && area.getAreaLevel() != AreaLevel.PRIVATE) {
            throw new IllegalArgumentException("Chỉ khu vực SEMI_PRIVATE hoặc PRIVATE mới cần tạo yêu cầu truy cập");
        }

        // PRIVATE area only allows INDIVIDUAL requests
        if (area.getAreaLevel() == AreaLevel.PRIVATE && request.requestType() == RequestType.GROUP) {
            throw new IllegalArgumentException("Khu vực riêng tư (PRIVATE) chỉ cho phép đăng ký truy cập cá nhân (INDIVIDUAL)");
        }

        // Time validations
        if (!request.startTime().isBefore(request.endTime())) {
            throw new IllegalArgumentException("Thời gian bắt đầu phải trước thời gian kết thúc");
        }

        if (request.startTime().isBefore(OffsetDateTime.now().minusMinutes(5))) {
            throw new IllegalArgumentException("Thời gian bắt đầu không được ở trong quá khứ");
        }

        AccessRequest accessRequest = AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(request.requestType())
                .purpose(request.purpose().trim())
                .startTime(request.startTime())
                .endTime(request.endTime())
                .status(RequestStatus.PENDING)
                .members(new ArrayList<>())
                .build();

        // Process group members if GROUP request
        if (request.requestType() == RequestType.GROUP) {
            if (request.memberUserCodes() == null || request.memberUserCodes().isEmpty()) {
                throw new IllegalArgumentException("Yêu cầu nhóm (GROUP) bắt buộc phải có ít nhất một mã số thành viên");
            }

            Set<String> processedCodes = new HashSet<>();
            for (String code : request.memberUserCodes()) {
                if (code == null || code.trim().isEmpty()) {
                    continue;
                }
                String cleanCode = code.trim();
                if (processedCodes.contains(cleanCode)) {
                    continue;
                }
                processedCodes.add(cleanCode);

                User memberUser = userRepository.findByUserCode(cleanCode)
                        .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng với mã số: " + cleanCode));

                AccessRequestMember member = AccessRequestMember.builder()
                        .accessRequest(accessRequest)
                        .user(memberUser)
                        .build();

                accessRequest.getMembers().add(member);
            }

            if (accessRequest.getMembers().isEmpty()) {
                throw new IllegalArgumentException("Vui lòng cung cấp ít nhất một mã số thành viên hợp lệ");
            }
        }

        AccessRequest saved = accessRequestRepository.save(accessRequest);
        log.info("Access request created with id: {}", saved.getId());
        return mapToResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<AccessRequestResponse> getMyRequests(String actorEmail, RequestStatus status, Pageable pageable) {
        User requester = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người dùng"));

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

        User reviewer = userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người duyệt"));

        AccessRequest accessRequest = accessRequestRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy yêu cầu truy cập với mã: " + id));

        if (accessRequest.getStatus() != RequestStatus.PENDING) {
            throw new IllegalStateException("Yêu cầu này đã được xử lý trước đó với trạng thái: " + accessRequest.getStatus());
        }

        if (reviewRequest.status() != RequestStatus.APPROVED && reviewRequest.status() != RequestStatus.REJECTED) {
            throw new IllegalArgumentException("Trạng thái phê duyệt phải là APPROVED hoặc REJECTED");
        }

        if (reviewRequest.status() == RequestStatus.REJECTED) {
            if (reviewRequest.rejectionReason() == null || reviewRequest.rejectionReason().trim().isEmpty()) {
                throw new IllegalArgumentException("Vui lòng cung cấp lý do từ chối yêu cầu");
            }
            accessRequest.setRejectionReason(reviewRequest.rejectionReason().trim());
        }

        accessRequest.setStatus(reviewRequest.status());
        accessRequest.setReviewer(reviewer);
        accessRequest.setReviewedAt(OffsetDateTime.now());

        AccessRequest updated = accessRequestRepository.save(accessRequest);
        log.info("Access request {} reviewed: {}", updated.getId(), updated.getStatus());
        return mapToResponse(updated);
    }

    private AccessRequestResponse mapToResponse(AccessRequest ar) {
        List<MemberInfo> memberInfos = List.of();
        if (ar.getMembers() != null && !ar.getMembers().isEmpty()) {
            memberInfos = ar.getMembers().stream()
                    .map(m -> new MemberInfo(
                            m.getUser() != null ? m.getUser().getId() : null,
                            m.getUser() != null ? m.getUser().getUserCode() : null,
                            m.getUser() != null ? m.getUser().getFullName() : null,
                            m.getUser() != null ? m.getUser().getEmail() : null
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
