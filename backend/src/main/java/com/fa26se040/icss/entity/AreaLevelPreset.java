package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.AreaLevel;
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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "area_level_presets")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AreaLevelPreset {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "area_level", nullable = false, length = 30)
    private AreaLevel areaLevel;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.SMALLINT)
    @Column(name = "area_access_level", nullable = false)
    private Integer areaAccessLevel;

    @Column(name = "explicit_authorization_required", nullable = false)
    private Boolean explicitAuthorizationRequired;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @PrePersist
    protected void onCreate() {
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AreaLevelPreset that = (AreaLevelPreset) o;
        return areaLevel == that.areaLevel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(areaLevel);
    }
}
