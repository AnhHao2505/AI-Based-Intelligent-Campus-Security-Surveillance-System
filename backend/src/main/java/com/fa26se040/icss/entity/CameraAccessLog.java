package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.AccessResult;
import com.fa26se040.icss.enums.AccessSource;
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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "camera_access_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraAccessLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "camera_code", nullable = false, length = 50)
    private String cameraCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "user_code", length = 50)
    private String userCode;

    @Column(name = "full_name", length = 150)
    private String fullName;

    @Column(name = "user_access_level")
    private Integer userAccessLevel;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_result", nullable = false, length = 30)
    private AccessResult accessResult;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_source", length = 30)
    private AccessSource accessSource;

    @Column(name = "source_reference_id")
    private UUID sourceReferenceId;

    @Column(name = "reason_details", columnDefinition = "TEXT")
    private String reasonDetails;

    @Column(name = "logged_at", nullable = false)
    private OffsetDateTime loggedAt;

    @PrePersist
    protected void onCreate() {
        if (loggedAt == null) {
            loggedAt = OffsetDateTime.now();
        }
    }
}
