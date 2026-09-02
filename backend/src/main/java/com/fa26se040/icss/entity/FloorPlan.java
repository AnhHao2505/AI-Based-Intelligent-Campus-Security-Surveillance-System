package com.fa26se040.icss.entity;

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
@Table(name = "floor_plans")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FloorPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "building", nullable = false, length = 50)
    private String building;

    @Column(name = "floor", nullable = false, length = 20)
    private String floor;

    @Column(name = "image_key", nullable = false, length = 255)
    private String imageKey;

    @Column(name = "original_width", nullable = false)
    private Integer originalWidth;

    @Column(name = "original_height", nullable = false)
    private Integer originalHeight;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now();
        }
        if (isActive == null) {
            isActive = true;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FloorPlan floorPlan = (FloorPlan) o;
        return id != null && Objects.equals(id, floorPlan.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
