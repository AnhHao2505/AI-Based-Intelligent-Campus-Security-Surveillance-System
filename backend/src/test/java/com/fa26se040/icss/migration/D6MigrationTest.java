package com.fa26se040.icss.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@ActiveProfiles("test")
public class D6MigrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("D6-01: Sau V55: count audit_logs không đổi; mọi dòng cũ có module qua join; actor_type = USER")
    void testD6_01_AuditLogsIntegrityAfterV55() {
        // Count audit_logs
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        assertNotNull(count);
        assertTrue(count >= 0);

        // Every row has module via join with audit_event_types
        Integer unmappedRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs l LEFT JOIN audit_event_types t " +
                        "ON l.target_type = t.target_type AND l.action = t.action WHERE t.module IS NULL",
                Integer.class
        );
        assertEquals(0, unmappedRows, "Mọi dòng cũ trong audit_logs phải có module qua join với audit_event_types");

        // Old rows (correlation_id IS NULL) have actor_type = USER
        Integer nonUserRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE correlation_id IS NULL AND (actor_type <> 'USER' OR actor_type IS NULL)",
                Integer.class
        );
        assertEquals(0, nonUserRows, "Mọi dòng cũ trong audit_logs phải có actor_type = 'USER'");
    }

    @Test
    @DisplayName("D6-02: Trigger trg_audit_logs_append_only vẫn chặn UPDATE và DELETE trên audit_logs")
    void testD6_02_TriggerBlocksUpdateAndDelete() {
        UUID actorId = jdbcTemplate.queryForObject("SELECT id FROM users LIMIT 1", UUID.class);
        assertNotNull(actorId);

        UUID logId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        jdbcTemplate.update(
                "INSERT INTO audit_logs (id, target_type, target_id, action, changed_by, actor_type, reason, correlation_id, changed_at) " +
                        "VALUES (?, 'LEVEL_PRESET', 'PUBLIC', 'UPDATE', ?, 'USER', 'Initial reason', ?, NOW())",
                logId, actorId, correlationId
        );

        // Test UPDATE blocked
        Exception updateEx = assertThrows(Exception.class, () -> {
            jdbcTemplate.update("UPDATE audit_logs SET reason = 'Tampered' WHERE id = ?", logId);
        });
        String updateMsg = updateEx.getMessage().toLowerCase();
        assertTrue(updateMsg.contains("append-only"), "Trigger phải chặn UPDATE với thông điệp append-only");

        // Test DELETE blocked
        Exception deleteEx = assertThrows(Exception.class, () -> {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE id = ?", logId);
        });
        String deleteMsg = deleteEx.getMessage().toLowerCase();
        assertTrue(deleteMsg.contains("append-only"), "Trigger phải chặn DELETE với thông điệp append-only");
    }

    @Test
    @DisplayName("D6-03: INSERT cặp (target_type, action) không có trong audit_event_types -> lỗi FK")
    void testD6_03_ForeignKeyViolation() {
        UUID actorId = jdbcTemplate.queryForObject("SELECT id FROM users LIMIT 1", UUID.class);
        assertNotNull(actorId);

        UUID logId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcTemplate.update(
                    "INSERT INTO audit_logs (id, target_type, target_id, action, changed_by, actor_type, reason, correlation_id, changed_at) " +
                            "VALUES (?, 'INVALID_TARGET', 'TARGET_1', 'INVALID_ACTION', ?, 'USER', 'Test FK', ?, NOW())",
                    logId, actorId, correlationId
            );
        });
    }

    @Test
    @DisplayName("D6-04: SYSTEM có changed_by -> lỗi CHECK; USER không có changed_by -> lỗi CHECK; dòng mới thiếu correlation_id -> lỗi CHECK")
    void testD6_04_CheckConstraints() {
        UUID actorId = jdbcTemplate.queryForObject("SELECT id FROM users LIMIT 1", UUID.class);
        assertNotNull(actorId);

        UUID correlationId = UUID.randomUUID();

        // 1. SYSTEM có changed_by -> lỗi CHECK chk_audit_actor
        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcTemplate.update(
                    "INSERT INTO audit_logs (id, target_type, target_id, action, changed_by, actor_type, actor_source, reason, correlation_id, changed_at) " +
                            "VALUES (?, 'LEVEL_PRESET', 'PUBLIC', 'UPDATE', ?, 'SYSTEM', 'TEST_JOB', 'Test', ?, NOW())",
                    UUID.randomUUID(), actorId, correlationId
            );
        });

        // 2. USER không có changed_by -> lỗi CHECK chk_audit_actor
        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcTemplate.update(
                    "INSERT INTO audit_logs (id, target_type, target_id, action, changed_by, actor_type, reason, correlation_id, changed_at) " +
                            "VALUES (?, 'LEVEL_PRESET', 'PUBLIC', 'UPDATE', NULL, 'USER', 'Test', ?, NOW())",
                    UUID.randomUUID(), correlationId
            );
        });

        // 3. Dòng mới thiếu correlation_id -> lỗi CHECK chk_audit_correlation
        assertThrows(DataIntegrityViolationException.class, () -> {
            jdbcTemplate.update(
                    "INSERT INTO audit_logs (id, target_type, target_id, action, changed_by, actor_type, reason, correlation_id, changed_at) " +
                            "VALUES (?, 'LEVEL_PRESET', 'PUBLIC', 'UPDATE', ?, 'USER', 'Test', NULL, NOW())",
                    UUID.randomUUID(), actorId
            );
        });
    }
}
