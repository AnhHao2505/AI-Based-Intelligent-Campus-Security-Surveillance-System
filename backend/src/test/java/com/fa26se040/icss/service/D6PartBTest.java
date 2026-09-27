package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.context.AuditContext;
import com.fa26se040.icss.dto.common.ApiResponse;
import com.fa26se040.icss.dto.accesscontrol.AuditLogResponse;
import com.fa26se040.icss.dto.accesscontrol.snapshot.UserAccessLevelAuditSnapshot;
import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AuditWriteException;
import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.repository.AuditLogRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class D6PartBTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuditService auditService;

    @SpyBean
    private AuditLogRepository auditLogRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private GlobalExceptionHandler globalExceptionHandler;

    @AfterEach
    void tearDown() {
        AuditContext.clear();
    }

    @Test
    @DisplayName("D6-07: Audit ghi lỗi (mock repository ném DataAccessException) -> API trả ERR_AUDIT_001, dữ liệu nghiệp vụ KHÔNG đổi")
    void testD6_07_AuditWriteFailure_ThrowsAuditWriteException_RollsBackBusinessData() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM D6-07 Test")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);

        User targetUser = User.builder()
                .userCode("USR-" + uniqueSuffix)
                .fullName("User D6-07 Test")
                .email("usr-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();
        targetUser = userRepository.save(targetUser);

        final UUID targetId = targetUser.getId();
        final String token = "Bearer " + jwtTokenProvider.generateToken(fmActor);

        // Mock repository ném DataAccessException khi saveAndFlush
        doThrow(new DataIntegrityViolationException("Simulated DB connection failure"))
                .when(auditLogRepository).saveAndFlush(any(AuditLog.class));

        try {
            mockMvc.perform(patch("/api/users/{id}/access-level", targetId)
                            .header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"accessLevel\": 3, \"reason\": \"Nâng cấp quyền cho nhân viên phụ trách\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("ERR_AUDIT_001"))
                    .andExpect(jsonPath("$.message").value("Không ghi được nhật ký hệ thống, thao tác chưa được thực hiện. Vui lòng thử lại."));

            // Dữ liệu nghiệp vụ KHÔNG đổi (access level vẫn là 1)
            User reloaded = userRepository.findById(targetId).orElseThrow();
            assertEquals(1, reloaded.getAccessLevel(), "Rollback thành công: accessLevel của user phải giữ nguyên");
        } finally {
            reset(auditLogRepository);
            userRepository.deleteById(targetId);
            userRepository.deleteById(fmActor.getId());
        }
    }

    @Test
    @DisplayName("D6-08: Hai lần record trong cùng 1 request -> cùng correlationId; hai request -> khác")
    void testD6_08_CorrelationId_SameInRequest_DifferentAcrossRequests() {
        UUID req1CorrelationId = UUID.randomUUID();
        AuditContext.setCorrelationId(req1CorrelationId);

        AuditLog log1 = auditService.record(
                AuditTargetType.AREA_ACCESS_RULES,
                AuditAction.UPDATE,
                "AREA-1",
                (com.fa26se040.icss.entity.Area) null,
                null,
                null,
                null,
                "Lần ghi thứ nhất trong request 1",
                (User) null
        );

        AuditLog log2 = auditService.record(
                AuditTargetType.AREA_ACCESS_RULES,
                AuditAction.UPDATE,
                "AREA-2",
                (com.fa26se040.icss.entity.Area) null,
                null,
                null,
                null,
                "Lần ghi thứ hai trong request 1",
                (User) null
        );

        assertNotNull(log1.getCorrelationId());
        assertNotNull(log2.getCorrelationId());
        assertEquals(req1CorrelationId, log1.getCorrelationId());
        assertEquals(req1CorrelationId, log2.getCorrelationId());
        assertEquals(log1.getCorrelationId(), log2.getCorrelationId(), "Hai lần record trong cùng 1 request phải chung correlationId");

        // Bắt đầu request 2
        AuditContext.clear();
        UUID req2CorrelationId = UUID.randomUUID();
        AuditContext.setCorrelationId(req2CorrelationId);

        AuditLog log3 = auditService.record(
                AuditTargetType.AREA_ACCESS_RULES,
                AuditAction.UPDATE,
                "AREA-3",
                (com.fa26se040.icss.entity.Area) null,
                null,
                null,
                null,
                "Lần ghi trong request 2",
                (User) null
        );

        assertEquals(req2CorrelationId, log3.getCorrelationId());
        assertNotEquals(log1.getCorrelationId(), log3.getCorrelationId(), "Hai request khác nhau phải có correlationId khác nhau");
    }

    @Test
    @DisplayName("D6-09: FM lọc module SYSTEM -> 403; FM không lọc -> không thấy dòng REASON_CATALOG; ADMIN thấy tất cả")
    void testD6_09_ModuleRoleFilter_FacilityManagerAndAdmin() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);

        User fm = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM D6-09 Test")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fm = userRepository.save(fm);

        User admin = User.builder()
                .userCode("ADM-" + uniqueSuffix)
                .fullName("Admin D6-09 Test")
                .email("adm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.ADMIN)
                .isActive(true)
                .build();
        admin = userRepository.save(admin);

        String fmToken = "Bearer " + jwtTokenProvider.generateToken(fm);
        String adminToken = "Bearer " + jwtTokenProvider.generateToken(admin);

        // Tạo 1 log thuộc module SYSTEM (target_type REASON_CATALOG)
        UUID sysCorrId = UUID.randomUUID();
        AuditContext.setCorrelationId(sysCorrId);
        AuditLog sysLog = auditService.record(
                AuditTargetType.REASON_CATALOG,
                AuditAction.CREATE,
                "REASON-TEST-" + uniqueSuffix,
                (com.fa26se040.icss.entity.Area) null,
                null,
                null,
                null,
                "Tạo lý do mới cho hệ thống",
                admin
        );

        // Tạo 1 log thuộc module ACCESS_CONTROL (target_type USER_ACCESS_LEVEL)
        UUID acCorrId = UUID.randomUUID();
        AuditContext.setCorrelationId(acCorrId);
        AuditLog acLog = auditService.record(
                AuditTargetType.USER_ACCESS_LEVEL,
                AuditAction.UPDATE,
                fm.getId().toString(),
                (com.fa26se040.icss.entity.Area) null,
                fm,
                null,
                new UserAccessLevelAuditSnapshot(2),
                "Cập nhật quyền cho FM test",
                admin
        );
        AuditContext.clear();

        // 1. FM lọc module SYSTEM -> 403 Forbidden
        mockMvc.perform(get("/api/access-control/audit-logs")
                        .header("Authorization", fmToken)
                        .param("module", "SYSTEM"))
                .andExpect(status().isForbidden());

        // 2. FM không lọc module -> 200 OK, thấy dòng ACCESS_CONTROL, KHÔNG thấy dòng REASON_CATALOG
        Page<AuditLogResponse> fmResult = auditService.getAuditLogs(
                null, null, null, null, null, null, null, null,
                PageRequest.of(0, 100), Role.FACILITY_MANAGER.name()
        );
        boolean fmSeesSysLog = fmResult.getContent().stream()
                .anyMatch(l -> sysLog.getId().equals(l.id()));
        boolean fmSeesAcLog = fmResult.getContent().stream()
                .anyMatch(l -> acLog.getId().equals(l.id()));
        assertFalse(fmSeesSysLog, "FM không được nhìn thấy bản ghi thuộc module SYSTEM khi không lọc");
        assertTrue(fmSeesAcLog, "FM phải nhìn thấy bản ghi thuộc module ACCESS_CONTROL");

        // 3. ADMIN thấy tất cả
        Page<AuditLogResponse> adminResult = auditService.getAuditLogs(
                null, null, null, null, null, null, null, null,
                PageRequest.of(0, 100), Role.ADMIN.name()
        );
        boolean adminSeesSysLog = adminResult.getContent().stream()
                .anyMatch(l -> sysLog.getId().equals(l.id()));
        boolean adminSeesAcLog = adminResult.getContent().stream()
                .anyMatch(l -> acLog.getId().equals(l.id()));
        assertTrue(adminSeesSysLog, "ADMIN phải nhìn thấy bản ghi thuộc module SYSTEM");
        assertTrue(adminSeesAcLog, "ADMIN phải nhìn thấy bản ghi thuộc module ACCESS_CONTROL");
    }

    @Test
    @DisplayName("D6-10: Exception bất kỳ -> response không chứa message gốc, có 'Mã tra cứu'")
    void testD6_10_UnhandledException_ReturnsMaskedMessageWithLookupCode() {
        String sensitiveInternalMessage = "FATAL: password authentication failed for user secret_db_user";
        Exception ex = new RuntimeException(sensitiveInternalMessage);

        ResponseEntity<ApiResponse<Object>> response = globalExceptionHandler.handleGeneral(ex);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertNotNull(response.getBody());
        String returnedMessage = response.getBody().getMessage();

        // KHÔNG trả ex.getMessage()
        assertFalse(returnedMessage.contains(sensitiveInternalMessage),
                "Response không được chứa thông điệp lỗi nhạy cảm ban đầu");

        // Có "Lỗi hệ thống. Mã tra cứu: {errorId}"
        assertTrue(returnedMessage.startsWith("Lỗi hệ thống. Mã tra cứu: "),
                "Response phải bắt đầu bằng 'Lỗi hệ thống. Mã tra cứu: '");
        String errorId = returnedMessage.substring("Lỗi hệ thống. Mã tra cứu: ".length()).trim();
        assertFalse(errorId.isEmpty(), "Mã tra cứu không được rỗng");
    }
}
