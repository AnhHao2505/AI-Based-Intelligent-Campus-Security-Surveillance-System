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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
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

        // PR-AUDIT (V112): IP + user agent của request hiện hành (null khi không có request: job, luồng nền)
        HttpServletRequest request = currentRequest();
        if (request != null) {
            builder.ipAddress(truncate(request.getRemoteAddr(), 45))
                    .userAgent(truncate(request.getHeader("User-Agent"), 255));
        }

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
                l.getChangedAt(),
                l.getIpAddress(),
                l.getUserAgent()
        );
    }

    /** PR-AUDIT: ba module của tab "Nhật ký" màn Quản lý tài khoản. */
    public static final Set<String> ACCOUNT_LOG_MODULES = Set.of("ACCOUNT", "AUTH", "SYSTEM_CONFIG");

    /**
     * PR-AUDIT (B6): nhật ký tài khoản / đăng nhập / cấu hình. Chỉ trả module nằm trong cả ACCOUNT_LOG_MODULES lẫn
     * audit_module_roles của role người gọi; xin module ngoài quyền -> 403. Sắp changedAt giảm dần khi không chỉ định.
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> getAccountAuditLogs(
            Collection<String> modules,
            Collection<AuditAction> actions,
            String subjectKeyword,
            UUID subjectUserId,
            String actorKeyword,
            UUID correlationId,
            OffsetDateTime fromDate,
            OffsetDateTime toDate,
            Pageable pageable,
            String userRole
    ) {
        List<String> allowed = userRole != null
                ? auditModuleRoleRepository.findModulesByRole(userRole).stream().filter(ACCOUNT_LOG_MODULES::contains).toList()
                : List.of();
        List<String> effectiveModules;
        if (modules != null && !modules.isEmpty()) {
            for (String m : modules) {
                if (!allowed.contains(m)) {
                    throw new AccessDeniedException("Bạn không có quyền truy cập nhật ký kiểm toán module: " + m);
                }
            }
            effectiveModules = List.copyOf(modules);
        } else {
            effectiveModules = allowed;
        }
        if (effectiveModules.isEmpty()) {
            throw new AccessDeniedException("Bạn không có quyền truy cập nhật ký tài khoản");
        }

        Pageable effectivePageable = pageable;
        if (pageable.getSort().isUnsorted()) {
            effectivePageable = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.DESC, "changedAt"));
        }
        String subjectUserCode = subjectUserId != null
                ? userRepository.findById(subjectUserId).map(User::getUserCode).orElse(null)
                : null;
        Specification<AuditLog> spec = AuditLogSpecification.accountLogFilter(effectiveModules, actions, subjectKeyword,
                subjectUserId, subjectUserCode, actorKeyword, correlationId, fromDate, toDate);
        return auditLogRepository.findAll(spec, effectivePageable).map(this::mapToResponse);
    }

    /**
     * PR-AUDIT: người thực hiện cho thao tác gọi qua HTTP — user đang đăng nhập (SecurityContext, name = e-mail).
     * Không có phiên (gọi trực tiếp từ job / test) -> actor SYSTEM với nguồn fallbackSource.
     */
    @Transactional(readOnly = true)
    public AuditActor resolveCurrentActor(String fallbackSource) {
        org.springframework.security.core.Authentication auth =
                org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            User user = userRepository.findByEmail(auth.getName()).orElse(null);
            if (user != null) {
                return AuditActor.user(user);
            }
        }
        return AuditActor.system(fallbackSource);
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private <T> String serialize(T obj) {
        if (obj == null) return null;
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            // Không được ghi snapshot rỗng thay cho dữ liệu thật: lỗi -> thao tác rollback (LA4)
            log.error("Audit log serialize error for object of type {}: {}", obj.getClass().getName(), e.getMessage());
            throw new AuditWriteException("Không ghi được nhật ký hệ thống, thao tác chưa được thực hiện. Vui lòng thử lại.", e);
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
