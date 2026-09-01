package com.fa26se040.icss.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Objects;

@Entity
@Table(name = "area_levels")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AreaLevel {

    @Id
    private Short level;

    @Column(name = "code", nullable = false, length = 30, unique = true)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "requires_face_recognition", nullable = false)
    @Builder.Default
    private Boolean requiresFaceRecognition = true;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AreaLevel areaLevel = (AreaLevel) o;
        return level != null && Objects.equals(level, areaLevel.level);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
