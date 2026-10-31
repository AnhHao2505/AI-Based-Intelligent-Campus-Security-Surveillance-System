package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.snapshot.DutyPostTypeAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.IncidentDispatchPolicyAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.IncidentSeverityRuleAuditSnapshot;
import com.fa26se040.icss.dto.incidentpolicy.DutyPostTypeResponse;
import com.fa26se040.icss.dto.incidentpolicy.DutyPostTypeUpdateRequest;
import com.fa26se040.icss.dto.incidentpolicy.IncidentDispatchPolicyResponse;
import com.fa26se040.icss.dto.incidentpolicy.IncidentDispatchPolicyUpdateRequest;
import com.fa26se040.icss.dto.incidentpolicy.IncidentSeverityRuleResponse;
import com.fa26se040.icss.dto.incidentpolicy.IncidentSeverityRuleUpdateRequest;
import com.fa26se040.icss.entity.DutyPostType;
import com.fa26se040.icss.entity.IncidentDispatchPolicy;
import com.fa26se040.icss.entity.IncidentSeverityRule;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.exception.IncidentPolicyErrorCode;
import com.fa26se040.icss.exception.IncidentPolicyException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.DutyPostTypeRepository;
import com.fa26se040.icss.repository.IncidentDispatchPolicyRepository;
import com.fa26se040.icss.repository.IncidentSeverityRuleRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Màn chính sách sự cố (V90, ADMIN): luật severity (BR-SEV-04), chính sách dispatch theo mức và danh mục vị trí trực
 * (BR-DSP-12). Chỉ xem / sửa, không thêm xoá dòng. Mỗi lần sửa: so version client gửi → ghi kèm @Version → audit.
 * Xung đột (version lệch hoặc optimistic lock lúc flush) trả ERR_INCPOL_004, không rơi vào handler chung ERR_AC_003.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentPolicyService {

    private final IncidentSeverityRuleRepository severityRuleRepository;
    private final IncidentDispatchPolicyRepository dispatchPolicyRepository;
    private final DutyPostTypeRepository dutyPostTypeRepository;
    private final AuditService auditService;
    private final UserRepository userRepository;

    // ================================================================== luật severity

    @Transactional(readOnly = true)
    public List<IncidentSeverityRuleResponse> getSeverityRules() {
        // Sắp theo cấp khu 1/2/3 (DB chỉ sắp được theo tên enum)
        return severityRuleRepository.findAll().stream()
                .sorted(Comparator.comparing(IncidentSeverityRule::getEventType)
                        .thenComparing(r -> r.getAreaLevelCode().level())
                        .thenComparing(IncidentSeverityRule::getTimeWindow))
                .map(this::mapRule)
                .toList();
    }

    @Transactional
    public IncidentSeverityRuleResponse updateSeverityRule(UUID id, IncidentSeverityRuleUpdateRequest req, String actorEmail) {
        IncidentSeverityRule rule = severityRuleRepository.findById(id)
                .orElseThrow(() -> new IncidentPolicyException(IncidentPolicyErrorCode.ERR_INCPOL_001));
        checkVersion(rule.getVersion(), req.version());

        if (rule.getSeverity() == req.severity()) {
            log.info("IncidentSeverityRule {} unchanged, skipping audit", id);
            return mapRule(rule);
        }

        User actor = currentUser(actorEmail);
        IncidentSeverityRuleAuditSnapshot oldSnapshot = snapshotOf(rule);

        rule.setSeverity(req.severity());
        rule.setUpdatedBy(actor);
        IncidentSeverityRule saved = flushOrConflict(() -> severityRuleRepository.saveAndFlush(rule));

        auditService.record(
                AuditTargetType.INCIDENT_SEVERITY_RULE,
                AuditAction.UPDATE,
                saved.getId().toString(),
                null,
                null,
                oldSnapshot,
                snapshotOf(saved),
                "Cập nhật luật mức độ sự cố " + saved.getEventType() + " / " + saved.getAreaLevelCode().name()
                        + " / " + saved.getTimeWindow().name() + ": " + oldSnapshot.severity() + " -> " + saved.getSeverity().name(),
                actor
        );
        return mapRule(saved);
    }

    // ================================================================== chính sách dispatch

    @Transactional(readOnly = true)
    public List<IncidentDispatchPolicyResponse> getDispatchPolicies() {
        return dispatchPolicyRepository.findAll().stream()
                .sorted(Comparator.comparing(IncidentDispatchPolicy::getSeverity))
                .map(this::mapPolicy)
                .toList();
    }

    @Transactional
    public IncidentDispatchPolicyResponse updateDispatchPolicy(IncidentSeverity severity, IncidentDispatchPolicyUpdateRequest req,
                                                               String actorEmail) {
        IncidentDispatchPolicy policy = dispatchPolicyRepository.findById(severity)
                .orElseThrow(() -> new IncidentPolicyException(IncidentPolicyErrorCode.ERR_INCPOL_002, severity));
        checkVersion(policy.getVersion(), req.version());
        validatePolicyRanges(req);

        boolean unchanged = Objects.equals(req.step1Size(), policy.getStep1Size().intValue())
                && Objects.equals(req.step1TimeoutSeconds(), policy.getStep1TimeoutSeconds())
                && Objects.equals(req.escalateFmMinutes(), policy.getEscalateFmMinutes())
                && Objects.equals(req.notifyFmImmediately(), policy.getNotifyFmImmediately());
        if (unchanged) {
            log.info("IncidentDispatchPolicy {} unchanged, skipping audit", severity);
            return mapPolicy(policy);
        }

        User actor = currentUser(actorEmail);
        IncidentDispatchPolicyAuditSnapshot oldSnapshot = snapshotOf(policy);

        policy.setStep1Size(req.step1Size().shortValue());
        policy.setStep1TimeoutSeconds(req.step1TimeoutSeconds());
        policy.setEscalateFmMinutes(req.escalateFmMinutes());
        policy.setNotifyFmImmediately(req.notifyFmImmediately());
        policy.setUpdatedBy(actor);
        IncidentDispatchPolicy saved = flushOrConflict(() -> dispatchPolicyRepository.saveAndFlush(policy));

        auditService.record(
                AuditTargetType.INCIDENT_DISPATCH_POLICY,
                AuditAction.UPDATE,
                saved.getSeverity().name(),
                null,
                null,
                oldSnapshot,
                snapshotOf(saved),
                "Cập nhật chính sách phân phối sự cố mức " + saved.getSeverity().name(),
                actor
        );
        return mapPolicy(saved);
    }

    /** K2: step1_size, step1_timeout_seconds, escalate_fm_minutes trong khoảng khai báo ở IncidentDispatchPolicy (trùng CHECK V90). */
    static void validatePolicyRanges(IncidentDispatchPolicyUpdateRequest req) {
        if (req.step1Size() < IncidentDispatchPolicy.STEP1_SIZE_MIN || req.step1Size() > IncidentDispatchPolicy.STEP1_SIZE_MAX) {
            throw new IncidentPolicyException(IncidentPolicyErrorCode.ERR_INCPOL_005,
                    IncidentDispatchPolicy.STEP1_SIZE_MIN, IncidentDispatchPolicy.STEP1_SIZE_MAX);
        }
        if (req.step1TimeoutSeconds() < IncidentDispatchPolicy.STEP1_TIMEOUT_SECONDS_MIN
                || req.step1TimeoutSeconds() > IncidentDispatchPolicy.STEP1_TIMEOUT_SECONDS_MAX) {
            throw new IncidentPolicyException(IncidentPolicyErrorCode.ERR_INCPOL_006,
                    IncidentDispatchPolicy.STEP1_TIMEOUT_SECONDS_MIN, IncidentDispatchPolicy.STEP1_TIMEOUT_SECONDS_MAX);
        }
        if (req.escalateFmMinutes() < IncidentDispatchPolicy.ESCALATE_FM_MINUTES_MIN
                || req.escalateFmMinutes() > IncidentDispatchPolicy.ESCALATE_FM_MINUTES_MAX) {
            throw new IncidentPolicyException(IncidentPolicyErrorCode.ERR_INCPOL_007,
                    IncidentDispatchPolicy.ESCALATE_FM_MINUTES_MIN, IncidentDispatchPolicy.ESCALATE_FM_MINUTES_MAX);
        }
    }

    // ================================================================== danh mục vị trí trực

    @Transactional(readOnly = true)
    public List<DutyPostTypeResponse> getDutyPostTypes() {
        return dutyPostTypeRepository.findAllByOrderBySortOrderAscCodeAsc().stream().map(this::mapDutyPost).toList();
    }

    /** Cho dropdown xếp ca (FM): chỉ vị trí đang sử dụng. */
    @Transactional(readOnly = true)
    public List<DutyPostTypeResponse> getActiveDutyPostTypes() {
        return dutyPostTypeRepository.findByIsActiveTrueOrderBySortOrderAscCodeAsc().stream().map(this::mapDutyPost).toList();
    }

    @Transactional
    public DutyPostTypeResponse updateDutyPostType(String code, DutyPostTypeUpdateRequest req, String actorEmail) {
        DutyPostType post = dutyPostTypeRepository.findById(code)
                .orElseThrow(() -> new IncidentPolicyException(IncidentPolicyErrorCode.ERR_INCPOL_003, code));
        checkVersion(post.getVersion(), req.version());
        if (DutyPostType.DEFAULT_CODE.equals(post.getCode()) && !Boolean.TRUE.equals(req.isActive())) {
            throw new IncidentPolicyException(IncidentPolicyErrorCode.ERR_INCPOL_008, DutyPostType.DEFAULT_CODE);
        }

        String newLabel = req.label().trim();
        Integer newSortOrder = req.sortOrder() != null ? req.sortOrder() : post.getSortOrder();
        boolean unchanged = Objects.equals(newLabel, post.getLabel())
                && Objects.equals(req.dispatchable(), post.getDispatchable())
                && Objects.equals(req.isActive(), post.getIsActive())
                && Objects.equals(newSortOrder, post.getSortOrder());
        if (unchanged) {
            log.info("DutyPostType {} unchanged, skipping audit", code);
            return mapDutyPost(post);
        }

        User actor = currentUser(actorEmail);
        DutyPostTypeAuditSnapshot oldSnapshot = snapshotOf(post);

        post.setLabel(newLabel);
        post.setDispatchable(req.dispatchable());
        post.setIsActive(req.isActive());
        post.setSortOrder(newSortOrder);
        post.setUpdatedBy(actor);
        DutyPostType saved = flushOrConflict(() -> dutyPostTypeRepository.saveAndFlush(post));

        auditService.record(
                AuditTargetType.DUTY_POST_TYPE,
                AuditAction.UPDATE,
                saved.getCode(),
                null,
                null,
                oldSnapshot,
                snapshotOf(saved),
                "Cập nhật vị trí trực " + saved.getCode(),
                actor
        );
        return mapDutyPost(saved);
    }

    // ================================================================== dùng chung

    static void checkVersion(Long current, Long clientVersion) {
        if (!Objects.equals(current, clientVersion)) {
            throw new IncidentPolicyException(IncidentPolicyErrorCode.ERR_INCPOL_004);
        }
    }

    /** Ghi xuống DB ngay; người khác vừa ghi cùng dòng (@Version lệch lúc flush) -> ERR_INCPOL_004. */
    private static <T> T flushOrConflict(Supplier<T> save) {
        try {
            return save.get();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new IncidentPolicyException(IncidentPolicyErrorCode.ERR_INCPOL_004);
        }
    }

    private User currentUser(String actorEmail) {
        return userRepository.findByEmail(actorEmail)
                .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));
    }

    private static String nameOf(User user) {
        return user != null ? user.getFullName() : null;
    }

    private static IncidentSeverityRuleAuditSnapshot snapshotOf(IncidentSeverityRule r) {
        return new IncidentSeverityRuleAuditSnapshot(
                r.getEventType(), r.getAreaLevelCode().name(), r.getTimeWindow().name(), r.getSeverity().name());
    }

    private static IncidentDispatchPolicyAuditSnapshot snapshotOf(IncidentDispatchPolicy p) {
        return new IncidentDispatchPolicyAuditSnapshot(
                p.getSeverity().name(), p.getStep1Size().intValue(), p.getStep1TimeoutSeconds(),
                p.getEscalateFmMinutes(), p.getNotifyFmImmediately());
    }

    private static DutyPostTypeAuditSnapshot snapshotOf(DutyPostType d) {
        return new DutyPostTypeAuditSnapshot(d.getCode(), d.getLabel(), d.getDispatchable(), d.getIsActive(), d.getSortOrder());
    }

    private IncidentSeverityRuleResponse mapRule(IncidentSeverityRule r) {
        return new IncidentSeverityRuleResponse(
                r.getId(), r.getEventType(), r.getAreaLevelCode(), r.getTimeWindow(), r.getSeverity(),
                r.getVersion(), r.getUpdatedAt(), nameOf(r.getUpdatedBy()));
    }

    private IncidentDispatchPolicyResponse mapPolicy(IncidentDispatchPolicy p) {
        return new IncidentDispatchPolicyResponse(
                p.getSeverity(), p.getStep1Size().intValue(), p.getStep1TimeoutSeconds(), p.getEscalateFmMinutes(),
                p.getNotifyFmImmediately(), p.getVersion(), p.getUpdatedAt(), nameOf(p.getUpdatedBy()));
    }

    private DutyPostTypeResponse mapDutyPost(DutyPostType d) {
        return new DutyPostTypeResponse(
                d.getCode(), d.getLabel(), d.getDispatchable(), d.getIsActive(), d.getSortOrder(),
                d.getVersion(), d.getUpdatedAt(), nameOf(d.getUpdatedBy()));
    }
}
