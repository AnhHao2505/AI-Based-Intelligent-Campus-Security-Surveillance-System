package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.AccessControlAuditLogResponse;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AccessControlAuditSnapshot;
import com.fa26se040.icss.entity.AccessControlAuditLog;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AccessControlAction;
import com.fa26se040.icss.enums.AccessControlTargetType;
import com.fa26se040.icss.repository.AccessControlAuditLogRepository;
import com.fa26se040.icss.repository.AccessControlAuditLogSpecification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AccessControlAuditService {

    private final AccessControlAuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * Ghi nhận 1 dòng audit log trong CÙNG transaction của nghiệp vụ (BR-AL-01).
     * Tham số old/new bắt buộc là record kế thừa AccessControlAuditSnapshot -> compile-time typesafe, không chứa PII (BR-AL-04).
     */
    @Transactional
    public <T extends AccessControlAuditSnapshot> AccessControlAuditLog record(
            AccessControlTargetType targetType,
            AccessControlAction action,
            String targetId,
            Area area,
            User subjectUser,
            T oldValue,
            T newValue,
            String reason,
            User changedBy
    ) {
        String cleanReason = reason != null ? reason.trim() : null;
        if (cleanReason != null && cleanReason.isEmpty()) {
            cleanReason = null;
        }

        JsonNode oldJson = oldValue != null ? objectMapper.valueToTree(oldValue) : null;
        JsonNode newJson = newValue != null ? objectMapper.valueToTree(newValue) : null;

        AccessControlAuditLog logEntry = AccessControlAuditLog.builder()
                .targetType(targetType)
                .action(action)
                .targetId(targetId)
                .area(area)
                .subjectUser(subjectUser)
                .oldValue(oldJson)
                .newValue(newJson)
                .reason(cleanReason)
                .changedBy(changedBy)
                .changedAt(OffsetDateTime.now())
                .build();

        AccessControlAuditLog saved = auditLogRepository.save(logEntry);
        log.info("Recorded access control audit log: id={}, targetType={}, action={}, targetId={}, actor={}",
                saved.getId(), targetType, action, targetId, changedBy != null ? changedBy.getUserCode() : "unknown");
        return saved;
    }

    /**
     * Tra cứu danh sách nhật ký kiểm toán có phân trang và bộ lọc (ADMIN, FM).
     */
    @Transactional(readOnly = true)
    public Page<AccessControlAuditLogResponse> getAuditLogs(
            AccessControlTargetType targetType,
            UUID areaId,
            UUID subjectUserId,
            UUID changedBy,
            OffsetDateTime fromDate,
            OffsetDateTime toDate,
            Pageable pageable
    ) {
        Pageable effectivePageable = pageable;
        if (pageable.getSort().isUnsorted()) {
            effectivePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "changedAt"));
        }
        Specification<AccessControlAuditLog> spec = AccessControlAuditLogSpecification.filter(
                targetType,
                areaId,
                subjectUserId,
                changedBy,
                fromDate,
                toDate
        );
        Page<AccessControlAuditLog> page = auditLogRepository.findAll(spec, effectivePageable);
        return page.map(this::mapToResponse);
    }

    private AccessControlAuditLogResponse mapToResponse(AccessControlAuditLog l) {
        User cb = l.getChangedBy();
        User su = l.getSubjectUser();
        Area a = l.getArea();

        return new AccessControlAuditLogResponse(
                l.getId(),
                l.getTargetType(),
                l.getAction(),
                l.getTargetId(),
                a != null ? a.getId() : null,
                a != null ? a.getName() : null,
                null,
                su != null ? su.getId() : null,
                su != null ? su.getFullName() : null,
                su != null ? su.getUserCode() : null,
                l.getOldValue(),
                l.getNewValue(),
                l.getReason(),
                cb != null ? cb.getId() : null,
                cb != null ? cb.getFullName() : null,
                cb != null ? cb.getUserCode() : null,
                l.getChangedAt()
        );
    }
}
