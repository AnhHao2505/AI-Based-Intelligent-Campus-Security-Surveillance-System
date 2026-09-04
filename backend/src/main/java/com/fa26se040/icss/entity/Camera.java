package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "cameras")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Camera {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "camera_code", nullable = false, unique = true, length = 50)
    private String cameraCode;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "mounting_height", precision = 5, scale = 2)
    private BigDecimal mountingHeight;

    @Column(name = "orientation", precision = 5, scale = 2)
    private BigDecimal orientation;

    @Column(name = "tilt_angle", precision = 5, scale = 2)
    private BigDecimal tiltAngle;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private CameraStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 50)
    private OperationalStatus operationalStatus;

    @Column(name = "installed_at")
    private OffsetDateTime installedAt;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @OneToOne(mappedBy = "camera", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private CameraSpecification specification;

    @OneToOne(mappedBy = "camera", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private CameraStreamConfiguration streamConfiguration;

    @OneToOne(mappedBy = "camera", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private CameraAIConfiguration aiConfiguration;

    @OneToMany(mappedBy = "camera", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<CameraHealthLog> healthLogs;

    @ManyToMany(mappedBy = "cameras", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<Area> areas = new HashSet<>();

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
