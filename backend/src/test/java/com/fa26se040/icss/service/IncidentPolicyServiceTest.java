package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.accesscontrol.snapshot.IncidentDispatchPolicyAuditSnapshot;
import com.fa26se040.icss.dto.incidentpolicy.DutyPostTypeUpdateRequest;
import com.fa26se040.icss.dto.incidentpolicy.IncidentDispatchPolicyResponse;
import com.fa26se040.icss.dto.incidentpolicy.IncidentDispatchPolicyUpdateRequest;
import com.fa26se040.icss.dto.incidentpolicy.IncidentSeverityRuleUpdateRequest;
import com.fa26se040.icss.entity.DutyPostType;
import com.fa26se040.icss.entity.IncidentDispatchPolicy;
import com.fa26se040.icss.entity.IncidentSeverityRule;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentTimeWindow;
import com.fa26se040.icss.exception.IncidentPolicyErrorCode;
import com.fa26se040.icss.exception.IncidentPolicyException;
import com.fa26se040.icss.repository.DutyPostTypeRepository;
import com.fa26se040.icss.repository.IncidentDispatchPolicyRepository;
import com.fa26se040.icss.repository.IncidentSeverityRuleRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** K2 / BR-DSP-12 / BR-SEV-04: validate phạm vi, version, optimistic lock, audit của màn chính sách sự cố. */
@ExtendWith(MockitoExtension.class)
class IncidentPolicyServiceTest {

    @Mock
    private IncidentSeverityRuleRepository severityRuleRepository;

    @Mock
    private IncidentDispatchPolicyRepository dispatchPolicyRepository;

    @Mock
    private DutyPostTypeRepository dutyPostTypeRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private IncidentPolicyService service;

    private User admin;
    private IncidentDispatchPolicy highPolicy;

    @BeforeEach
    void setUp() {
        admin = User.builder().id(UUID.randomUUID()).email("admin@fpt.edu.vn").fullName("Quản trị viên").build();
        highPolicy = IncidentDispatchPolicy.builder()
                .severity(IncidentSeverity.HIGH)
                .step1Size((short) 2)
                .step1TimeoutSeconds(30)
                .escalateFmMinutes(5)
                .notifyFmImmediately(false)
                .version(3L)
                .build();
    }

    private static IncidentDispatchPolicyUpdateRequest policyReq(int size, int timeout, int escalate) {
        return new IncidentDispatchPolicyUpdateRequest(size, timeout, escalate, false, 3L);
    }

    // ------------------------------------------------------------------ phạm vi K2

    @ParameterizedTest(name = "size={0}, timeout={1}, escalate={2} hợp lệ")
    @DisplayName("Biên K2 hợp lệ: step1_size 0–10, timeout 10–600 giây, escalate 1–60 phút")
    @CsvSource({"0, 10, 1", "10, 600, 60", "1, 60, 15"})
    void validRanges(int size, int timeout, int escalate) {
        assertDoesNotThrow(() -> IncidentPolicyService.validatePolicyRanges(policyReq(size, timeout, escalate)));
    }

    @ParameterizedTest(name = "size={0}, timeout={1}, escalate={2} -> {3}")
    @DisplayName("Ngoài biên K2 -> mã lỗi riêng, câu lỗi nội suy đúng khoảng")
    @CsvSource({
            "-1, 30, 5, ERR_INCPOL_005, Số bảo vệ được mời ở đợt 1 phải từ 0 đến 10",
            "11, 30, 5, ERR_INCPOL_005, Số bảo vệ được mời ở đợt 1 phải từ 0 đến 10",
            "2, 9, 5, ERR_INCPOL_006, Thời gian chờ đợt 1 phải từ 10 đến 600 giây",
            "2, 601, 5, ERR_INCPOL_006, Thời gian chờ đợt 1 phải từ 10 đến 600 giây",
            "2, 30, 0, ERR_INCPOL_007, Thời gian chờ trước khi báo Quản lý CSVC phải từ 1 đến 60 phút",
            "2, 30, 61, ERR_INCPOL_007, Thời gian chờ trước khi báo Quản lý CSVC phải từ 1 đến 60 phút"
    })
    void outOfRange(int size, int timeout, int escalate, IncidentPolicyErrorCode code, String message) {
        IncidentPolicyException ex = assertThrows(IncidentPolicyException.class,
                () -> IncidentPolicyService.validatePolicyRanges(policyReq(size, timeout, escalate)));
        assertEquals(code, ex.getErrorCode());
        assertEquals(message, ex.getMessage());
    }

    @Test
    @DisplayName("Ngoài biên -> không ghi DB, không audit")
    void outOfRange_doesNotSave() {
        when(dispatchPolicyRepository.findById(IncidentSeverity.HIGH)).thenReturn(Optional.of(highPolicy));

        assertThrows(IncidentPolicyException.class,
                () -> service.updateDispatchPolicy(IncidentSeverity.HIGH, policyReq(11, 30, 5), admin.getEmail()));

        verify(dispatchPolicyRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    // ------------------------------------------------------------------ version / optimistic lock

    @Test
    @DisplayName("Version client gửi lệch version hiện tại -> 409 ERR_INCPOL_004, không ghi")
    void staleVersion_conflict() {
        when(dispatchPolicyRepository.findById(IncidentSeverity.HIGH)).thenReturn(Optional.of(highPolicy));

        IncidentPolicyException ex = assertThrows(IncidentPolicyException.class,
                () -> service.updateDispatchPolicy(IncidentSeverity.HIGH,
                        new IncidentDispatchPolicyUpdateRequest(1, 30, 5, false, 2L), admin.getEmail()));

        assertEquals(IncidentPolicyErrorCode.ERR_INCPOL_004, ex.getErrorCode());
        verify(dispatchPolicyRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Optimistic lock lúc flush -> ERR_INCPOL_004 của module, không phải ERR_AC_003; không audit")
    void optimisticLockOnFlush_mappedToModuleConflict() {
        when(dispatchPolicyRepository.findById(IncidentSeverity.HIGH)).thenReturn(Optional.of(highPolicy));
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(dispatchPolicyRepository.saveAndFlush(any()))
                .thenThrow(new ObjectOptimisticLockingFailureException(IncidentDispatchPolicy.class, IncidentSeverity.HIGH));

        IncidentPolicyException ex = assertThrows(IncidentPolicyException.class,
                () -> service.updateDispatchPolicy(IncidentSeverity.HIGH, policyReq(1, 30, 5), admin.getEmail()));

        assertEquals(IncidentPolicyErrorCode.ERR_INCPOL_004, ex.getErrorCode());
        verifyNoInteractions(auditService);
    }

    // ------------------------------------------------------------------ cập nhật + audit

    @Test
    @DisplayName("Sửa chính sách hợp lệ -> lưu giá trị mới, audit INCIDENT_DISPATCH_POLICY / UPDATE với snapshot cũ và mới")
    void updatePolicy_savesAndAudits() {
        when(dispatchPolicyRepository.findById(IncidentSeverity.HIGH)).thenReturn(Optional.of(highPolicy));
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(dispatchPolicyRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        IncidentDispatchPolicyResponse res = service.updateDispatchPolicy(IncidentSeverity.HIGH,
                new IncidentDispatchPolicyUpdateRequest(1, 45, 10, true, 3L), admin.getEmail());

        assertEquals(1, res.step1Size());
        assertEquals(45, res.step1TimeoutSeconds());
        assertEquals(10, res.escalateFmMinutes());
        assertEquals(true, res.notifyFmImmediately());

        ArgumentCaptor<IncidentDispatchPolicyAuditSnapshot> oldSnap = ArgumentCaptor.forClass(IncidentDispatchPolicyAuditSnapshot.class);
        ArgumentCaptor<IncidentDispatchPolicyAuditSnapshot> newSnap = ArgumentCaptor.forClass(IncidentDispatchPolicyAuditSnapshot.class);
        verify(auditService).record(eq(AuditTargetType.INCIDENT_DISPATCH_POLICY), eq(AuditAction.UPDATE), eq("HIGH"),
                isNull(), isNull(), oldSnap.capture(), newSnap.capture(), any(String.class), eq(admin));
        assertEquals(2, oldSnap.getValue().step1Size());
        assertEquals(1, newSnap.getValue().step1Size());
    }

    @Test
    @DisplayName("Không đổi gì -> không ghi, không audit")
    void updatePolicy_unchanged_skips() {
        when(dispatchPolicyRepository.findById(IncidentSeverity.HIGH)).thenReturn(Optional.of(highPolicy));

        service.updateDispatchPolicy(IncidentSeverity.HIGH, policyReq(2, 30, 5), admin.getEmail());

        verify(dispatchPolicyRepository, never()).saveAndFlush(any());
        verifyNoInteractions(auditService);
    }

    @Test
    @DisplayName("Luật severity không tồn tại -> 404 ERR_INCPOL_001")
    void severityRule_notFound() {
        UUID id = UUID.randomUUID();
        when(severityRuleRepository.findById(id)).thenReturn(Optional.empty());

        IncidentPolicyException ex = assertThrows(IncidentPolicyException.class,
                () -> service.updateSeverityRule(id, new IncidentSeverityRuleUpdateRequest(IncidentSeverity.HIGH, 0L), admin.getEmail()));

        assertEquals(IncidentPolicyErrorCode.ERR_INCPOL_001, ex.getErrorCode());
    }

    @Test
    @DisplayName("Sửa luật severity -> audit INCIDENT_SEVERITY_RULE / UPDATE")
    void severityRule_updateAudits() {
        UUID id = UUID.randomUUID();
        IncidentSeverityRule rule = IncidentSeverityRule.builder()
                .id(id).eventType("UNKNOWN_PERSON").areaLevelCode(AreaLevel.CONFIDENTIAL)
                .timeWindow(IncidentTimeWindow.IN_HOURS).severity(IncidentSeverity.HIGH).version(0L).build();
        when(severityRuleRepository.findById(id)).thenReturn(Optional.of(rule));
        when(userRepository.findByEmail(admin.getEmail())).thenReturn(Optional.of(admin));
        when(severityRuleRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.updateSeverityRule(id, new IncidentSeverityRuleUpdateRequest(IncidentSeverity.CRITICAL, 0L), admin.getEmail());

        assertEquals(IncidentSeverity.CRITICAL, rule.getSeverity());
        verify(auditService).record(eq(AuditTargetType.INCIDENT_SEVERITY_RULE), eq(AuditAction.UPDATE), eq(id.toString()),
                isNull(), isNull(), any(), any(), any(String.class), eq(admin));
    }

    // ------------------------------------------------------------------ vị trí trực

    @Test
    @DisplayName("Không được ngừng sử dụng vị trí mặc định PATROL -> ERR_INCPOL_008")
    void dutyPost_cannotDeactivateDefault() {
        DutyPostType patrol = DutyPostType.builder().code(DutyPostType.DEFAULT_CODE).label("Tuần tra").version(0L).build();
        when(dutyPostTypeRepository.findById(DutyPostType.DEFAULT_CODE)).thenReturn(Optional.of(patrol));

        IncidentPolicyException ex = assertThrows(IncidentPolicyException.class,
                () -> service.updateDutyPostType(DutyPostType.DEFAULT_CODE,
                        new DutyPostTypeUpdateRequest("Tuần tra", true, false, 1, 0L), admin.getEmail()));

        assertEquals(IncidentPolicyErrorCode.ERR_INCPOL_008, ex.getErrorCode());
        assertEquals("Không thể ngừng sử dụng vị trí trực mặc định PATROL", ex.getMessage());
    }

    @Test
    @DisplayName("Vị trí trực không tồn tại -> 404 ERR_INCPOL_003 nêu mã")
    void dutyPost_notFound() {
        when(dutyPostTypeRepository.findById("NOPE")).thenReturn(Optional.empty());

        IncidentPolicyException ex = assertThrows(IncidentPolicyException.class,
                () -> service.updateDutyPostType("NOPE", new DutyPostTypeUpdateRequest("X", true, true, 1, 0L), admin.getEmail()));

        assertEquals(IncidentPolicyErrorCode.ERR_INCPOL_003, ex.getErrorCode());
        assertEquals("Không tìm thấy vị trí trực NOPE", ex.getMessage());
    }
}
