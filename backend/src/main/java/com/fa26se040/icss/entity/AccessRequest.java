package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.CancelSource;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "access_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AccessRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "area_id", nullable = false)
    private Area area;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "requester_id", nullable = false)
    private User requester;

    @Enumerated(EnumType.STRING)
    @Column(name = "request_type", nullable = false, length = 20)
    private RequestType requestType;

    @Column(name = "purpose", columnDefinition = "TEXT", nullable = false)
    private String purpose;

    @Column(name = "start_time", nullable = false)
    private OffsetDateTime startTime;

    @Column(name = "end_time", nullable = false)
    private OffsetDateTime endTime;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private RequestStatus status = RequestStatus.PENDING;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reviewer_id")
    private User reviewer;

    @Column(name = "reviewed_at")
    private OffsetDateTime reviewedAt;

    @Column(name = "rejection_reason", columnDefinition = "TEXT")
    private String rejectionReason;

    // Step 5b (BR-TC-15): nguồn huỷ đơn. NULL ở đơn CANCELLED trước V56 = không rõ nguồn.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "cancel_source", length = 10)
    private CancelSource cancelSource;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    // V88 (BR-RQ-46): lý do và người kết thúc đơn, ghi cùng câu UPDATE có điều kiện finishIfApprovedStarted
    @Column(name = "finish_reason", length = 500)
    private String finishReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finished_by")
    private User finishedBy;

    @OneToMany(mappedBy = "accessRequest", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<AccessRequestMember> members = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
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
        if (status == null) {
            status = RequestStatus.PENDING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** T1b: trạng thái hiệu lực tại now, xem {@link #effectiveStatus(RequestStatus, OffsetDateTime, OffsetDateTime, OffsetDateTime)}. */
    public RequestStatus effectiveStatus(OffsetDateTime now) {
        return effectiveStatus(status, startTime, endTime, now);
    }

    /**
     * T1b (Lucas chốt 11/11): trạng thái hiển thị / dùng cho thao tác ngay tại now, không chờ job.
     * <ul>
     *   <li>lưu APPROVED và now ≥ endTime -> FINISHED (hết giờ; job finishElapsedApprovedRequests sẽ ghi sau)</li>
     *   <li>lưu PENDING và now ≥ startTime -> EXPIRED (job expireOverdueRequests sẽ ghi sau)</li>
     *   <li>còn lại -> trạng thái lưu</li>
     * </ul>
     * Cùng điều kiện với bộ lọc AccessRequestSpecification và các câu UPDATE có điều kiện của AccessRequestRepository.
     */
    public static RequestStatus effectiveStatus(RequestStatus stored, OffsetDateTime startTime, OffsetDateTime endTime,
                                                OffsetDateTime now) {
        if (stored == RequestStatus.APPROVED && endTime != null && !now.isBefore(endTime)) {
            return RequestStatus.FINISHED;
        }
        if (stored == RequestStatus.PENDING && startTime != null && !now.isBefore(startTime)) {
            return RequestStatus.EXPIRED;
        }
        return stored;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccessRequest that = (AccessRequest) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
