package com.fa26se040.icss.repository;

import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.enums.AuditAction;
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

    /**
     * PR-AUDIT: lọc nhật ký tài khoản / đăng nhập / cấu hình. modules bắt buộc (đã giao với quyền của role).
     * subjectKeyword khớp (không phân biệt hoa thường) mã của người bị tác động hoặc target_id (mã lô, e-mail đăng nhập,
     * khoá cấu hình); actorKeyword khớp mã hoặc họ tên người thực hiện.
     */
    public static Specification<AuditLog> accountLogFilter(
            Collection<String> modules,
            Collection<AuditAction> actions,
            String subjectKeyword,
            UUID subjectUserId,
            String subjectUserCode,
            String actorKeyword,
            UUID correlationId,
            OffsetDateTime fromDate,
            OffsetDateTime toDate
    ) {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            var eventTypeJoin = root.join("eventType", JoinType.LEFT);
            predicates.add(eventTypeJoin.get("module").in(modules));

            if (actions != null && !actions.isEmpty()) {
                predicates.add(root.get("action").in(actions));
            }
            if (subjectKeyword != null && !subjectKeyword.isBlank()) {
                String like = "%" + subjectKeyword.trim().toLowerCase() + "%";
                var subjectJoin = root.join("subjectUser", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("targetId")), like),
                        cb.like(cb.lower(subjectJoin.get("userCode")), like)));
            }
            if (subjectUserId != null) {
                // Dòng về tài khoản này: subject_user_id, hoặc target_id = id / mã (REGISTER_FACE khi tạo tài khoản lẻ ghi
                // trước khi có User nên chỉ có mã ở target_id)
                var subjectJoin = root.join("subjectUser", JoinType.LEFT);
                var byAccount = new ArrayList<Predicate>();
                byAccount.add(cb.equal(subjectJoin.get("id"), subjectUserId));
                byAccount.add(cb.equal(root.get("targetId"), subjectUserId.toString()));
                if (subjectUserCode != null && !subjectUserCode.isBlank()) {
                    byAccount.add(cb.equal(root.get("targetId"), subjectUserCode));
                }
                predicates.add(cb.or(byAccount.toArray(new Predicate[0])));
            }
            if (actorKeyword != null && !actorKeyword.isBlank()) {
                String like = "%" + actorKeyword.trim().toLowerCase() + "%";
                var actorJoin = root.join("changedBy", JoinType.LEFT);
                predicates.add(cb.or(
                        cb.like(cb.lower(actorJoin.get("userCode")), like),
                        cb.like(cb.lower(actorJoin.get("fullName")), like)));
            }
            if (correlationId != null) {
                predicates.add(cb.equal(root.get("correlationId"), correlationId));
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
