package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.enums.RequestStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class AccessRequestSpecification {

    private AccessRequestSpecification() {}

    /** T1b: lọc theo trạng thái hiệu lực tại thời điểm gọi. */
    public static Specification<AccessRequest> filter(
            UUID userId,
            RequestStatus status,
            UUID areaId
    ) {
        return filter(userId, status, areaId, OffsetDateTime.now());
    }

    /**
     * T1b: status là trạng thái HIỆU LỰC tại now (AccessRequest.effectiveStatus) — điều kiện thời gian nằm trong câu truy vấn
     * nên phân trang / tổng số trang đúng:
     * <ul>
     *   <li>APPROVED: lưu APPROVED và endTime &gt; now</li>
     *   <li>FINISHED: lưu FINISHED, hoặc lưu APPROVED và endTime ≤ now</li>
     *   <li>PENDING: lưu PENDING và startTime &gt; now</li>
     *   <li>EXPIRED: lưu EXPIRED, hoặc lưu PENDING và startTime ≤ now</li>
     *   <li>còn lại: đúng trạng thái lưu</li>
     * </ul>
     */
    public static Specification<AccessRequest> filter(
            UUID userId,
            RequestStatus status,
            UUID areaId,
            OffsetDateTime now
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
                predicates.add(effectiveStatusPredicate(root, cb, status, now));
            }

            if (areaId != null) {
                predicates.add(cb.equal(root.get("area").get("id"), areaId));
            }

            return cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private static Predicate effectiveStatusPredicate(Root<AccessRequest> root, CriteriaBuilder cb, RequestStatus status,
                                                      OffsetDateTime now) {
        Path<RequestStatus> stored = root.get("status");
        Path<OffsetDateTime> start = root.get("startTime");
        Path<OffsetDateTime> end = root.get("endTime");
        return switch (status) {
            case APPROVED -> cb.and(cb.equal(stored, RequestStatus.APPROVED),
                    cb.or(cb.isNull(end), cb.greaterThan(end, now)));
            case FINISHED -> cb.or(cb.equal(stored, RequestStatus.FINISHED),
                    cb.and(cb.equal(stored, RequestStatus.APPROVED), cb.lessThanOrEqualTo(end, now)));
            case PENDING -> cb.and(cb.equal(stored, RequestStatus.PENDING),
                    cb.or(cb.isNull(start), cb.greaterThan(start, now)));
            case EXPIRED -> cb.or(cb.equal(stored, RequestStatus.EXPIRED),
                    cb.and(cb.equal(stored, RequestStatus.PENDING), cb.lessThanOrEqualTo(start, now)));
            default -> cb.equal(stored, status);
        };
    }
}
