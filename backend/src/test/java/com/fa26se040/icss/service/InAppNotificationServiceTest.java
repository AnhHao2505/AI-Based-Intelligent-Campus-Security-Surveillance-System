package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.notification.NotificationResponse;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.NotificationRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.time.OffsetDateTime;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InAppNotificationServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AccessRequestRepository accessRequestRepository;

    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private InAppNotificationService inAppNotificationService;

    private User user1;
    private User user2;
    private User fmUser;

    @BeforeEach
    void setUp() {
        user1 = User.builder()
                .id(UUID.randomUUID())
                .email("student.an@fpt.edu.vn")
                .fullName("Lê Văn An")
                .userCode("SV-001")
                .role(Role.NORMAL_USER)
                .isActive(true)
                .build();

        user2 = User.builder()
                .id(UUID.randomUUID())
                .email("student.binh@fpt.edu.vn")
                .fullName("Trần Văn Bình")
                .userCode("SV-002")
                .role(Role.NORMAL_USER)
                .isActive(true)
                .build();

        fmUser = User.builder()
                .id(UUID.randomUUID())
                .email("manager.fm@fpt.edu.vn")
                .fullName("Facility Manager")
                .userCode("FM-001")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("createForUsers tạo đúng số bản ghi và chỉ gọi saveAll 1 lần")
    void createForUsers_Success_UsesSaveAllOnce() {
        List<User> recipients = List.of(user1, user2, user1); // user1 duplicated
        UUID refId = UUID.randomUUID();

        when(notificationRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<Notification> result = inAppNotificationService.createForUsers(
                recipients,
                NotificationType.ADDED_TO_GROUP,
                "Tiêu đề",
                "Nội dung",
                refId
        );

        assertNotNull(result);
        assertEquals(2, result.size()); // Deduplicated to 2 unique recipients
        verify(notificationRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("markAsRead thành công khi đúng chủ sở hữu")
    void markAsRead_Success() {
        UUID notifId = UUID.randomUUID();
        Notification notif = Notification.builder()
                .id(notifId)
                .recipient(user1)
                .title("Thông báo")
                .message("Nội dung")
                .type(NotificationType.REQUEST_APPROVED)
                .isRead(false)
                .createdAt(OffsetDateTime.now())
                .build();

        when(userRepository.findByEmail(user1.getEmail())).thenReturn(Optional.of(user1));
        when(notificationRepository.findById(notifId)).thenReturn(Optional.of(notif));
        when(notificationRepository.save(any(Notification.class))).thenAnswer(i -> i.getArgument(0));

        NotificationResponse response = inAppNotificationService.markAsRead(notifId, user1.getEmail());

        assertNotNull(response);
        assertTrue(response.isRead());
        verify(notificationRepository).save(notif);
    }

    @Test
    @DisplayName("markAsRead thất bại khi người thực hiện không phải người nhận -> AccessDeniedException")
    void markAsRead_NotOwner_ThrowsAccessDeniedException() {
        UUID notifId = UUID.randomUUID();
        Notification notif = Notification.builder()
                .id(notifId)
                .recipient(user1)
                .title("Thông báo")
                .message("Nội dung")
                .type(NotificationType.REQUEST_APPROVED)
                .isRead(false)
                .build();

        when(userRepository.findByEmail(user2.getEmail())).thenReturn(Optional.of(user2));
        when(notificationRepository.findById(notifId)).thenReturn(Optional.of(notif));

        assertThrows(AccessDeniedException.class,
                () -> inAppNotificationService.markAsRead(notifId, user2.getEmail()));
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("existsByReferenceIdAndType kiểm tra chống gửi trùng chính xác")
    void existsByReferenceIdAndType_ReturnsRepositoryResult() {
        UUID refId = UUID.randomUUID();
        when(notificationRepository.existsByReferenceIdAndType(refId, NotificationType.EXPIRING_SOON)).thenReturn(true);

        boolean exists = inAppNotificationService.existsByReferenceIdAndType(refId, NotificationType.EXPIRING_SOON);

        assertTrue(exists);
        verify(notificationRepository).existsByReferenceIdAndType(refId, NotificationType.EXPIRING_SOON);
    }

    @Test
    @DisplayName("scanAndNotifyExpiringSoonRequests bỏ qua nếu đã gửi thông báo trước đó (chống gửi trùng)")
    void scanAndNotifyExpiringSoonRequests_AlreadyExists_SkipsCreation() {
        UUID refId = UUID.randomUUID();
        AccessRequest req = AccessRequest.builder()
                .id(refId)
                .requester(user1)
                .area(Area.builder().id(UUID.randomUUID()).name("Phòng Lab AI").build())
                .startTime(OffsetDateTime.now().plusMinutes(30))
                .endTime(OffsetDateTime.now().plusHours(2))
                .status(RequestStatus.APPROVED)
                .build();

        when(systemConfigService.getInt(ConfigKey.NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES)).thenReturn(30);
        when(accessRequestRepository.findApprovedRequestsStartingBetween(eq(RequestStatus.APPROVED), any(), any()))
                .thenReturn(List.of(req));
        when(notificationRepository.existsByReferenceIdAndType(refId, NotificationType.EXPIRING_SOON))
                .thenReturn(true);

        inAppNotificationService.scanAndNotifyExpiringSoonRequests();

        verify(notificationRepository, never()).saveAll(anyList());
    }

    @Test
    @DisplayName("scanAndNotifyPendingOverdueRequests gửi PENDING_OVERDUE cho tất cả FM")
    void scanAndNotifyPendingOverdueRequests_SendsToAllFMs() {
        UUID refId = UUID.randomUUID();
        AccessRequest req = AccessRequest.builder()
                .id(refId)
                .requester(user1)
                .area(Area.builder().id(UUID.randomUUID()).name("Phòng Server").build())
                .startTime(OffsetDateTime.now().plusHours(5))
                .endTime(OffsetDateTime.now().plusHours(7))
                .status(RequestStatus.PENDING)
                .createdAt(OffsetDateTime.now().minusHours(25))
                .build();

        when(systemConfigService.getInt(ConfigKey.NOTIFICATION_PENDING_OVERDUE_HOURS)).thenReturn(24);
        when(accessRequestRepository.findPendingRequestsCreatedBefore(eq(RequestStatus.PENDING), any()))
                .thenReturn(List.of(req));
        when(userRepository.findActiveUsersByRole(Role.FACILITY_MANAGER))
                .thenReturn(List.of(fmUser));
        when(notificationRepository.existsByReferenceIdAndType(refId, NotificationType.PENDING_OVERDUE))
                .thenReturn(false);
        when(notificationRepository.saveAll(anyList())).thenAnswer(i -> i.getArgument(0));

        inAppNotificationService.scanAndNotifyPendingOverdueRequests();

        verify(notificationRepository, times(1)).saveAll(anyList());
    }

    @Test
    @DisplayName("getMyNotifications phân trang và trả về đúng danh sách thông báo")
    void getMyNotifications_Success() {
        Notification notif = Notification.builder()
                .id(UUID.randomUUID())
                .recipient(user1)
                .title("Thông báo 1")
                .message("Nội dung 1")
                .type(NotificationType.REQUEST_APPROVED)
                .isRead(false)
                .createdAt(OffsetDateTime.now())
                .build();

        Pageable pageable = PageRequest.of(0, 10);
        when(userRepository.findByEmail(user1.getEmail())).thenReturn(Optional.of(user1));
        when(notificationRepository.findByRecipientIdOrderByCreatedAtDesc(user1.getId(), pageable))
                .thenReturn(new PageImpl<>(List.of(notif), pageable, 1));

        Page<NotificationResponse> result = inAppNotificationService.getMyNotifications(user1.getEmail(), pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        assertEquals("Thông báo 1", result.getContent().get(0).title());
    }

    @Test
    @DisplayName("getUnreadCount trả về đúng số thông báo chưa đọc")
    void getUnreadCount_Success() {
        when(userRepository.findByEmail(user1.getEmail())).thenReturn(Optional.of(user1));
        when(notificationRepository.countByRecipientIdAndIsReadFalse(user1.getId())).thenReturn(5L);

        long count = inAppNotificationService.getUnreadCount(user1.getEmail());

        assertEquals(5L, count);
    }

    @Test
    @DisplayName("markAllAsRead cập nhật tất cả thông báo chưa đọc của người dùng")
    void markAllAsRead_Success() {
        when(userRepository.findByEmail(user1.getEmail())).thenReturn(Optional.of(user1));
        when(notificationRepository.markAllAsRead(eq(user1.getId()), any(OffsetDateTime.class))).thenReturn(4);

        int updated = inAppNotificationService.markAllAsRead(user1.getEmail());

        assertEquals(4, updated);
        verify(notificationRepository).markAllAsRead(eq(user1.getId()), any(OffsetDateTime.class));
    }
}
