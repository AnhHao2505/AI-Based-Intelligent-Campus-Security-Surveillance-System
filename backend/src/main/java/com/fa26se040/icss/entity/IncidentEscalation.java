package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.EscalationCloseReason;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Bản ghi leo thang của một sự cố (V80, BR-IE-03). Tạo cùng transaction với sự cố;
 * job leo thang đóng bản ghi bằng conditional UPDATE (BR-IE-07), không sửa qua entity.
 */
@Entity
@Table(name = "incident_escalations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentEscalation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false, unique = true, updatable = false)
    private SecurityIncident incident;

    @Column(name = "guards_notified_at", nullable = false)
    private OffsetDateTime guardsNotifiedAt;

    @Column(name = "guard_recipient_count", nullable = false)
    private Integer guardRecipientCount;

    @Column(name = "fm_escalated_at")
    private OffsetDateTime fmEscalatedAt;

    @Column(name = "fm_recipient_count")
    private Integer fmRecipientCount;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason", length = 30)
    private EscalationCloseReason closeReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = OffsetDateTime.now();
        }
    }
}
