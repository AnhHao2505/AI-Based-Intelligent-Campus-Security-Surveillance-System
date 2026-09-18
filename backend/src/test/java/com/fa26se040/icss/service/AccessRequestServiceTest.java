package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessrequest.AccessRequestResponse;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.accessrequest.GroupAccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.IndividualAccessRequestCreateRequest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.ConcurrentReviewException;
import com.fa26se040.icss.exception.DuplicateResourceException;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccessRequestServiceTest {

    @Mock
    private AccessRequestRepository accessRequestRepository;

    @Mock
    private AreaRepository areaRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InAppNotificationService inAppNotificationService;

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private AccessRequestService accessRequestService;

    private User requester;
    private User reviewer;
    private User member1;
    private User member2;
    private Area semiPrivateArea;
    private Area privateArea;
    private Area publicArea;

    @BeforeEach
    void setUp() {
        requester = User.builder()
                .id(UUID.randomUUID())
                .email("student.tuan@fpt.edu.vn")
                .userCode("SV-001")
                .fullName("Nguyễn Văn Tuấn")
                .role(Role.NORMAL_USER)
                .isActive(true)
                .build();

        reviewer = User.builder()
                .id(UUID.randomUUID())
                .email("manager.binh@fpt.edu.vn")
                .userCode("FM-001")
                .fullName("Trần Văn Bình")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();

        member1 = User.builder()
                .id(UUID.randomUUID())
                .email("student.an@fpt.edu.vn")
                .userCode("SV-002")
                .fullName("Lê Văn An")
                .role(Role.NORMAL_USER)
                .isActive(true)
                .build();

        member2 = User.builder()
                .id(UUID.randomUUID())
                .email("student.hoa@fpt.edu.vn")
                .userCode("SV-003")
                .fullName("Phạm Thị Hoa")
                .role(Role.NORMAL_USER)
                .isActive(true)
                .build();

        semiPrivateArea = Area.builder()
                .id(UUID.randomUUID())
                .code("LAB-01")
                .name("Phòng Thí Nghiệm AI")
                .areaLevel(AreaLevel.SEMI_PRIVATE)
                .building("Tòa Alpha")
                .floor("Tầng 2")
                .isActive(true)
                .build();

        privateArea = Area.builder()
                .id(UUID.randomUUID())
                .code("SERVER-01")
                .name("Phòng Server Trung Tâm")
                .areaLevel(AreaLevel.PRIVATE)
                .building("Tòa Beta")
                .floor("Tầng 1")
                .isActive(true)
                .build();

        publicArea = Area.builder()
                .id(UUID.randomUUID())
                .code("HALL-A")
                .name("Sảnh Chính A")
                .areaLevel(AreaLevel.PUBLIC)
                .isActive(true)
                .build();

        org.mockito.Mockito.lenient().when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS)).thenReturn(30);
        org.mockito.Mockito.lenient().when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_DURATION_HOURS)).thenReturn(12);
        org.mockito.Mockito.lenient().when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_ADVANCE_DAYS)).thenReturn(30);
        org.mockito.Mockito.lenient().when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_PAST_START_BUFFER_MINUTES)).thenReturn(5);
        org.mockito.Mockito.lenient().when(systemConfigService.getBoolean(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE)).thenReturn(false);
    }

    @Test
    @DisplayName("Tạo yêu cầu cá nhân thành công với dữ liệu hợp lệ")
    void createIndividualRequest_Success() {
        OffsetDateTime startTime = OffsetDateTime.now().plusHours(1);
        OffsetDateTime endTime = startTime.plusHours(2);
        IndividualAccessRequestCreateRequest request = new IndividualAccessRequestCreateRequest(
                semiPrivateArea.getId(),
                startTime,
                endTime,
                "Học nhóm và nghiên cứu"
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(semiPrivateArea.getId())).thenReturn(Optional.of(semiPrivateArea));
        when(accessRequestRepository.findOverlappingRequests(anyList(), eq(semiPrivateArea.getId()), eq(startTime), eq(endTime), anyList()))
                .thenReturn(Collections.emptyList());

        when(accessRequestRepository.save(any(AccessRequest.class))).thenAnswer(invocation -> {
            AccessRequest ar = invocation.getArgument(0);
            ar.setId(UUID.randomUUID());
            ar.setCreatedAt(OffsetDateTime.now());
            ar.setUpdatedAt(OffsetDateTime.now());
            return ar;
        });

        AccessRequestResponse response = accessRequestService.createIndividualRequest(request, requester.getEmail());

        assertNotNull(response);
        assertEquals(RequestType.INDIVIDUAL, response.requestType());
        assertEquals(RequestStatus.PENDING, response.status());
        assertEquals("Học nhóm và nghiên cứu", response.purpose());
        assertEquals(semiPrivateArea.getCode(), response.areaCode());
        assertEquals(requester.getUserCode(), response.requesterCode());
    }

    @Test
    @DisplayName("Tạo yêu cầu cá nhân thất bại khi thời gian bắt đầu ở quá khứ")
    void createIndividualRequest_StartTimeInPast_ThrowsException() {
        OffsetDateTime startTime = OffsetDateTime.now().minusHours(1);
        OffsetDateTime endTime = startTime.plusHours(2);
        IndividualAccessRequestCreateRequest request = new IndividualAccessRequestCreateRequest(
                semiPrivateArea.getId(),
                startTime,
                endTime,
                "Lý do"
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(semiPrivateArea.getId())).thenReturn(Optional.of(semiPrivateArea));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> accessRequestService.createIndividualRequest(request, requester.getEmail()));
        assertTrue(ex.getMessage().contains("quá khứ"));
    }

    @Test
    @DisplayName("Tạo yêu cầu cá nhân thất bại khi startTime >= endTime")
    void createIndividualRequest_StartTimeAfterEndTime_ThrowsException() {
        OffsetDateTime startTime = OffsetDateTime.now().plusHours(2);
        OffsetDateTime endTime = startTime.minusHours(1);
        IndividualAccessRequestCreateRequest request = new IndividualAccessRequestCreateRequest(
                semiPrivateArea.getId(),
                startTime,
                endTime,
                "Lý do"
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(semiPrivateArea.getId())).thenReturn(Optional.of(semiPrivateArea));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> accessRequestService.createIndividualRequest(request, requester.getEmail()));
        assertTrue(ex.getMessage().contains("trước thời gian kết thúc"));
    }

    @Test
    @DisplayName("Tạo yêu cầu cá nhân thất bại khi thời lượng vượt quá 12 giờ")
    void createIndividualRequest_DurationOver12Hours_ThrowsException() {
        OffsetDateTime startTime = OffsetDateTime.now().plusHours(1);
        OffsetDateTime endTime = startTime.plusHours(13);
        IndividualAccessRequestCreateRequest request = new IndividualAccessRequestCreateRequest(
                semiPrivateArea.getId(),
                startTime,
                endTime,
                "Lý do"
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(semiPrivateArea.getId())).thenReturn(Optional.of(semiPrivateArea));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> accessRequestService.createIndividualRequest(request, requester.getEmail()));
        assertTrue(ex.getMessage().contains("12 giờ"));
    }

    @Test
    @DisplayName("Tạo yêu cầu thất bại khi đặt trước vượt quá 30 ngày")
    void createIndividualRequest_AdvanceBookingOver30Days_ThrowsException() {
        OffsetDateTime startTime = OffsetDateTime.now().plusDays(31);
        OffsetDateTime endTime = startTime.plusHours(2);
        IndividualAccessRequestCreateRequest request = new IndividualAccessRequestCreateRequest(
                semiPrivateArea.getId(),
                startTime,
                endTime,
                "Lý do"
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(semiPrivateArea.getId())).thenReturn(Optional.of(semiPrivateArea));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> accessRequestService.createIndividualRequest(request, requester.getEmail()));
        assertTrue(ex.getMessage().contains("30 ngày tới"));
    }

    @Test
    @DisplayName("Tạo yêu cầu thất bại khi khu vực là PUBLIC")
    void createIndividualRequest_PublicArea_ThrowsException() {
        OffsetDateTime startTime = OffsetDateTime.now().plusHours(1);
        OffsetDateTime endTime = startTime.plusHours(2);
        IndividualAccessRequestCreateRequest request = new IndividualAccessRequestCreateRequest(
                publicArea.getId(),
                startTime,
                endTime,
                "Lý do"
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(publicArea.getId())).thenReturn(Optional.of(publicArea));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> accessRequestService.createIndividualRequest(request, requester.getEmail()));
        assertTrue(ex.getMessage().contains("PUBLIC"));
    }

    @Test
    @DisplayName("Chặn trùng lịch yêu cầu cá nhân trả về DuplicateResourceException (409 Conflict)")
    void createIndividualRequest_OverlapDetected_ThrowsDuplicateResourceException() {
        OffsetDateTime startTime = OffsetDateTime.now().plusHours(1);
        OffsetDateTime endTime = startTime.plusHours(2);
        IndividualAccessRequestCreateRequest request = new IndividualAccessRequestCreateRequest(
                semiPrivateArea.getId(),
                startTime,
                endTime,
                "Lý do"
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(semiPrivateArea.getId())).thenReturn(Optional.of(semiPrivateArea));

        AccessRequest existingRequest = AccessRequest.builder()
                .id(UUID.randomUUID())
                .requester(requester)
                .area(semiPrivateArea)
                .status(RequestStatus.APPROVED)
                .startTime(startTime.minusMinutes(30))
                .endTime(startTime.plusMinutes(30))
                .build();

        when(accessRequestRepository.findOverlappingRequests(anyList(), eq(semiPrivateArea.getId()), eq(startTime), eq(endTime), anyList()))
                .thenReturn(List.of(existingRequest));

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class,
                () -> accessRequestService.createIndividualRequest(request, requester.getEmail()));
        assertTrue(ex.getMessage().contains("SV-001"));
        assertTrue(ex.getMessage().contains("Nguyễn Văn Tuấn"));
    }

    @Test
    @DisplayName("Tạo yêu cầu nhóm thành công và tự động loại bỏ requester nếu vô tình nhập vào memberUserCodes")
    void createGroupRequest_Success_WithDeduplication() {
        OffsetDateTime startTime = OffsetDateTime.now().plusHours(1);
        OffsetDateTime endTime = startTime.plusHours(3);
        // Requester code SV-001 is included along with SV-002, SV-003
        GroupAccessRequestCreateRequest request = new GroupAccessRequestCreateRequest(
                semiPrivateArea.getId(),
                startTime,
                endTime,
                "Nghiên cứu dự án nhóm",
                List.of("SV-001", "SV-002", "SV-003")
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(semiPrivateArea.getId())).thenReturn(Optional.of(semiPrivateArea));
        when(userRepository.findByUserCode("SV-002")).thenReturn(Optional.of(member1));
        when(userRepository.findByUserCode("SV-003")).thenReturn(Optional.of(member2));
        when(accessRequestRepository.findOverlappingRequests(anyList(), eq(semiPrivateArea.getId()), eq(startTime), eq(endTime), anyList()))
                .thenReturn(Collections.emptyList());

        when(accessRequestRepository.save(any(AccessRequest.class))).thenAnswer(invocation -> {
            AccessRequest ar = invocation.getArgument(0);
            ar.setId(UUID.randomUUID());
            ar.setCreatedAt(OffsetDateTime.now());
            ar.setUpdatedAt(OffsetDateTime.now());
            return ar;
        });

        AccessRequestResponse response = accessRequestService.createGroupRequest(request, requester.getEmail());

        assertNotNull(response);
        assertEquals(RequestType.GROUP, response.requestType());
        assertEquals(2, response.members().size()); // Only SV-002 and SV-003
    }

    @Test
    @DisplayName("Tạo yêu cầu nhóm thất bại vào khu vực PRIVATE")
    void createGroupRequest_PrivateArea_ThrowsException() {
        OffsetDateTime startTime = OffsetDateTime.now().plusHours(1);
        OffsetDateTime endTime = startTime.plusHours(3);
        GroupAccessRequestCreateRequest request = new GroupAccessRequestCreateRequest(
                privateArea.getId(),
                startTime,
                endTime,
                "Lý do",
                List.of("SV-002")
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(privateArea.getId())).thenReturn(Optional.of(privateArea));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> accessRequestService.createGroupRequest(request, requester.getEmail()));
        assertTrue(ex.getMessage().contains("PRIVATE"));
    }

    @Test
    @DisplayName("Tạo yêu cầu nhóm thất bại khi chỉ có duy nhất mã của requester trong danh sách")
    void createGroupRequest_OnlyRequesterInMembers_ThrowsException() {
        OffsetDateTime startTime = OffsetDateTime.now().plusHours(1);
        OffsetDateTime endTime = startTime.plusHours(3);
        GroupAccessRequestCreateRequest request = new GroupAccessRequestCreateRequest(
                semiPrivateArea.getId(),
                startTime,
                endTime,
                "Lý do",
                List.of("SV-001") // Only requester
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(semiPrivateArea.getId())).thenReturn(Optional.of(semiPrivateArea));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> accessRequestService.createGroupRequest(request, requester.getEmail()));
        assertTrue(ex.getMessage().contains("ít nhất một thành viên khác"));
    }

    @Test
    @DisplayName("Tạo yêu cầu nhóm thất bại khi một thành viên bị trùng lịch, thông báo lỗi chỉ rõ người bị trùng")
    void createGroupRequest_MemberOverlap_ThrowsExceptionWithUserDetail() {
        OffsetDateTime startTime = OffsetDateTime.now().plusHours(1);
        OffsetDateTime endTime = startTime.plusHours(3);
        GroupAccessRequestCreateRequest request = new GroupAccessRequestCreateRequest(
                semiPrivateArea.getId(),
                startTime,
                endTime,
                "Lý do",
                List.of("SV-002")
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(semiPrivateArea.getId())).thenReturn(Optional.of(semiPrivateArea));
        when(userRepository.findByUserCode("SV-002")).thenReturn(Optional.of(member1));

        // Member1 has conflicting request
        AccessRequest conflictingRequest = AccessRequest.builder()
                .id(UUID.randomUUID())
                .requester(member1)
                .area(semiPrivateArea)
                .status(RequestStatus.PENDING)
                .startTime(startTime)
                .endTime(endTime)
                .build();

        when(accessRequestRepository.findOverlappingRequests(anyList(), eq(semiPrivateArea.getId()), eq(startTime), eq(endTime), anyList()))
                .thenReturn(List.of(conflictingRequest));

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class,
                () -> accessRequestService.createGroupRequest(request, requester.getEmail()));
        assertTrue(ex.getMessage().contains("SV-002"));
        assertTrue(ex.getMessage().contains("Lê Văn An"));
    }

    @Test
    @DisplayName("FM phê duyệt yêu cầu thành công khi reviewIfPending trả về 1")
    void reviewRequest_Approve_Success() {
        UUID requestId = UUID.randomUUID();
        AccessRequest approvedRequest = AccessRequest.builder()
                .id(requestId)
                .requester(requester)
                .reviewer(reviewer)
                .area(semiPrivateArea)
                .status(RequestStatus.APPROVED)
                .startTime(OffsetDateTime.now().plusHours(1))
                .endTime(OffsetDateTime.now().plusHours(2))
                .purpose("Lý do")
                .reviewedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(accessRequestRepository.reviewIfPending(
                eq(requestId),
                eq(RequestStatus.APPROVED),
                eq(reviewer),
                any(OffsetDateTime.class),
                isNull(),
                eq(RequestStatus.PENDING)
        )).thenReturn(1);
        when(accessRequestRepository.findByIdWithDetails(requestId)).thenReturn(Optional.of(approvedRequest));

        AccessRequestReviewRequest reviewRequest = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);
        AccessRequestResponse response = accessRequestService.reviewRequest(requestId, reviewRequest, reviewer.getEmail());

        assertNotNull(response);
        assertEquals(RequestStatus.APPROVED, response.status());
        assertEquals(reviewer.getFullName(), response.reviewerName());
    }

    @Test
    @DisplayName("FM từ chối yêu cầu thành công kèm lý do khi reviewIfPending trả về 1")
    void reviewRequest_Reject_Success() {
        UUID requestId = UUID.randomUUID();
        AccessRequest rejectedRequest = AccessRequest.builder()
                .id(requestId)
                .requester(requester)
                .reviewer(reviewer)
                .area(semiPrivateArea)
                .status(RequestStatus.REJECTED)
                .rejectionReason("Khu vực đang bảo trì")
                .startTime(OffsetDateTime.now().plusHours(1))
                .endTime(OffsetDateTime.now().plusHours(2))
                .purpose("Lý do")
                .reviewedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(accessRequestRepository.reviewIfPending(
                eq(requestId),
                eq(RequestStatus.REJECTED),
                eq(reviewer),
                any(OffsetDateTime.class),
                eq("Khu vực đang bảo trì"),
                eq(RequestStatus.PENDING)
        )).thenReturn(1);
        when(accessRequestRepository.findByIdWithDetails(requestId)).thenReturn(Optional.of(rejectedRequest));

        AccessRequestReviewRequest reviewRequest = new AccessRequestReviewRequest(RequestStatus.REJECTED, "Khu vực đang bảo trì");
        AccessRequestResponse response = accessRequestService.reviewRequest(requestId, reviewRequest, reviewer.getEmail());

        assertNotNull(response);
        assertEquals(RequestStatus.REJECTED, response.status());
        assertEquals("Khu vực đang bảo trì", response.rejectionReason());
        assertEquals(reviewer.getFullName(), response.reviewerName());
    }

    @Test
    @DisplayName("FM từ chối thất bại nếu lý do từ chối rỗng - ném lỗi TRƯỚC khi gọi repository")
    void reviewRequest_RejectWithoutReason_ThrowsExceptionBeforeRepo() {
        UUID requestId = UUID.randomUUID();
        AccessRequestReviewRequest reviewRequest = new AccessRequestReviewRequest(RequestStatus.REJECTED, "   ");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> accessRequestService.reviewRequest(requestId, reviewRequest, reviewer.getEmail()));
        assertTrue(ex.getMessage().contains("lý do từ chối"));

        verify(accessRequestRepository, never()).reviewIfPending(any(), any(), any(), any(), any(), any());
        verify(userRepository, never()).findByEmail(any());
    }

    @Test
    @DisplayName("reviewIfPending trả 0 + entity đã APPROVED -> ConcurrentReviewException, message chứa tên người duyệt")
    void reviewRequest_ReviewIfPendingZero_EntityApproved_ThrowsConcurrentReviewException() {
        UUID requestId = UUID.randomUUID();
        AccessRequest processedRequest = AccessRequest.builder()
                .id(requestId)
                .requester(requester)
                .reviewer(reviewer)
                .area(semiPrivateArea)
                .status(RequestStatus.APPROVED)
                .reviewedAt(OffsetDateTime.now())
                .build();

        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(accessRequestRepository.reviewIfPending(
                eq(requestId),
                eq(RequestStatus.APPROVED),
                eq(reviewer),
                any(OffsetDateTime.class),
                isNull(),
                eq(RequestStatus.PENDING)
        )).thenReturn(0);
        when(accessRequestRepository.findByIdWithDetails(requestId)).thenReturn(Optional.of(processedRequest));

        AccessRequestReviewRequest reviewRequest = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);

        ConcurrentReviewException ex = assertThrows(ConcurrentReviewException.class,
                () -> accessRequestService.reviewRequest(requestId, reviewRequest, reviewer.getEmail()));
        assertTrue(ex.getMessage().contains("Trần Văn Bình"));
        assertTrue(ex.getMessage().contains("phê duyệt"));
    }

    @Test
    @DisplayName("reviewIfPending trả 0 + entity không tồn tại -> ResourceNotFoundException")
    void reviewRequest_ReviewIfPendingZero_EntityNotFound_ThrowsResourceNotFoundException() {
        UUID requestId = UUID.randomUUID();

        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(accessRequestRepository.reviewIfPending(
                eq(requestId),
                eq(RequestStatus.APPROVED),
                eq(reviewer),
                any(OffsetDateTime.class),
                isNull(),
                eq(RequestStatus.PENDING)
        )).thenReturn(0);
        when(accessRequestRepository.findByIdWithDetails(requestId)).thenReturn(Optional.empty());

        AccessRequestReviewRequest reviewRequest = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);

        assertThrows(ResourceNotFoundException.class,
                () -> accessRequestService.reviewRequest(requestId, reviewRequest, reviewer.getEmail()));
    }

    @Test
    @DisplayName("reviewIfPending trả 0 + entity đã CANCELLED (reviewer null) -> ConcurrentReviewException không chứa chữ null")
    void reviewRequest_ReviewIfPendingZero_EntityCancelled_ReviewerNull_ThrowsConcurrentReviewExceptionWithoutNull() {
        UUID requestId = UUID.randomUUID();
        AccessRequest cancelledRequest = AccessRequest.builder()
                .id(requestId)
                .requester(requester)
                .reviewer(null)
                .area(semiPrivateArea)
                .status(RequestStatus.CANCELLED)
                .build();

        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(accessRequestRepository.reviewIfPending(
                eq(requestId),
                eq(RequestStatus.APPROVED),
                eq(reviewer),
                any(OffsetDateTime.class),
                isNull(),
                eq(RequestStatus.PENDING)
        )).thenReturn(0);
        when(accessRequestRepository.findByIdWithDetails(requestId)).thenReturn(Optional.of(cancelledRequest));

        AccessRequestReviewRequest reviewRequest = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);

        ConcurrentReviewException ex = assertThrows(ConcurrentReviewException.class,
                () -> accessRequestService.reviewRequest(requestId, reviewRequest, reviewer.getEmail()));
        assertFalse(ex.getMessage().toLowerCase().contains("null"));
        assertTrue(ex.getMessage().contains("huỷ"));
    }

    @Test
    @DisplayName("Requester huỷ yêu cầu thành công khi cancelIfPending trả về 1")
    void cancelRequest_Success() {
        UUID requestId = UUID.randomUUID();
        AccessRequest pendingRequest = AccessRequest.builder()
                .id(requestId)
                .requester(requester)
                .area(semiPrivateArea)
                .status(RequestStatus.PENDING)
                .build();

        AccessRequest cancelledRequest = AccessRequest.builder()
                .id(requestId)
                .requester(requester)
                .area(semiPrivateArea)
                .status(RequestStatus.CANCELLED)
                .startTime(OffsetDateTime.now().plusHours(1))
                .endTime(OffsetDateTime.now().plusHours(2))
                .purpose("Lý do")
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(accessRequestRepository.findByIdWithDetails(requestId))
                .thenReturn(Optional.of(pendingRequest))
                .thenReturn(Optional.of(cancelledRequest));
        when(accessRequestRepository.cancelIfPending(
                eq(requestId),
                eq(RequestStatus.CANCELLED),
                any(OffsetDateTime.class),
                eq(RequestStatus.PENDING)
        )).thenReturn(1);

        AccessRequestResponse response = accessRequestService.cancelRequest(requestId, requester.getEmail());

        assertNotNull(response);
        assertEquals(RequestStatus.CANCELLED, response.status());
    }

    @Test
    @DisplayName("Huỷ yêu cầu thất bại khi người thực hiện không phải requester")
    void cancelRequest_NotRequester_ThrowsAccessDeniedException() {
        UUID requestId = UUID.randomUUID();
        AccessRequest accessRequest = AccessRequest.builder()
                .id(requestId)
                .requester(requester)
                .status(RequestStatus.PENDING)
                .build();

        when(accessRequestRepository.findByIdWithDetails(requestId)).thenReturn(Optional.of(accessRequest));

        assertThrows(AccessDeniedException.class,
                () -> accessRequestService.cancelRequest(requestId, "other.user@fpt.edu.vn"));
        verify(accessRequestRepository, never()).cancelIfPending(any(), any(), any(), any());
    }

    @Test
    @DisplayName("cancelIfPending trả 0 + entity đã APPROVED -> ConcurrentReviewException")
    void cancelRequest_CancelIfPendingZero_EntityApproved_ThrowsConcurrentReviewException() {
        UUID requestId = UUID.randomUUID();
        AccessRequest pendingRequest = AccessRequest.builder()
                .id(requestId)
                .requester(requester)
                .status(RequestStatus.PENDING)
                .build();

        AccessRequest approvedRequest = AccessRequest.builder()
                .id(requestId)
                .requester(requester)
                .reviewer(reviewer)
                .status(RequestStatus.APPROVED)
                .build();

        when(accessRequestRepository.findByIdWithDetails(requestId))
                .thenReturn(Optional.of(pendingRequest))
                .thenReturn(Optional.of(approvedRequest));
        when(accessRequestRepository.cancelIfPending(
                eq(requestId),
                eq(RequestStatus.CANCELLED),
                any(OffsetDateTime.class),
                eq(RequestStatus.PENDING)
        )).thenReturn(0);

        ConcurrentReviewException ex = assertThrows(ConcurrentReviewException.class,
                () -> accessRequestService.cancelRequest(requestId, requester.getEmail()));
        assertTrue(ex.getMessage().contains("phê duyệt"));
    }

    @Test
    @DisplayName("Lỗi khi ghi notification KHÔNG làm fail reviewRequest")
    void reviewRequest_NotificationException_DoesNotFailReview() {
        UUID requestId = UUID.randomUUID();
        AccessRequest approvedRequest = AccessRequest.builder()
                .id(requestId)
                .requester(requester)
                .reviewer(reviewer)
                .area(semiPrivateArea)
                .status(RequestStatus.APPROVED)
                .startTime(OffsetDateTime.now().plusHours(1))
                .endTime(OffsetDateTime.now().plusHours(2))
                .purpose("Lý do")
                .reviewedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build();

        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(accessRequestRepository.reviewIfPending(
                eq(requestId),
                eq(RequestStatus.APPROVED),
                eq(reviewer),
                any(OffsetDateTime.class),
                isNull(),
                eq(RequestStatus.PENDING)
        )).thenReturn(1);
        when(accessRequestRepository.findByIdWithDetails(requestId)).thenReturn(Optional.of(approvedRequest));

        // Mock notification service throwing exception
        when(inAppNotificationService.createForUsers(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Lỗi database notification"));

        AccessRequestReviewRequest reviewRequest = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);
        AccessRequestResponse response = accessRequestService.reviewRequest(requestId, reviewRequest, reviewer.getEmail());

        assertNotNull(response);
        assertEquals(RequestStatus.APPROVED, response.status());
        assertEquals(reviewer.getFullName(), response.reviewerName());
    }

    @Test
    @DisplayName("reviewRequest ở nhánh 409 (ConcurrentReviewException) -> verify KHÔNG gọi tạo notification")
    void reviewRequest_Branch409_NeverCallsNotification() {
        UUID requestId = UUID.randomUUID();
        AccessRequest processedRequest = AccessRequest.builder()
                .id(requestId)
                .requester(requester)
                .reviewer(reviewer)
                .area(semiPrivateArea)
                .status(RequestStatus.APPROVED)
                .reviewedAt(OffsetDateTime.now())
                .build();

        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(accessRequestRepository.reviewIfPending(
                eq(requestId),
                eq(RequestStatus.APPROVED),
                eq(reviewer),
                any(OffsetDateTime.class),
                isNull(),
                eq(RequestStatus.PENDING)
        )).thenReturn(0);
        when(accessRequestRepository.findByIdWithDetails(requestId)).thenReturn(Optional.of(processedRequest));

        AccessRequestReviewRequest reviewRequest = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);

        assertThrows(ConcurrentReviewException.class,
                () -> accessRequestService.reviewRequest(requestId, reviewRequest, reviewer.getEmail()));

        verify(inAppNotificationService, never()).createForUsers(any(), any(), any(), any(), any());
        verify(inAppNotificationService, never()).createForUser(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Tạo yêu cầu nhóm vượt quá số thành viên cấu hình động thì ném ngoại lệ chứa số cấu hình")
    void createGroupRequest_ExceedsDynamicMaxMembers_ThrowsException() {
        OffsetDateTime startTime = OffsetDateTime.now().plusHours(1);
        OffsetDateTime endTime = OffsetDateTime.now().plusHours(3);

        List<String> memberCodes = List.of("SV-002", "SV-003", "SV-004", "SV-005", "SV-006", "SV-007");
        GroupAccessRequestCreateRequest request = new GroupAccessRequestCreateRequest(
                semiPrivateArea.getId(),
                startTime,
                endTime,
                "Mục đích học nhóm",
                memberCodes
        );

        when(userRepository.findByEmail(requester.getEmail())).thenReturn(Optional.of(requester));
        when(areaRepository.findByIdAndDeletedAtIsNull(semiPrivateArea.getId())).thenReturn(Optional.of(semiPrivateArea));
        when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS)).thenReturn(5);

        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> accessRequestService.createGroupRequest(request, requester.getEmail())
        );

        assertTrue(ex.getMessage().contains("5"));
    }

    @Test
    @DisplayName("Quét các yêu cầu quá hạn chuyển sang EXPIRED thành công")
    void expireOverdueRequests_Success() {
        when(accessRequestRepository.expireOverdueRequests(eq(RequestStatus.PENDING), eq(RequestStatus.EXPIRED), any(OffsetDateTime.class)))
                .thenReturn(3);

        int expiredCount = accessRequestService.expireOverdueRequests();

        assertEquals(3, expiredCount);
        verify(accessRequestRepository).expireOverdueRequests(eq(RequestStatus.PENDING), eq(RequestStatus.EXPIRED), any(OffsetDateTime.class));
    }
}
