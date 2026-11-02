package com.fa26se040.icss.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Danh mục vị trí trực (V90, K10-a / BR-DSP-13). dispatchable = false thì bảo vệ ở vị trí đó không được mời
 * ở đợt 1, trừ khi không còn ai khác.
 */
@Entity
@Table(name = "duty_post_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DutyPostType {

    /** Vị trí mặc định của ca, trùng DEFAULT của cột guard_shifts.duty_post_code (V90). Không được ngừng sử dụng. */
    public static final String DEFAULT_CODE = "PATROL";

    @Id
    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "label", nullable = false, length = 100)
    private String label;

    @Column(name = "dispatchable", nullable = false)
    @Builder.Default
    private Boolean dispatchable = true;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private Integer sortOrder = 0;

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
