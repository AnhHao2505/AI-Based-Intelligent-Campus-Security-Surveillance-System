package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.RecordingStatus;
import com.fa26se040.icss.enums.RecordingStorageLocation;
import com.fa26se040.icss.enums.RecordingType;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "camera_recordings")
@Getter
@Setter
@ToString(exclude = {"camera", "incident"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraRecording {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camera_id")
    private Camera camera;

    @Column(name = "camera_code", nullable = false, length = 50)
    private String cameraCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id")
    private SecurityIncident incident;

    @Enumerated(EnumType.STRING)
    @Column(name = "recording_type", nullable = false, length = 30)
    private RecordingType recordingType;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_location", nullable = false, length = 20)
    private RecordingStorageLocation storageLocation;

    @Column(name = "storage_bucket", length = 100)
    private String storageBucket;

    @Column(name = "storage_key", nullable = false, unique = true, length = 500)
    private String storageKey;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "start_time", nullable = false)
    private OffsetDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private OffsetDateTime endTime;

    @Column(name = "duration_seconds", nullable = false)
    private Integer durationSeconds;

    @Column(name = "file_size_bytes", nullable = false)
    private Long fileSizeBytes;

    @Column(name = "resolution", nullable = false, length = 30)
    @Builder.Default
    private String resolution = "854x480";

    @Column(name = "fps", nullable = false)
    @Builder.Default
    private Double fps = 15.0;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RecordingStatus status = RecordingStatus.READY;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (resolution == null || resolution.isBlank()) {
            resolution = "854x480";
        }
        if (fps == null || fps <= 0) {
            fps = 15.0;
        }
        if (status == null) {
            status = RecordingStatus.READY;
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
        CameraRecording that = (CameraRecording) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
