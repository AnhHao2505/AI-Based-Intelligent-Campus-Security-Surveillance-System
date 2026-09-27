package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.enums.AuditTargetType;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.UUID;

public final class AuditLogSpecification {

    private AuditLogSpecification() {}

    public static Specification<AuditLog> filter(
            String module,
            Collection<String> allowedModules,
            UUID correlationId,
            AuditTargetType targetType,
            UUID areaId,
            UUID subjectUserId,
            UUID changedBy,
            OffsetDateTime fromDate,
            OffsetDateTime toDate
    ) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();

            var eventTypeJoin = root.join("eventType", JoinType.LEFT);

            if (module != null && !module.isBlank()) {
                predicates.add(cb.equal(eventTypeJoin.get("module"), module));
            } else if (allowedModules != null && !allowedModules.isEmpty()) {
                predicates.add(eventTypeJoin.get("module").in(allowedModules));
            } else if (allowedModules != null) {
                predicates.add(cb.disjunction());
            }

            if (correlationId != null) {
                predicates.add(cb.equal(root.get("correlationId"), correlationId));
            }
            if (targetType != null) {
                predicates.add(cb.equal(root.get("targetType"), targetType));
            }
            if (areaId != null) {
                predicates.add(cb.equal(root.get("area").get("id"), areaId));
            }
            if (subjectUserId != null) {
                predicates.add(cb.equal(root.get("subjectUser").get("id"), subjectUserId));
            }
            if (changedBy != null) {
                predicates.add(cb.equal(root.get("changedBy").get("id"), changedBy));
            }
            if (fromDate != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("changedAt"), fromDate));
            }
            if (toDate != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("changedAt"), toDate));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
