package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.GuestBiometricStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Khách của một lượt. Không có CCCD / SĐT / địa chỉ / email (BR-GV-02).
 */
@Entity
@Table(name = "guests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Guest {

    public static final String ANONYMIZED_NAME = "Khách đã ẩn danh";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private GuestVisit visit;

    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Column(name = "organization", length = 200)
    private String organization;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "biometric_status", nullable = false, length = 20)
    private GuestBiometricStatus biometricStatus = GuestBiometricStatus.NO_PHOTO;

    @Column(name = "photo_object_key", length = 255)
    private String photoObjectKey;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "consent_confirmed_by")
    private User consentConfirmedBy;

    @Column(name = "consent_confirmed_at")
    private OffsetDateTime consentConfirmedAt;

    @Column(name = "consent_notice_version", length = 50)
    private String consentNoticeVersion;

    @Column(name = "photo_attached_at")
    private OffsetDateTime photoAttachedAt;

    @Column(name = "biometric_deleted_at")
    private OffsetDateTime biometricDeletedAt;

    /** BR-GV-27: ảnh chưa xoá được trên MinIO, job thử lại (NULL = không còn gì phải xoá). */
    @Column(name = "pending_delete_object_key", length = 255)
    private String pendingDeleteObjectKey;

    @Column(name = "anonymized_at")
    private OffsetDateTime anonymizedAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
