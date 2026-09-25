package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.enums.RequestStatus;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AccessRequestSpecification {

    private AccessRequestSpecification() {}

    public static Specification<AccessRequest> filter(
            UUID userId,
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

            if (userId != null) {
                Predicate isRequester = cb.equal(root.get("requester").get("id"), userId);

                Subquery<Integer> memberSubquery = query.subquery(Integer.class);
                Root<AccessRequestMember> armRoot = memberSubquery.from(AccessRequestMember.class);
                memberSubquery.select(cb.literal(1));
                memberSubquery.where(
                        cb.equal(armRoot.get("accessRequest").get("id"), root.get("id")),
                        cb.equal(armRoot.get("user").get("id"), userId)
                );
                Predicate isMember = cb.exists(memberSubquery);

                predicates.add(cb.or(isRequester, isMember));
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
