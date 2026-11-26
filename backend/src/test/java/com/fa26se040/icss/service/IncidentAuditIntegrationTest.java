package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accesscontrol.AuditLogResponse;
import com.fa26se040.icss.dto.accesscontrol.snapshot.SecurityIncidentAuditSnapshot;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.IncidentDispatchStage;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class IncidentAuditIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditService auditService;

    @Test
    @Transactional
    @DisplayName("V149: audit_event_types & audit_module_roles cho INCIDENT đầy đủ, ghi và đọc được cho ADMIN và FACILITY_MANAGER")
    void v149IncidentAuditEventsAndRolesWork() {
        Integer roleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_module_roles WHERE module = 'INCIDENT' AND role IN ('ADMIN', 'FACILITY_MANAGER')",
                Integer.class
        );
        assertEquals(2, roleCount);

        UUID incidentId = UUID.randomUUID();
        SecurityIncidentAuditSnapshot oldSnap = new SecurityIncidentAuditSnapshot(
                incidentId, "CAM-005", "UNAUTHORIZED_PERSON", IncidentSeverity.HIGH,
                IncidentStatus.CLAIMED, IncidentDispatchStage.BROADCAST, null,
                UUID.randomUUID(), "Bảo vệ Test", OffsetDateTime.now().minusMinutes(5),
                null, null, null, null, null, null
        );
        SecurityIncidentAuditSnapshot newSnap = new SecurityIncidentAuditSnapshot(
                incidentId, "CAM-005", "UNAUTHORIZED_PERSON", IncidentSeverity.HIGH,
                IncidentStatus.RESOLVED_VERIFIED, IncidentDispatchStage.BROADCAST, null,
                oldSnap.claimedById(), "Bảo vệ Test", oldSnap.claimedAt(),
                oldSnap.claimedById(), "Bảo vệ Test", OffsetDateTime.now(),
                IncidentOutcome.VERIFIED, IncidentResolutionCategory.REMINDED_DISPERSED, "Đã xử lý"
        );

        AuditLog recorded = auditService.record(
                AuditTargetType.SECURITY_INCIDENT,
                AuditAction.RESOLVE,
                incidentId.toString(),
                null,
                null,
                oldSnap,
                newSnap,
                "Đã xử lý",
                AuditActor.system("TEST_RUNNER")
        );
        assertNotNull(recorded.getId());

        Page<AuditLogResponse> adminLogs = auditService.getAuditLogs(
                "INCIDENT", null, AuditTargetType.SECURITY_INCIDENT, null, null, null, null, null, PageRequest.of(0, 10), "ADMIN");
        assertTrue(adminLogs.getContent().stream().anyMatch(l -> incidentId.toString().equals(l.targetId())));

        Page<AuditLogResponse> fmLogs = auditService.getAuditLogs(
                "INCIDENT", null, AuditTargetType.SECURITY_INCIDENT, null, null, null, null, null, PageRequest.of(0, 10), "FACILITY_MANAGER");
        assertTrue(fmLogs.getContent().stream().anyMatch(l -> incidentId.toString().equals(l.targetId())));
    }
}
