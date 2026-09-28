package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
@Immutable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 30, nullable = false)
    private AuditTargetType targetType;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 30, nullable = false)
    private AuditAction action;

    @Column(name = "target_id", length = 100, nullable = false)
    private String targetId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "area_id")
    private Area area;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "subject_user_id")
    private User subjectUser;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "old_value", columnDefinition = "jsonb")
    private String oldValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "new_value", columnDefinition = "jsonb")
    private String newValue;

    @Column(name = "reason", length = 500)
    private String reason;

    @Column(name = "actor_type", length = 10, nullable = false)
    private String actorType; // "USER" or "SYSTEM"

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "changed_by")
    private User changedBy;

    @Column(name = "actor_source", length = 100)
    private String actorSource;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "changed_at", nullable = false)
    private OffsetDateTime changedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns({
            @JoinColumn(name = "target_type", referencedColumnName = "target_type", insertable = false, updatable = false),
            @JoinColumn(name = "action", referencedColumnName = "action", insertable = false, updatable = false)
    })
    private AuditEventType eventType;

    @PrePersist
    protected void onCreate() {
        if (changedAt == null) {
            changedAt = OffsetDateTime.now();
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AuditLog that = (AuditLog) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
