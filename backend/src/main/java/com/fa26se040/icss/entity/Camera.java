package com.fa26se040.icss.entity;

import com.fa26se040.icss.dto.camera.RoiGeometry;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "cameras")
@Getter
@Setter
@ToString(exclude = {"streamConfiguration", "healthLogs", "area"})
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

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private CameraStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "operational_status", nullable = false, length = 50)
    private OperationalStatus operationalStatus;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "roi_geometry", columnDefinition = "jsonb")
    private RoiGeometry roiGeometry;

    @OneToOne(mappedBy = "camera", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private CameraStreamConfiguration streamConfiguration;

    @OneToMany(mappedBy = "camera", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<CameraHealthLog> healthLogs;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "area_id")
    private Area area;

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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Camera camera = (Camera) o;
        return id != null && Objects.equals(id, camera.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
