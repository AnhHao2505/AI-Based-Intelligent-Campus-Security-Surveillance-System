package com.fa26se040.icss.guest;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Khách G-A (P1): bảng lượt khách, khu vực của lượt, khách, embedding khách; CHECK chính; seed audit + config.
 * Chỉ đọc metadata và thử chèn dòng SAI (bị CHECK từ chối nên không để lại dữ liệu).
 */
public class GuestSchemaTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository userRepository;

    private User anyUser() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(User.builder()
                .email("tga.schema." + s + "@fpt.edu.vn")
                .userCode("TGA-SCH-" + s)
                .fullName("Test guest schema " + s)
                .role(Role.NORMAL_USER)
                .accessLevel(2)
                .isActive(true)
                .build());
    }

    private String violation(String sql, Object... args) {
        DataAccessException ex = assertThrows(DataAccessException.class, () -> jdbc.update(sql, args));
        return String.valueOf(ex.getMostSpecificCause().getMessage());
    }

    @Test
    @DisplayName("P1: đủ 4 bảng khách")
    void tablesExist() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public' AND table_name IN "
                        + "('guest_visits','guest_visit_areas','guests','guest_face_embeddings') ORDER BY table_name", String.class);
        assertEquals(List.of("guest_face_embeddings", "guest_visit_areas", "guest_visits", "guests"), tables);
    }

    @Test
    @DisplayName("P1: CHECK trạng thái lượt khách từ chối giá trị lạ")
    void visitStatusCheck() {
        User host = anyUser();
        String msg = violation("INSERT INTO guest_visits (id, host_id, purpose, start_time, end_time, status, version, created_at, updated_at) "
                + "VALUES (?, ?, 'Muc dich hop le E2E', now() + interval '1 hour', now() + interval '2 hour', 'BOGUS', 0, now(), now())",
                UUID.randomUUID(), host.getId());
        assertTrue(msg.contains("chk_guest_visits_status"), msg);
    }

    @Test
    @DisplayName("P1: CHECK khung giờ lượt khách (start < end)")
    void visitWindowCheck() {
        User host = anyUser();
        String msg = violation("INSERT INTO guest_visits (id, host_id, purpose, start_time, end_time, status, version, created_at, updated_at) "
                + "VALUES (?, ?, 'Muc dich hop le E2E', now() + interval '2 hour', now() + interval '1 hour', 'PENDING', 0, now(), now())",
                UUID.randomUUID(), host.getId());
        assertTrue(msg.contains("chk_guest_visits_window"), msg);
    }

    @Test
    @DisplayName("P1: CHECK trạng thái sinh trắc của khách từ chối giá trị lạ")
    void guestBiometricStatusCheck() {
        String msg = violation("INSERT INTO guests (id, visit_id, full_name, biometric_status, created_at, updated_at) "
                + "VALUES (?, ?, 'Nguyen Van A', 'BOGUS', now(), now())", UUID.randomUUID(), UUID.randomUUID());
        assertTrue(msg.contains("chk_guests_biometric_status"), msg);
    }

    @Test
    @DisplayName("P1: khách có ảnh bắt buộc có object key + người xác nhận + thời điểm + phiên bản thông báo")
    void guestPhotoConsentCheck() {
        String msg = violation("INSERT INTO guests (id, visit_id, full_name, biometric_status, created_at, updated_at) "
                + "VALUES (?, ?, 'Nguyen Van A', 'PHOTO_READY', now(), now())", UUID.randomUUID(), UUID.randomUUID());
        assertTrue(msg.contains("chk_guests_photo_consent"), msg);
    }

    @Test
    @DisplayName("P1: embedding khách đúng 512 chiều")
    void embeddingDimension() {
        String msg = violation("INSERT INTO guest_face_embeddings (guest_id, embedding, expires_at, created_at) "
                + "VALUES (?, '[0.1,0.2,0.3]'::vector, now(), now())", UUID.randomUUID());
        assertTrue(msg.contains("512"), msg);
    }

    @Test
    @DisplayName("P1: CHECK thông báo = 16 loại hiện hành + 6 loại khách")
    void notificationTypeCheck() {
        String def = jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_notifications_type'", String.class);
        for (String t : List.of("REQUEST_APPROVED", "REQUEST_REJECTED", "EXPIRING_SOON", "ACCESS_DENIED", "ADDED_TO_GROUP",
                "NEW_REQUEST_PENDING", "REQUEST_CANCELLED", "PENDING_OVERDUE", "EVENT_MODE_LIMIT_CHANGED", "EVENT_MODE_CHANGED",
                "EVENT_MODE_EXPIRING", "EVENT_MODE_SCHEDULED", "EVENT_MODE_SCHEDULE_STARTING", "EVENT_MODE_SCHEDULE_FAILED",
                "AREA_TYPE_CHANGED", "REQUEST_SYSTEM_CANCELLED",
                "GUEST_VISIT_PENDING", "GUEST_VISIT_APPROVED", "GUEST_VISIT_REJECTED", "GUEST_VISIT_REVOKED",
                "GUEST_VISIT_EXPIRED", "GUEST_PHOTO_REQUIRED")) {
            assertTrue(def.contains("'" + t + "'"), "Thiếu loại thông báo " + t + " trong " + def);
        }
    }

    @Test
    @DisplayName("P1: audit_event_types có đủ cặp cho BR-GV-30, module GUEST; chỉ ADMIN đọc module GUEST")
    void auditEventTypes() {
        List<String> pairs = jdbc.queryForList(
                "SELECT target_type || '/' || action FROM audit_event_types WHERE module = 'GUEST' ORDER BY 1", String.class);
        assertEquals(List.of("GUEST/ANONYMIZE", "GUEST/ATTACH_PHOTO", "GUEST/DELETE_BIOMETRIC", "GUEST/VIEW_PHOTO",
                "GUEST_VISIT/APPROVE", "GUEST_VISIT/CANCEL", "GUEST_VISIT/COMPLETE", "GUEST_VISIT/CREATE",
                "GUEST_VISIT/EXPIRE", "GUEST_VISIT/REJECT", "GUEST_VISIT/REVOKE"), pairs);
        List<String> roles = jdbc.queryForList("SELECT role FROM audit_module_roles WHERE module = 'GUEST' ORDER BY 1", String.class);
        assertEquals(List.of("ADMIN"), roles);
    }

    @Test
    @DisplayName("P1: seed 8 config khách + hằng trong ConfigKey khớp mặc định")
    void configsSeeded() {
        Map<String, String> rows = jdbc.queryForList(
                        "SELECT config_key, data_type || ':' || config_value AS v FROM system_configurations WHERE config_group = 'GUEST'")
                .stream().collect(Collectors.toMap(r -> (String) r.get("config_key"), r -> (String) r.get("v")));
        Map<String, String> expected = Map.of(
                "GUEST_HOST_MIN_LEVEL", "INTEGER:2",
                "GUEST_MAX_PER_VISIT", "INTEGER:10",
                "GUEST_VISIT_MAX_HOURS", "INTEGER:8",
                "GUEST_MAX_ADVANCE_DAYS", "INTEGER:14",
                "GUEST_FACE_RETENTION_HOURS", "INTEGER:24",
                "GUEST_RECORD_RETENTION_DAYS", "INTEGER:90",
                "GUEST_PHOTO_URL_TTL_SECONDS", "INTEGER:60",
                "GUEST_CONSENT_NOTICE_VERSION", "STRING:v1");
        assertEquals(expected, rows);
        for (Map.Entry<String, String> e : expected.entrySet()) {
            ConfigKey key = ConfigKey.valueOf(e.getKey());
            assertEquals(e.getValue().substring(e.getValue().indexOf(':') + 1), key.getDefaultValue(), "Mặc định enum " + key);
        }
    }
}
