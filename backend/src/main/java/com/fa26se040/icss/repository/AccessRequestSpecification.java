package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.enums.RequestStatus;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AccessRequestSpecification {

    private AccessRequestSpecification() {}

    public static Specification<AccessRequest> filter(
            UUID requesterId,
            RequestStatus status,
            UUID areaId
    ) {
        return (root, query, cb) -> {
            Class<?> resultType = query.getResultType();
            if (resultType != null && !Long.class.equals(resultType) && !long.class.equals(resultType)) {
                root.fetch("area", JoinType.LEFT);
                root.fetch("requester", JoinType.LEFT);
            }

            List<Predicate> predicates = new ArrayList<>();

            if (requesterId != null) {
                predicates.add(cb.equal(root.get("requester").get("id"), requesterId));
            }
            if (status != null) {
                predicates.add(cb.equal(root.get("status"), status));
            }
            if (areaId != null) {
                predicates.add(cb.equal(root.get("area").get("id"), areaId));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }
}
