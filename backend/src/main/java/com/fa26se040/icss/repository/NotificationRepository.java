package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByRecipientIdOrderByCreatedAtDesc(UUID recipientId, Pageable pageable);

    long countByRecipientIdAndIsReadFalse(UUID recipientId);

    boolean existsByReferenceIdAndType(UUID referenceId, NotificationType type);

    /** BR-GV-39: đã có thông báo loại này cho đối tượng, tạo từ thời điểm from trở đi (chống nhắc trùng không thêm cột). */
    boolean existsByReferenceIdAndTypeAndCreatedAtGreaterThanEqual(UUID referenceId, NotificationType type, java.time.OffsetDateTime from);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE Notification n SET n.isRead = true, n.readAt = :now " +
           "WHERE n.recipient.id = :recipientId AND n.isRead = false")
    int markAllAsRead(@Param("recipientId") UUID recipientId, @Param("now") OffsetDateTime now);
}
