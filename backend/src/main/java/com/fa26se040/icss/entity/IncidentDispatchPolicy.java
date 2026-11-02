package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.IncidentSeverity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * Chính sách dispatch theo mức (V90, BR-DSP-12). Một dòng cho mỗi {@link IncidentSeverity}.
 * Khoảng hợp lệ khai báo một lần ở đây, trùng CHECK của bảng; câu lỗi nội suy từ các hằng này.
 */
@Entity
@Table(name = "incident_dispatch_policies")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IncidentDispatchPolicy {

    /** step1_size: 0 = phát cảnh báo toàn bộ ngay, không mời đợt 1. */
    public static final int STEP1_SIZE_MIN = 0;
    public static final int STEP1_SIZE_MAX = 10;
    public static final int STEP1_TIMEOUT_SECONDS_MIN = 10;
    public static final int STEP1_TIMEOUT_SECONDS_MAX = 600;
    public static final int ESCALATE_FM_MINUTES_MIN = 1;
    public static final int ESCALATE_FM_MINUTES_MAX = 60;

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "severity", nullable = false, length = 10)
    private IncidentSeverity severity;

    @Column(name = "step1_size", nullable = false)
    private Short step1Size;

    @Column(name = "step1_timeout_seconds", nullable = false)
    private Integer step1TimeoutSeconds;

    @Column(name = "escalate_fm_minutes", nullable = false)
    private Integer escalateFmMinutes;

    @Column(name = "notify_fm_immediately", nullable = false)
    @Builder.Default
    private Boolean notifyFmImmediately = false;

    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @PrePersist
    protected void onCreate() {
        this.updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }
}
