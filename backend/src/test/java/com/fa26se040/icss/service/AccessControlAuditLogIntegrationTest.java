package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaAccessRulesUpdateRequest;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
class AccessControlAuditLogIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private AreaService areaService;

    @SpyBean
    private AccessControlAuditService auditService;

    @Test
    @Transactional
    @DisplayName("E.3: Trigger trg_access_control_audit_logs_append_only chặn UPDATE trên bảng audit log")
    void testAppendOnlyTrigger_BlocksUpdate() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User actor = User.builder()
                .userCode("ACT-" + uniqueSuffix)
                .fullName("Actor Trigger Test")
                .email("act-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        actor = userRepository.save(actor);

        UUID logId = UUID.randomUUID();
        entityManager.createNativeQuery(
                "INSERT INTO access_control_audit_logs (id, target_type, target_id, action, changed_by, reason, changed_at) " +
                        "VALUES (:id, 'LEVEL_PRESET', 'PUBLIC', 'UPDATE', :changedBy, 'Initial reason', NOW())"
        ).setParameter("id", logId).setParameter("changedBy", actor.getId()).executeUpdate();
        entityManager.flush();

        PersistenceException ex = assertThrows(PersistenceException.class, () -> {
            entityManager.createNativeQuery(
                    "UPDATE access_control_audit_logs SET reason = 'Tampered reason' WHERE id = :id"
            ).setParameter("id", logId).executeUpdate();
            entityManager.flush();
        });

        assertTrue(ex.getMessage().contains("append-only") || ex.getCause().getMessage().contains("append-only"),
                "Thông báo lỗi phải chứa thông điệp từ trigger chặn UPDATE");
    }

    @Test
    @Transactional
    @DisplayName("E.3: Trigger trg_access_control_audit_logs_append_only chặn DELETE trên bảng audit log")
    void testAppendOnlyTrigger_BlocksDelete() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User actor = User.builder()
                .userCode("ACT-" + uniqueSuffix)
                .fullName("Actor Trigger Test")
                .email("act-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        actor = userRepository.save(actor);

        UUID logId = UUID.randomUUID();
        entityManager.createNativeQuery(
                "INSERT INTO access_control_audit_logs (id, target_type, target_id, action, changed_by, reason, changed_at) " +
                        "VALUES (:id, 'LEVEL_PRESET', 'PUBLIC', 'UPDATE', :changedBy, 'Initial reason', NOW())"
        ).setParameter("id", logId).setParameter("changedBy", actor.getId()).executeUpdate();
        entityManager.flush();

        PersistenceException ex = assertThrows(PersistenceException.class, () -> {
            entityManager.createNativeQuery(
                    "DELETE FROM access_control_audit_logs WHERE id = :id"
            ).setParameter("id", logId).executeUpdate();
            entityManager.flush();
        });

        assertTrue(ex.getMessage().contains("append-only") || ex.getCause().getMessage().contains("append-only"),
                "Thông báo lỗi phải chứa thông điệp từ trigger chặn DELETE");
    }

    @Test
    @DisplayName("E.2: Rollback khi ghi log lỗi — lỗi trong quá trình ghi log khiến toàn bộ transaction rollback")
    void testTransactionRollback_WhenAuditRecordingFails_RollsBackBusinessChange() {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        User fmActor = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM Integration Test")
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();
        fmActor = userRepository.save(fmActor);

        User targetUser = User.builder()
                .userCode("USR-" + uniqueSuffix)
                .fullName("User Integration Test")
                .email("usr-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();
        targetUser = userRepository.save(targetUser);

        final UUID targetId = targetUser.getId();
        final String fmEmail = fmActor.getEmail();

        try {
            // Giả lập lỗi khi ghi audit log
            doThrow(new RuntimeException("Simulated audit log write failure"))
                    .when(auditService).record(any(), any(), any(), any(), any(), any(), any(), any(), any());

            assertThrows(RuntimeException.class, () -> {
                userService.updateAccessLevel(targetId, 3, "Lý do cập nhật", fmEmail);
            });

            // Sau khi rollback, access level của targetUser trong DB vẫn phải là 1 (không bị sửa thành 3)
            User refreshed = userRepository.findById(targetId).orElseThrow();
            assertEquals(1, refreshed.getAccessLevel(),
                    "Transaction phải rollback toàn bộ, cấp độ của user phải giữ nguyên là 1");

        } finally {
            // Dọn dẹp: vì transaction rollback nên không có dòng audit nào tham chiếu đến fmActor hoặc targetUser
            // Xoá an toàn không vướng FK RESTRICT
            userRepository.deleteById(targetId);
            userRepository.deleteById(fmActor.getId());
        }
    }

    @Test
    @Transactional
    @DisplayName("E.8: Tính toán động cờ differsFromPreset (true khi khác preset, false khi khớp preset)")
    void testDiffersFromPreset_DynamicCalculation() {
        String uniqueCode = "AREA-" + UUID.randomUUID().toString().substring(0, 8);
        Area area = Area.builder()
                .code(uniqueCode)
                .name("Khu vực test differsFromPreset")
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .isActive(true)
                .build();
        area = areaRepository.save(area);

        // Ban đầu khớp với preset của PUBLIC (1, false) -> differsFromPreset = false
        AreaResponse resp1 = areaService.getAreaById(area.getId());
        assertFalse(resp1.differsFromPreset(), "Khớp preset thì differsFromPreset phải là false");

        // Thay đổi quy tắc khác preset (ví dụ đặt level = 2) -> differsFromPreset = true
        area.setAreaAccessLevel(2);
        areaRepository.save(area);

        AreaResponse resp2 = areaService.getAreaById(area.getId());
        assertTrue(resp2.differsFromPreset(), "Khác preset thì differsFromPreset phải là true");
    }
}
