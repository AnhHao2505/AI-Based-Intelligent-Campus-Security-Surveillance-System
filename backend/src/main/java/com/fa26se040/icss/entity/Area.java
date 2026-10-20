package com.fa26se040.icss.entity;

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

import com.fa26se040.icss.enums.AreaLevel;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import lombok.ToString;

@Entity
@Table(name = "areas")
@Getter
@Setter
@ToString(exclude = {"cameras"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Area {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "name", nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "area_level", nullable = false, length = 30)
    private AreaLevel areaLevel;

    /**
     * V83 (loại ⇔ cấp): cột chỉ là bản sao của {@link AreaLevel#level()} cho FE / mobile / AI đọc, đồng bộ trước mỗi lần
     * ghi (CHECK chk_areas_level_matches_type). Getter trả cấp theo loại nên mọi nơi xét quyền dùng đúng cấp của loại.
     */
    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "area_access_level", nullable = false)
    @Builder.Default
    private Integer areaAccessLevel = 3;

    /** V83: không còn dùng trong quy tắc xét quyền, luôn false (CHECK chk_areas_explicit_unused). Giữ cột tới khi FE / mobile hết đọc. */
    @Column(name = "explicit_authorization_required", nullable = false)
    @Builder.Default
    private Boolean explicitAuthorizationRequired = false;

    @Column(name = "open_to_members", nullable = false)
    @Builder.Default
    private Boolean openToMembers = false;

    @Column(name = "open_until")
    private OffsetDateTime openUntil;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "floor_id")
    private Floor floorEntity;

    @Column(name = "building", length = 50)
    private String building;

    @Column(name = "floor", length = 50)
    private String floor;

    @Column(name = "center_latitude")
    private Double centerLatitude;

    @Column(name = "center_longitude")
    private Double centerLongitude;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * Step 5b (BR-TC-13): tăng đúng 1 mỗi lần dòng areas thay đổi thật (AreaService quản lý, không dùng @Version
     * vì AuditService.saveAndFlush có thể flush nhiều lần trong một thao tác).
     */
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    @OneToMany(mappedBy = "area", fetch = FetchType.LAZY)
    @Builder.Default
    private Set<Camera> cameras = new HashSet<>();

    @PrePersist
    protected void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (isActive == null) {
            isActive = true;
        }
        if (version == null) {
            version = 0L;
        }
        syncTypeRules();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = OffsetDateTime.now();
        syncTypeRules();
    }

    /** V83: cấp theo loại, cờ explicit luôn false. */
    private void syncTypeRules() {
        if (areaLevel != null) {
            areaAccessLevel = areaLevel.level();
        }
        explicitAuthorizationRequired = false;
    }

    /** Cấp truy cập của khu = cấp của loại (V83); chỉ khi chưa có loại mới trả giá trị cột. */
    public Integer getAreaAccessLevel() {
        return areaLevel != null ? areaLevel.level() : areaAccessLevel;
    }

    /** V83: cờ chỉ định đích danh đã bỏ khỏi quy tắc xét quyền, luôn false. */
    public Boolean getExplicitAuthorizationRequired() {
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Area area = (Area) o;
        return id != null && Objects.equals(id, area.id);
    }

    public boolean isEventActive(OffsetDateTime at) {
        if (at == null) return false;
        return Boolean.TRUE.equals(openToMembers) && openUntil != null && at.isBefore(openUntil);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
