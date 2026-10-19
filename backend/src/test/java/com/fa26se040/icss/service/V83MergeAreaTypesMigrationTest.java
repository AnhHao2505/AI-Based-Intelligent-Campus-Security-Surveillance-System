package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * V83 (gộp loại khu vực 4 → 3). DB test đã chạy V83 khi dựng schema nên không còn dữ liệu kiểu cũ; test dựng lại dữ liệu
 * kiểu cũ (4 loại, cấp lệch, cờ explicit, preset 4 dòng, cấu hình cũ) trong MỘT giao dịch, chạy lại đúng file V83,
 * kiểm kết quả, chạy lần 2 để kiểm chạy lại không lỗi, rồi ROLLBACK (không để lại gì trong DB test).
 */
class V83MergeAreaTypesMigrationTest extends AbstractIntegrationTest {

    private static final String V83_FILE = "db/migration/V83__merge_area_types.sql";
    private static final String SOURCE = "FLYWAY_MIGRATION_V83";
    private static final List<String> NEW_CONSTRAINTS = List.of("chk_areas_area_level", "chk_areas_level_matches_type",
            "chk_areas_explicit_unused", "chk_preset_area_level", "chk_preset_level_matches_type", "chk_preset_explicit_unused");
    private static final List<String> CHANGED_CONFIGS = List.of("ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES",
            "ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER", "ACCESS_LEVEL_DEFAULT_ADMIN");

    @Autowired private JdbcTemplate jdbc;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private UserService userService;
    @Autowired private SystemConfigService systemConfigService;

    private static String v83Script() throws Exception {
        return new String(new ClassPathResource(V83_FILE).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private Map<String, Object> areaRow(UUID id) {
        return jdbc.queryForMap("SELECT area_level, area_access_level, explicit_authorization_required, version FROM areas WHERE id = ?", id);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private Map<String, Integer> v83AuditsByArea(List<UUID> ids) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (UUID id : ids) {
            for (Map<String, Object> r : jdbc.queryForList(
                    "SELECT target_type, action, count(*) AS n FROM audit_logs WHERE actor_source = ? AND area_id = ? GROUP BY 1, 2",
                    SOURCE, id)) {
                counts.put(id + "/" + r.get("target_type") + "/" + r.get("action"), ((Number) r.get("n")).intValue());
            }
        }
        return counts;
    }

    private int presetAudits() {
        return count("SELECT count(*) FROM audit_logs WHERE actor_source = ? AND target_type = 'LEVEL_PRESET'", SOURCE);
    }

    private int configChangeLogs() {
        return count("SELECT count(*) FROM system_configuration_change_logs WHERE reason LIKE '%(V83, 19/10/2026)' AND config_key IN (?, ?, ?)",
                CHANGED_CONFIGS.toArray());
    }

    @Test
    @DisplayName("V83: Nội bộ / Liên hệ trước -> Bảo mật cấp 2, cờ false; cấp lệch về cấp của loại; preset đúng 3 dòng; cấu hình đổi kèm nhật ký; users không đổi; chạy lại không lỗi")
    void v83_transformsOldData_andIsRerunnable() throws Exception {
        String script = v83Script();
        String statements = script.replaceAll("(?m)--.*$", "");
        assertFalse(Pattern.compile("update\\s+users", Pattern.CASE_INSENSITIVE).matcher(statements).find(),
                "V83 không được đổi cấp tài khoản đang có");

        transactionTemplate.executeWithoutResult(tx -> {
            try {
                String suffix = UUID.randomUUID().toString().substring(0, 8);
                Building building = buildingRepository.saveAndFlush(Building.builder().name("Tòa V83 " + suffix).isActive(true).build());
                Floor floor = floorRepository.saveAndFlush(Floor.builder().name("V83 " + suffix).floorOrder(1).building(building).isActive(true).build());
                UUID internal = saveArea("internal", floor, AreaLevel.CONFIDENTIAL);
                UUID contact = saveArea("contact", floor, AreaLevel.CONFIDENTIAL);
                UUID highly = saveArea("highly", floor, AreaLevel.HIGHLY_CONFIDENTIAL);
                UUID publicArea = saveArea("public", floor, AreaLevel.PUBLIC);
                List<UUID> ids = List.of(internal, contact, highly, publicArea);

                // Dữ liệu kiểu trước V83 (bỏ CHECK mới để ghi được)
                for (String c : List.of("chk_areas_area_level", "chk_areas_level_matches_type", "chk_areas_explicit_unused")) {
                    jdbc.execute("ALTER TABLE areas DROP CONSTRAINT IF EXISTS " + c);
                }
                for (String c : List.of("chk_preset_area_level", "chk_preset_level_matches_type", "chk_preset_explicit_unused")) {
                    jdbc.execute("ALTER TABLE area_level_presets DROP CONSTRAINT IF EXISTS " + c);
                }
                jdbc.update("UPDATE areas SET area_level = 'INTERNAL_CONFIDENTIAL', area_access_level = 2, explicit_authorization_required = FALSE WHERE id = ?", internal);
                jdbc.update("UPDATE areas SET area_level = 'CONFIDENTIAL_CONTACT_REQUIRED', area_access_level = 2, explicit_authorization_required = TRUE WHERE id = ?", contact);
                jdbc.update("UPDATE areas SET area_access_level = 2, explicit_authorization_required = TRUE WHERE id = ?", highly);
                jdbc.update("UPDATE areas SET area_access_level = 3 WHERE id = ?", publicArea);
                jdbc.update("UPDATE area_level_presets SET area_level = 'INTERNAL_CONFIDENTIAL' WHERE area_level = 'CONFIDENTIAL'");
                jdbc.update("INSERT INTO area_level_presets (area_level, area_access_level, explicit_authorization_required, updated_at) "
                        + "VALUES ('CONFIDENTIAL_CONTACT_REQUIRED', 2, TRUE, CURRENT_TIMESTAMP)");
                jdbc.update("UPDATE area_level_presets SET explicit_authorization_required = TRUE WHERE area_level = 'HIGHLY_CONFIDENTIAL'");
                jdbc.update("UPDATE system_configurations SET config_value = 'CONFIDENTIAL_CONTACT_REQUIRED' WHERE config_key = 'ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES'");
                jdbc.update("UPDATE system_configurations SET config_value = '2' WHERE config_key = 'ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER'");
                jdbc.update("UPDATE system_configurations SET config_value = '1' WHERE config_key = 'ACCESS_LEVEL_DEFAULT_ADMIN'");
                jdbc.update("DELETE FROM system_configurations WHERE config_key = 'ACCESS_REQUEST_MAX_LEVEL_GAP'");

                Map<UUID, Long> versionsBefore = new LinkedHashMap<>();
                ids.forEach(id -> versionsBefore.put(id, ((Number) areaRow(id).get("version")).longValue()));
                List<String> usersBefore = jdbc.queryForList("SELECT id::text || ':' || access_level FROM users ORDER BY id", String.class);
                int presetAuditsBefore = presetAudits();
                int changeLogsBefore = configChangeLogs();

                // Lần chạy 1
                jdbc.execute(script);

                assertArea(areaRow(internal), "CONFIDENTIAL", 2);
                assertArea(areaRow(contact), "CONFIDENTIAL", 2);
                assertArea(areaRow(highly), "HIGHLY_CONFIDENTIAL", 3);
                assertArea(areaRow(publicArea), "PUBLIC", 1);
                for (UUID id : ids) {
                    assertEquals(versionsBefore.get(id) + 1, ((Number) areaRow(id).get("version")).longValue(), "mỗi khu đổi thật tăng đúng 1 version");
                }
                Map<String, Integer> audits = v83AuditsByArea(ids);
                assertEquals(Map.of(
                        internal + "/AREA/CHANGE_TYPE", 1,
                        contact + "/AREA/CHANGE_TYPE", 1,
                        highly + "/AREA_ACCESS_RULES/UPDATE", 1,
                        publicArea + "/AREA_ACCESS_RULES/UPDATE", 1), audits);
                assertEquals("CONFIDENTIAL_CONTACT_REQUIRED", jdbc.queryForObject(
                        "SELECT old_value ->> 'areaLevel' FROM audit_logs WHERE actor_source = ? AND area_id = ? AND action = 'CHANGE_TYPE'",
                        String.class, SOURCE, contact), "audit giữ tên loại cũ");
                assertEquals(presetAuditsBefore + 3, presetAudits(), "audit 3 dòng preset đổi (Nội bộ, Liên hệ trước, Tuyệt mật)");

                assertEquals(List.of("PUBLIC:1:false", "CONFIDENTIAL:2:false", "HIGHLY_CONFIDENTIAL:3:false"), jdbc.queryForList(
                        "SELECT area_level || ':' || area_access_level || ':' || explicit_authorization_required FROM area_level_presets ORDER BY area_access_level",
                        String.class));
                assertEquals(NEW_CONSTRAINTS.size(), count(
                        "SELECT count(*) FROM pg_constraint WHERE connamespace = current_schema()::regnamespace AND conname = ANY (?)",
                        (Object) NEW_CONSTRAINTS.toArray(new String[0])));

                assertEquals("CONFIDENTIAL", config("ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES"));
                assertEquals("3", config("ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER"));
                assertEquals("3", config("ACCESS_LEVEL_DEFAULT_ADMIN"));
                Map<String, Object> gap = jdbc.queryForMap(
                        "SELECT config_value, data_type, min_value, max_value, config_group FROM system_configurations WHERE config_key = 'ACCESS_REQUEST_MAX_LEVEL_GAP'");
                assertEquals("1", gap.get("config_value"));
                assertEquals("INTEGER", gap.get("data_type"));
                assertEquals(1, ((Number) gap.get("min_value")).intValue());
                assertEquals(2, ((Number) gap.get("max_value")).intValue());
                assertEquals("ACCESS_REQUEST", gap.get("config_group"));
                assertEquals(changeLogsBefore + 3, configChangeLogs(), "mỗi cấu hình đổi thật có 1 dòng nhật ký");
                assertEquals("CONFIDENTIAL_CONTACT_REQUIRED", jdbc.queryForObject(
                        "SELECT old_value FROM system_configuration_change_logs WHERE config_key = 'ACCESS_REQUEST_SPONSOR_ALLOWED_AREA_TYPES' "
                                + "AND reason LIKE '%(V83, 19/10/2026)' ORDER BY changed_at DESC LIMIT 1", String.class));

                assertEquals(usersBefore, jdbc.queryForList("SELECT id::text || ':' || access_level FROM users ORDER BY id", String.class),
                        "V83 không đổi cấp tài khoản đang có");

                // Lần chạy 2: không lỗi, không ghi thêm gì
                jdbc.execute(script);
                assertEquals(audits, v83AuditsByArea(ids));
                assertEquals(presetAuditsBefore + 3, presetAudits());
                assertEquals(changeLogsBefore + 3, configChangeLogs());
                for (UUID id : ids) {
                    assertEquals(versionsBefore.get(id) + 1, ((Number) areaRow(id).get("version")).longValue());
                }
            } catch (Exception e) {
                throw e instanceof RuntimeException re ? re : new RuntimeException(e);
            } finally {
                tx.setRollbackOnly();
            }
        });
    }

    @Test
    @DisplayName("BR-AL-24 (V83): cấp mặc định khi tạo mới FM / ADMIN = 3 (đọc cấu hình thật), NORMAL_USER 1, GUARD 2")
    void defaultAccessLevelForNewAccounts() {
        assertEquals(3, systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER));
        assertEquals(3, systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_ADMIN));
        assertEquals(3, userService.resolveDefaultAccessLevel(Role.FACILITY_MANAGER));
        assertEquals(3, userService.resolveDefaultAccessLevel(Role.ADMIN));
        assertEquals(1, userService.resolveDefaultAccessLevel(Role.NORMAL_USER));
        assertEquals(2, userService.resolveDefaultAccessLevel(Role.GUARD));
    }

    @Test
    @DisplayName("V83: 6 CHECK mới có trong schema đang dùng (test chạy lại V83 ở trên rollback cả DDL), không còn khu / preset kiểu cũ")
    void v83ConstraintsPresent_noOldTypesLeft() {
        assertEquals(NEW_CONSTRAINTS.size(), count(
                "SELECT count(*) FROM pg_constraint WHERE connamespace = current_schema()::regnamespace AND conname = ANY (?)",
                (Object) NEW_CONSTRAINTS.toArray(new String[0])));
        assertEquals(0, count("SELECT count(*) FROM areas WHERE area_level NOT IN ('PUBLIC', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL') "
                + "OR explicit_authorization_required"));
        assertEquals(3, count("SELECT count(*) FROM area_level_presets"));
    }

    private UUID saveArea(String tag, Floor floor, AreaLevel level) {
        Area a = areaRepository.saveAndFlush(Area.builder()
                .name("V83 " + tag + " " + floor.getName())
                .areaLevel(level)
                .floorEntity(floor)
                .building(floor.getBuilding().getName())
                .floor(floor.getName())
                .isActive(true)
                .build());
        return a.getId();
    }

    private String config(String key) {
        return jdbc.queryForObject("SELECT config_value FROM system_configurations WHERE config_key = ?", String.class, key);
    }

    private static void assertArea(Map<String, Object> row, String level, int accessLevel) {
        assertEquals(level, row.get("area_level"));
        assertEquals(accessLevel, ((Number) row.get("area_access_level")).intValue());
        assertEquals(false, row.get("explicit_authorization_required"));
    }
}
