package com.fa26se040.icss.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;

@Entity
@Table(name = "edge_device_statuses")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EdgeDeviceStatus {

    @Id
    @Column(name = "edge_id", length = 100, nullable = false)
    private String edgeId;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "last_seen", nullable = false)
    private OffsetDateTime lastSeen;

    @Column(name = "cameras_json", columnDefinition = "TEXT")
    private String camerasJson;

    @Column(name = "gpu")
    private Boolean gpu;

    @Column(name = "version", length = 50)
    private String version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
        if (status == null) {
            status = "ONLINE";
        }
        if (lastSeen == null) {
            lastSeen = OffsetDateTime.now();
        }
    }

    @PreUpdate
    public void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
