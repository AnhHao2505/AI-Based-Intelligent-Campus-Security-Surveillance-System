package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.notification.NotificationResponse;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.NotificationRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class InAppNotificationService {

    public static final String REF_TYPE_ACCESS_REQUEST = "ACCESS_REQUEST";
    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final AccessRequestRepository accessRequestRepository;

    public static String formatTimeRange(OffsetDateTime startTime, OffsetDateTime endTime) {
        if (startTime == null || endTime == null) {
            return "";
        }
        String startDay = startTime.atZoneSameInstant(VN_ZONE).format(DATE_FORMATTER);
        String endDay = endTime.atZoneSameInstant(VN_ZONE).format(DATE_FORMATTER);
        String startClock = startTime.atZoneSameInstant(VN_ZONE).format(TIME_FORMATTER);
        String endClock = endTime.atZoneSameInstant(VN_ZONE).format(TIME_FORMATTER);

        if (startDay.equals(endDay)) {
            return startDay + ", " + startClock + "-" + endClock;
        } else {
            return startDay + " " + startClock + " - " + endDay + " " + endClock;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification createForUser(User recipient, NotificationType type, String title, String message, UUID referenceId) {
        return createForUser(recipient, type, title, message, referenceId, REF_TYPE_ACCESS_REQUEST);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Notification createForUser(User recipient, NotificationType type, String title, String message, UUID referenceId, String referenceType) {
        if (recipient == null) {
            log.warn("Cannot create notification {}: recipient is null", type);
            return null;
        }

        Notification notification = Notification.builder()
                .recipient(recipient)
                .type(type)
                .title(title)
                .message(message)
                .referenceId(referenceId)
                .referenceType(referenceType)
                .isRead(false)
                .createdAt(OffsetDateTime.now())
                .build();

        Notification saved = notificationRepository.save(notification);
        log.info("In-app notification created for user {} with type: {}", recipient.getEmail(), type);
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Notification> createForUsers(List<User> recipients, NotificationType type, String title, String message, UUID referenceId) {
        return createForUsers(recipients, type, title, message, referenceId, REF_TYPE_ACCESS_REQUEST);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<Notification> createForUsers(List<User> recipients, NotificationType type, String title, String message, UUID referenceId, String referenceType) {
        if (recipients == null || recipients.isEmpty()) {
            log.debug("createForUsers called with empty recipients for type {}", type);
            return List.of();
        }

        // Deduplicate recipients by user ID
        Map<UUID, User> uniqueRecipients = new LinkedHashMap<>();
        for (User user : recipients) {
            if (user != null && user.getId() != null) {
                uniqueRecipients.putIfAbsent(user.getId(), user);
            }
        }

        if (uniqueRecipients.isEmpty()) {
            return List.of();
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<Notification> notifications = new ArrayList<>();
        for (User user : uniqueRecipients.values()) {
            notifications.add(Notification.builder()
                    .recipient(user)
                    .type(type)
                    .title(title)
                    .message(message)
                    .referenceId(referenceId)
                    .referenceType(referenceType)
                    .isRead(false)
                    .createdAt(now)
                    .build());
        }

        List<Notification> savedList = notificationRepository.saveAll(notifications);
        log.info("Batch saved {} in-app notifications with type: {}", savedList.size(), type);
        return savedList;
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> getMyNotifications(String email, Pageable pageable) {
        User user = getUser(email);
        Page<Notification> page = notificationRepository.findByRecipientIdOrderByCreatedAtDesc(user.getId(), pageable);
        return page.map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(String email) {
        User user = getUser(email);
        return notificationRepository.countByRecipientIdAndIsReadFalse(user.getId());
    }

    @Transactional
    public NotificationResponse markAsRead(UUID id, String email) {
        User user = getUser(email);
        Notification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy thông báo với mã: " + id));

        if (!notification.getRecipient().getId().equals(user.getId())) {
            throw new AccessDeniedException("Bạn không có quyền truy cập thông báo này");
        }

        if (!Boolean.TRUE.equals(notification.getIsRead())) {
            notification.setIsRead(true);
            notification.setReadAt(OffsetDateTime.now());
            notification = notificationRepository.save(notification);
        }

        return mapToResponse(notification);
    }

    @Transactional
    public int markAllAsRead(String email) {
        User user = getUser(email);
        return notificationRepository.markAllAsRead(user.getId(), OffsetDateTime.now());
    }

    @Transactional(readOnly = true)
    public boolean existsByReferenceIdAndType(UUID referenceId, NotificationType type) {
        if (referenceId == null || type == null) {
            return false;
        }
        return notificationRepository.existsByReferenceIdAndType(referenceId, type);
    }

    /**
     * Quét các yêu cầu đã duyệt sắp bắt đầu trong vòng 25-35 phút để gửi EXPIRING_SOON.
     * Chạy định kỳ mỗi 10 phút.
     */
    @Scheduled(cron = "0 */10 * * * *")
    @Transactional
    public void scanAndNotifyExpiringSoonRequests() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime windowStart = now.plusMinutes(25);
        OffsetDateTime windowEnd = now.plusMinutes(35);

        List<AccessRequest> startingRequests = accessRequestRepository.findApprovedRequestsStartingBetween(
                RequestStatus.APPROVED,
                windowStart,
                windowEnd
        );

        if (startingRequests.isEmpty()) {
            return;
        }

        for (AccessRequest req : startingRequests) {
            try {
                if (existsByReferenceIdAndType(req.getId(), NotificationType.EXPIRING_SOON)) {
                    continue;
                }

                List<User> recipients = new ArrayList<>();
                if (req.getRequester() != null) {
                    recipients.add(req.getRequester());
                }
                if (req.getMembers() != null) {
                    for (var member : req.getMembers()) {
                        if (member.getUser() != null) {
                            recipients.add(member.getUser());
                        }
                    }
                }

                String areaName = req.getArea() != null ? req.getArea().getName() : "khu vực";
                String timeRange = formatTimeRange(req.getStartTime(), req.getEndTime());
                String title = "Sắp đến giờ truy cập khu vực";
                String message = "Yêu cầu vào " + areaName + " (" + timeRange + ") sắp bắt đầu trong 30 phút tới. Vui lòng chuẩn bị.";

                createForUsers(recipients, NotificationType.EXPIRING_SOON, title, message, req.getId(), REF_TYPE_ACCESS_REQUEST);
            } catch (Exception e) {
                log.error("Failed to process EXPIRING_SOON notification for request {}", req.getId(), e);
            }
        }
    }

    /**
     * Quét các yêu cầu PENDING tồn đọng quá 24 giờ để gửi PENDING_OVERDUE cho Facility Manager.
     * Chạy định kỳ mỗi 1 giờ.
     */
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void scanAndNotifyPendingOverdueRequests() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime threshold = now.minusHours(24);

        List<AccessRequest> overdueRequests = accessRequestRepository.findPendingRequestsCreatedBefore(
                RequestStatus.PENDING,
                threshold
        );

        if (overdueRequests.isEmpty()) {
            return;
        }

        List<User> fms = userRepository.findActiveUsersByRole(Role.FACILITY_MANAGER);
        if (fms.isEmpty()) {
            log.warn("No active Facility Managers found to send PENDING_OVERDUE notifications");
            return;
        }

        for (AccessRequest req : overdueRequests) {
            try {
                if (existsByReferenceIdAndType(req.getId(), NotificationType.PENDING_OVERDUE)) {
                    continue;
                }

                String areaName = req.getArea() != null ? req.getArea().getName() : "khu vực";
                String requesterName = req.getRequester() != null ? req.getRequester().getFullName() : "Người dùng";
                String timeRange = formatTimeRange(req.getStartTime(), req.getEndTime());
                String title = "Yêu cầu tồn đọng quá 24 giờ";
                String message = "Yêu cầu truy cập khu vực " + areaName + " của " + requesterName + " (" + timeRange + ") chưa được xử lý quá 24 giờ.";

                createForUsers(fms, NotificationType.PENDING_OVERDUE, title, message, req.getId(), REF_TYPE_ACCESS_REQUEST);
            } catch (Exception e) {
                log.error("Failed to process PENDING_OVERDUE notification for request {}", req.getId(), e);
            }
        }
    }

    private User getUser(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người dùng"));
    }

    private NotificationResponse mapToResponse(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getType(),
                n.getTitle(),
                n.getMessage(),
                n.getReferenceId(),
                n.getReferenceType(),
                Boolean.TRUE.equals(n.getIsRead()),
                n.getCreatedAt()
        );
    }
}
