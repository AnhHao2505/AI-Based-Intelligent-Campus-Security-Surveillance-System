package com.fa26se040.icss.entity;

import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Entity
@Table(name = "audit_event_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEventType {

    @EmbeddedId
    private Id id;

    @Column(name = "module", length = 30, nullable = false)
    private String module;

    @Column(name = "description", length = 255)
    private String description;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Id implements Serializable {
        @Enumerated(EnumType.STRING)
        @Column(name = "target_type", length = 30)
        private AuditTargetType targetType;

        @Enumerated(EnumType.STRING)
        @Column(name = "action", length = 30)
        private AuditAction action;
    }
}
