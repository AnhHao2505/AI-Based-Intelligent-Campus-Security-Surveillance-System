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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "system_configurations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemConfiguration {

    @Id
    @Column(name = "config_key", length = 100, nullable = false)
    private String configKey;

    @Column(name = "config_value", length = 255, nullable = false)
    private String configValue;

    @Column(name = "data_type", length = 20, nullable = false)
    private String dataType;

    @Column(name = "min_value")
    private BigDecimal minValue;

    @Column(name = "max_value")
    private BigDecimal maxValue;

    @Column(name = "unit", length = 30)
    private String unit;

    @Column(name = "config_group", length = 50, nullable = false)
    private String configGroup;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "description", nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(name = "editable", nullable = false)
    @Builder.Default
    private Boolean editable = true;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "updated_by")
    private User updatedBy;

    @PrePersist
    protected void onCreate() {
        if (updatedAt == null) {
            updatedAt = OffsetDateTime.now();
        }
        if (editable == null) {
            editable = true;
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
        SystemConfiguration that = (SystemConfiguration) o;
        return Objects.equals(configKey, that.configKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(configKey);
    }
}
