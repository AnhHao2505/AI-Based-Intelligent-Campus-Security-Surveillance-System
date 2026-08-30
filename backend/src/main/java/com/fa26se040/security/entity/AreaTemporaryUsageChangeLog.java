package com.fa26se040.security.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "area_temporary_usage_change_logs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AreaTemporaryUsageChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "temporary_usage_id", nullable = false)
    private UUID temporaryUsageId;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Column(name = "action", nullable = false, length = 20)
    private String action;

    @Column(name = "old_end_time")
    private OffsetDateTime oldEndTime;

    @Column(name = "new_end_time", nullable = false)
    private OffsetDateTime newEndTime;

    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AreaTemporaryUsageChangeLog that = (AreaTemporaryUsageChangeLog) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
