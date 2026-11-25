package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accesscontrol.AuditLogResponse;
import com.fa26se040.icss.dto.accesscontrol.snapshot.GuardTeamAuditSnapshot;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.ConfigKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GuardAuditAndConfigIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SystemConfigService systemConfigService;

    @Autowired
    private AuditService auditService;

    @Test
    @DisplayName("V143: 2 khóa khung nhận/kết thúc ca trực tồn tại trong system_configurations và đọc được qua SystemConfigService")
    void v143ConfigsExistAndReadable() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_configurations WHERE config_key IN (?, ?)",
                Integer.class,
                ConfigKey.GUARD_SHIFT_CHECK_IN_WINDOW_MINUTES.getKey(),
                ConfigKey.GUARD_SHIFT_CHECK_OUT_WINDOW_MINUTES.getKey()
        );
        assertEquals(2, count);
        assertTrue(systemConfigService.getIntWithinBounds(ConfigKey.GUARD_SHIFT_CHECK_IN_WINDOW_MINUTES) >= 1);
        assertTrue(systemConfigService.getIntWithinBounds(ConfigKey.GUARD_SHIFT_CHECK_OUT_WINDOW_MINUTES) >= 1);
    }

    @Test
    @Transactional
    @DisplayName("V144: audit_event_types & audit_module_roles cho GUARD đầy đủ, ghi và đọc được cho ADMIN và FACILITY_MANAGER")
    void v144GuardAuditEventsAndRolesWork() {
        Integer roleCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_module_roles WHERE module = 'GUARD' AND role IN ('ADMIN', 'FACILITY_MANAGER')",
                Integer.class
        );
        assertEquals(2, roleCount);

        UUID teamId = UUID.randomUUID();
        AuditLog recorded = auditService.record(
                AuditTargetType.GUARD_TEAM,
                AuditAction.CREATE,
                teamId.toString(),
                null,
                null,
                null,
                new GuardTeamAuditSnapshot(teamId, "Đội Integration Test", null, null, null, true, 0, List.of(), 3, 4, 2, 2, 2, 2, true),
                "Kiểm thử ghi audit GUARD_TEAM",
                AuditActor.system("GUARD_TEAM_SERVICE")
        );
        assertNotNull(recorded.getId());

        Page<AuditLogResponse> adminLogs = auditService.getAuditLogs(
                "GUARD", null, AuditTargetType.GUARD_TEAM, null, null, null, null, null, PageRequest.of(0, 10), "ADMIN");
        assertTrue(adminLogs.getContent().stream().anyMatch(l -> teamId.toString().equals(l.targetId())));

        Page<AuditLogResponse> fmLogs = auditService.getAuditLogs(
                "GUARD", null, AuditTargetType.GUARD_TEAM, null, null, null, null, null, PageRequest.of(0, 10), "FACILITY_MANAGER");
        assertTrue(fmLogs.getContent().stream().anyMatch(l -> teamId.toString().equals(l.targetId())));
    }
}
