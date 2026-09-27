package com.fa26se040.icss.service;

import com.fa26se040.icss.context.AuditContext;
import com.fa26se040.icss.dto.accesscontrol.AuditLogResponse;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AuditSnapshot;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.exception.AuditWriteException;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.AuditLogRepository;
import com.fa26se040.icss.repository.AuditLogSpecification;
import com.fa26se040.icss.repository.AuditModuleRoleRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;
    private final AuditModuleRoleRepository auditModuleRoleRepository;
    private final AreaRepository areaRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    /**
     * Chữ ký cũ (delegate, actor = user).
     */
    @Transactional
    public <T extends AuditSnapshot> AuditLog record(
            AuditTargetType targetType,
            AuditAction action,
            String targetId,
            Area area,
            User subjectUser,
            T oldSnapshot,
            T newSnapshot,
            String reason,
            User changedBy
    ) {
        return record(targetType, action, targetId, area, subjectUser, oldSnapshot, newSnapshot, reason,
                changedBy != null ? AuditActor.user(changedBy) : null);
    }

    @Transactional
    public <T extends AuditSnapshot> AuditLog record(
            AuditTargetType targetType,
            AuditAction action,
            String targetId,
            Area area,
            User subjectUser,
            T oldSnapshot,
            T newSnapshot,
            String reason
    ) {
        return record(targetType, action, targetId, area, subjectUser, oldSnapshot, newSnapshot, reason, (AuditActor) null);
    }

    /**
     * Helper ghi theo UUID nếu không có entity Area/User.
     */
    @Transactional
    public <T extends AuditSnapshot> AuditLog recordByIds(
            AuditTargetType targetType,
            AuditAction action,
            String targetId,
            UUID areaId,
            UUID subjectUserId,
            T oldSnapshot,
            T newSnapshot,
            String reason,
            AuditActor actor
    ) {
        Area area = areaId != null ? areaRepository.findById(areaId).orElse(null) : null;
        User subjectUser = subjectUserId != null ? userRepository.findById(subjectUserId).orElse(null) : null;
        return record(targetType, action, targetId, area, subjectUser, oldSnapshot, newSnapshot, reason, actor);
    }

    /**
     * Ghi nhận 1 bản ghi kiểm toán với AuditActor (USER hoặc SYSTEM) và Entity Area, User.
     */
    @Transactional
    public <T extends AuditSnapshot> AuditLog record(
            AuditTargetType targetType,
            AuditAction action,
            String targetId,
            Area area,
            User subjectUser,
            T oldSnapshot,
            T newSnapshot,
            String reason,
            AuditActor actor
    ) {
        if (actor == null) {
            actor = AuditContext.getCurrentActor();
        }

        UUID correlationId = AuditContext.getCorrelationId();
        if (correlationId == null) {
            correlationId = UUID.randomUUID();
            log.warn("Recording audit log without AuditContext correlationId. Generated ad-hoc correlationId={}", correlationId);
        }

        String oldVal = serialize(oldSnapshot);
        String newVal = serialize(newSnapshot);

        AuditLog.AuditLogBuilder builder = AuditLog.builder()
                .targetType(targetType)
                .action(action)
                .targetId(targetId)
                .area(area)
                .subjectUser(subjectUser)
                .oldValue(oldVal)
                .newValue(newVal)
                .reason(reason)
                .correlationId(correlationId);

        if (actor != null && "SYSTEM".equals(actor.getActorType())) {
            builder.actorType("SYSTEM")
                    .actorSource(actor.getActorSource())
                    .changedBy(null);
        } else if (actor != null && actor.getUser() != null) {
            builder.actorType("USER")
                    .changedBy(actor.getUser())
                    .actorSource(null);
        } else {
            builder.actorType("SYSTEM")
                    .actorSource("SYSTEM_AUTOMATION")
                    .changedBy(null);
        }

        AuditLog logEntry = builder.build();

        try {
            AuditLog saved = auditLogRepository.saveAndFlush(logEntry);
            log.info("Recorded audit log: id={}, targetType={}, action={}, targetId={}, actorType={}, correlationId={}",
                    saved.getId(), targetType, action, targetId, saved.getActorType(), saved.getCorrelationId());
            return saved;
        } catch (DataAccessException ex) {
            log.error("Failed to persist audit log: targetType={}, action={}, targetId={}", targetType, action, targetId, ex);
            throw new AuditWriteException("Không ghi được nhật ký hệ thống, thao tác chưa được thực hiện. Vui lòng thử lại.", ex);
        }
    }

    /**
     * Tra cứu danh sách nhật ký kiểm toán có phân trang và bộ lọc theo module và role người dùng.
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getAuditLogs(
            String module,
            UUID correlationId,
            AuditTargetType targetType,
            UUID areaId,
            UUID subjectUserId,
            UUID changedBy,
            OffsetDateTime fromDate,
            OffsetDateTime toDate,
            Pageable pageable,
            String userRole
    ) {
        List<String> allowedModules = userRole != null
                ? auditModuleRoleRepository.findModulesByRole(userRole)
                : List.of();

        if (module != null && !module.isBlank()) {
            if (!allowedModules.contains(module)) {
                throw new AccessDeniedException("Bạn không có quyền truy cập nhật ký kiểm toán module: " + module);
            }
        }

        Pageable effectivePageable = pageable;
        if (pageable.getSort().isUnsorted()) {
            effectivePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "changedAt"));
        }

        Specification<AuditLog> spec = AuditLogSpecification.filter(
                module,
                allowedModules,
                correlationId,
                targetType,
                areaId,
                subjectUserId,
                changedBy,
                fromDate,
                toDate
        );
        Page<AuditLog> page = auditLogRepository.findAll(spec, effectivePageable);
        return page.map(this::mapToResponse);
    }

    private AuditLogResponse mapToResponse(AuditLog l) {
        User cb = l.getChangedBy();
        User su = l.getSubjectUser();
        Area a = l.getArea();
        String module = l.getEventType() != null ? l.getEventType().getModule() : null;

        return new AuditLogResponse(
                l.getId(),
                l.getTargetType(),
                l.getAction(),
                module,
                l.getTargetId(),
                a != null ? a.getId() : null,
                a != null ? a.getName() : null,
                su != null ? su.getId() : null,
                su != null ? su.getFullName() : null,
                su != null ? su.getUserCode() : null,
                deserializeJson(l.getOldValue()),
                deserializeJson(l.getNewValue()),
                l.getReason(),
                l.getActorType(),
                cb != null ? cb.getId() : null,
                cb != null ? cb.getFullName() : null,
                cb != null ? cb.getUserCode() : null,
                l.getActorSource(),
                l.getCorrelationId(),
                l.getChangedAt()
        );
    }

    private <T> String serialize(T obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Audit log serialize error for object of type {}: {}", obj.getClass().getName(), e.getMessage());
            return "{}";
        }
    }

    private Object deserializeJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            return json;
        }
    }
}
