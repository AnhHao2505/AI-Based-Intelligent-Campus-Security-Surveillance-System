package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.DispatchOfferCancelReason;
import com.fa26se040.icss.enums.DispatchOfferResponse;
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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Lời mời bước 1 (V93, K3, BR-DSP-04). {@code response} NULL = còn chờ. Mỗi bảo vệ tối đa một lời mời cho một sự cố.
 * Trạng thái chỉ đổi bằng conditional UPDATE {@code WHERE response IS NULL} trong {@code IncidentDispatchOfferRepository}.
 */
@Entity
@Table(name = "incident_dispatch_offers")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentDispatchOffer {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "incident_id", nullable = false, updatable = false)
    private SecurityIncident incident;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guard_id", nullable = false, updatable = false)
    private User guard;

    @Column(name = "offered_at", nullable = false, updatable = false)
    private OffsetDateTime offeredAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private OffsetDateTime expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "response", length = 10)
    private DispatchOfferResponse response;

    @Column(name = "responded_at")
    private OffsetDateTime respondedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_reason", length = 30)
    private DispatchOfferCancelReason cancelReason;
}
