package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
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
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(
    name = "guard_shifts",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_guard_shift_slot", columnNames = {"guard_id", "shift_date", "start_time"})
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuardShift {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "guard_id", nullable = false)
    private User guard;

    @Column(name = "shift_date", nullable = false)
    private LocalDate shiftDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "shift_type", nullable = false, length = 20)
    private ShiftType shiftType;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "area_id")
    private Area area;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "building_id")
    private Building building;

    @Column(name = "is_cross_building", nullable = false)
    @Builder.Default
    private Boolean isCrossBuilding = false;

    @Column(name = "cross_building_reason")
    private String crossBuildingReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private ShiftStatus status = ShiftStatus.SCHEDULED;

    @Column(name = "check_in_at")
    private OffsetDateTime checkInAt;

    @Column(name = "check_out_at")
    private OffsetDateTime checkOutAt;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "is_overtime", nullable = false)
    @Builder.Default
    private Boolean isOvertime = false;

    // V90 (BR-DSP-13): vị trí trực của ca, FK duty_post_types.code; ca sinh tự động và ca không chọn = PATROL
    @Column(name = "duty_post_code", nullable = false, length = 30)
    @Builder.Default
    private String dutyPostCode = DutyPostType.DEFAULT_CODE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        truncateShiftTimes();
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.status == null) {
            this.status = ShiftStatus.SCHEDULED;
        }
        if (this.dutyPostCode == null || this.dutyPostCode.isBlank()) {
            this.dutyPostCode = DutyPostType.DEFAULT_CODE;
        }
        if (this.isCrossBuilding == null) {
            this.isCrossBuilding = false;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        truncateShiftTimes();
        this.updatedAt = OffsetDateTime.now();
    }

    // Hibernate 6.4 + TZ +07: giờ trước 07:00 có phần lẻ của giây đọc lại bị lỗi NanoOfSecond âm, nên cắt về giây khi ghi
    private void truncateShiftTimes() {
        if (this.startTime != null) this.startTime = this.startTime.truncatedTo(ChronoUnit.SECONDS);
        if (this.endTime != null) this.endTime = this.endTime.truncatedTo(ChronoUnit.SECONDS);
    }
}
