package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AccessControlErrorCode;
import com.fa26se040.icss.exception.AccessControlException;
import com.fa26se040.icss.exception.ConcurrentReviewException;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.MemberLookupRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * D-1: nhánh 0 dòng của reviewRequest đọc lại đơn để báo đúng nguyên nhân —
 * vẫn PENDING mà đã tới giờ bắt đầu (đơn qua bước kiểm trước rồi mới tới giờ) -> ERR_AC_012;
 * người khác đã xử lý -> câu ConcurrentReviewException cũ.
 */
@ExtendWith(MockitoExtension.class)
class AccessRequestReviewAfterStartUnitTest {

    @Mock private AccessRequestRepository accessRequestRepository;
    @Mock private AreaRepository areaRepository;
    @Mock private UserRepository userRepository;
    @Mock private InAppNotificationService inAppNotificationService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private MemberLookupRateLimiter memberLookupRateLimiter;
    @Mock private AuditService auditService;
    @Mock private jakarta.persistence.EntityManager entityManager;
    @Spy private ReasonValidator reasonValidator = ReasonValidatorTestSupport.withDefaults();
    @InjectMocks private AccessRequestService accessRequestService;

    private User requester;
    private User reviewer;
    private Area area;

    @BeforeEach
    void setUp() {
        requester = User.builder().id(UUID.randomUUID()).email("req@fpt.edu.vn").fullName("Người tạo")
                .role(Role.NORMAL_USER).isActive(true).build();
        reviewer = User.builder().id(UUID.randomUUID()).email("fm@fpt.edu.vn").fullName("Trần Văn Bình")
                .role(Role.FACILITY_MANAGER).isActive(true).build();
        area = Area.builder().id(UUID.randomUUID()).name("Phòng họp").areaLevel(AreaLevel.CONFIDENTIAL).areaAccessLevel(2).build();
    }

    private AccessRequest request(UUID id, RequestStatus status, OffsetDateTime start) {
        return AccessRequest.builder().id(id).requester(requester).area(area).requestType(RequestType.INDIVIDUAL)
                .status(status).startTime(start).endTime(start.plusHours(2)).build();
    }

    @Test
    @DisplayName("UPDATE 0 dòng + đọc lại vẫn PENDING đã qua giờ bắt đầu -> ERR_AC_012, không audit, không thông báo")
    void zeroRows_stillPendingButStarted_errAc012() {
        UUID id = UUID.randomUUID();
        // Lần đọc đầu (kiểm trước): chưa tới giờ; lần đọc lại sau UPDATE: đã tới giờ (đồng hồ vượt startTime giữa hai bước)
        AccessRequest beforeStart = request(id, RequestStatus.PENDING, OffsetDateTime.now().plusMinutes(5));
        AccessRequest afterStart = request(id, RequestStatus.PENDING, OffsetDateTime.now().minusSeconds(1));
        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(accessRequestRepository.findByIdWithDetails(id))
                .thenReturn(Optional.of(beforeStart))
                .thenReturn(Optional.of(beforeStart))
                .thenReturn(Optional.of(afterStart));
        when(accessRequestRepository.reviewIfPending(eq(id), eq(RequestStatus.REJECTED), eq(reviewer), any(), any(), eq(RequestStatus.PENDING)))
                .thenReturn(0);

        AccessControlException ex = assertThrows(AccessControlException.class, () -> accessRequestService.reviewRequest(
                id, new AccessRequestReviewRequest(RequestStatus.REJECTED, "Khu vực đang bảo trì định kỳ"), reviewer.getEmail()));

        assertEquals(AccessControlErrorCode.ERR_AC_012, ex.getErrorCode());
        verifyNoInteractions(auditService, inAppNotificationService);
    }

    @Test
    @DisplayName("Đọc trước đã thấy PENDING qua giờ bắt đầu -> ERR_AC_012 ngay, không gọi UPDATE")
    void precheck_pendingStarted_errAc012_noUpdate() {
        UUID id = UUID.randomUUID();
        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(accessRequestRepository.findByIdWithDetails(id))
                .thenReturn(Optional.of(request(id, RequestStatus.PENDING, OffsetDateTime.now().minusMinutes(1))));

        AccessControlException ex = assertThrows(AccessControlException.class, () -> accessRequestService.reviewRequest(
                id, new AccessRequestReviewRequest(RequestStatus.APPROVED, null), reviewer.getEmail()));

        assertEquals(AccessControlErrorCode.ERR_AC_012, ex.getErrorCode());
        verify(accessRequestRepository, never()).reviewIfPending(any(), any(), any(), any(), any(), any());
        verifyNoInteractions(auditService, inAppNotificationService);
    }

    @Test
    @DisplayName("UPDATE 0 dòng + đơn đã được FM khác duyệt (dù đã qua giờ) -> câu 409 cũ nêu người duyệt, không phải ERR_AC_012")
    void zeroRows_reviewedByOther_oldConcurrentMessage() {
        UUID id = UUID.randomUUID();
        AccessRequest beforeStart = request(id, RequestStatus.PENDING, OffsetDateTime.now().plusMinutes(5));
        AccessRequest approvedByOther = request(id, RequestStatus.APPROVED, OffsetDateTime.now().minusSeconds(1));
        approvedByOther.setReviewer(reviewer);
        approvedByOther.setReviewedAt(OffsetDateTime.now());
        when(userRepository.findByEmail(reviewer.getEmail())).thenReturn(Optional.of(reviewer));
        when(accessRequestRepository.findByIdWithDetails(id))
                .thenReturn(Optional.of(beforeStart))
                .thenReturn(Optional.of(beforeStart))
                .thenReturn(Optional.of(approvedByOther));
        when(accessRequestRepository.reviewIfPending(eq(id), eq(RequestStatus.REJECTED), eq(reviewer), any(), any(), eq(RequestStatus.PENDING)))
                .thenReturn(0);

        ConcurrentReviewException ex = assertThrows(ConcurrentReviewException.class, () -> accessRequestService.reviewRequest(
                id, new AccessRequestReviewRequest(RequestStatus.REJECTED, "Khu vực đang bảo trì định kỳ"), reviewer.getEmail()));

        assertEquals(true, ex.getMessage().contains("Trần Văn Bình"));
        verifyNoInteractions(auditService, inAppNotificationService);
    }
}
