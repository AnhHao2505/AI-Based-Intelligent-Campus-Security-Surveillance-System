package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
import com.fa26se040.icss.enums.IncidentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "security_incidents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SecurityIncident {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", length = 100)
    private String eventId;

    @Column(name = "camera_code", nullable = false, length = 50)
    private String cameraCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @Column(name = "building", nullable = false, length = 50)
    private String building;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "image_url", length = 512)
    private String imageUrl;

    @Column(name = "detected_at", nullable = false)
    private OffsetDateTime detectedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    @Builder.Default
    private IncidentStatus status = IncidentStatus.NEW;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "claimed_by")
    private User claimedBy;

    @Column(name = "claimed_at")
    private OffsetDateTime claimedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "resolved_by")
    private User resolvedBy;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20)
    private IncidentOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_category", length = 50)
    private IncidentResolutionCategory resolutionCategory;

    @Column(name = "resolution_notes", columnDefinition = "TEXT")
    private String resolutionNotes;

    @Column(name = "evidence_image_url", length = 512)
    private String evidenceImageUrl;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Integer version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.detectedAt == null) {
            this.detectedAt = now;
        }
        if (this.status == null) {
            this.status = IncidentStatus.NEW;
        }
        if (this.version == null) {
            this.version = 0;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
