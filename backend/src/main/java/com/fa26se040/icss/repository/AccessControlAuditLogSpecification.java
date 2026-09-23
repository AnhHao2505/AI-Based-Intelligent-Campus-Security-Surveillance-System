package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AccessControlAuditLog;
import com.fa26se040.icss.enums.AccessControlTargetType;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AccessControlAuditLogSpecification {

    private AccessControlAuditLogSpecification() {}

    public static Specification<AccessControlAuditLog> filter(
            AccessControlTargetType targetType,
            UUID areaId,
            UUID subjectUserId,
            UUID changedBy,
            OffsetDateTime fromDate,
            OffsetDateTime toDate
    ) {
        return (root, query, cb) -> {
            Class<?> resultType = query.getResultType();
            if (resultType != null && !Long.class.equals(resultType) && !long.class.equals(resultType)) {
                root.fetch("changedBy", JoinType.LEFT);
                root.fetch("subjectUser", JoinType.LEFT);
                root.fetch("area", JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();

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
