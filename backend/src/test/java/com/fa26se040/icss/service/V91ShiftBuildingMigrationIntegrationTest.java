package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class V91ShiftBuildingMigrationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("V91: Kiểm tra cột mới và thống kê dữ liệu điền sẵn trên DB test")
    void testV91MigrationAndBackfillStats() {
        // Kiểm tra cột tồn tại
        Integer teamBuildingColCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'guard_teams' AND column_name = 'building_id'",
                Integer.class
        );
        assertEquals(1, teamBuildingColCount);

        Integer shiftBuildingColCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'guard_shifts' AND column_name = 'building_id'",
                Integer.class
        );
        assertEquals(1, shiftBuildingColCount);

        Integer crossColCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_name = 'guard_shifts' AND column_name = 'is_cross_building'",
                Integer.class
        );
        assertEquals(1, crossColCount);

        // Thống kê đội
        Integer totalTeams = jdbc.queryForObject("SELECT COUNT(*) FROM guard_teams", Integer.class);
        Integer teamsWithBuilding = jdbc.queryForObject("SELECT COUNT(*) FROM guard_teams WHERE building_id IS NOT NULL", Integer.class);
        Integer teamsWithoutBuilding = jdbc.queryForObject("SELECT COUNT(*) FROM guard_teams WHERE building_id IS NULL", Integer.class);

        System.out.println("=== THỐNG KÊ ĐỘI BẢO VỆ SAU V91 ===");
        System.out.println("Tổng số đội: " + totalTeams);
        System.out.println("Đội được gán tòa: " + teamsWithBuilding);
        System.out.println("Đội không khớp tòa (chưa gán): " + teamsWithoutBuilding);
        jdbc.query("SELECT id, team_name, description, building_id FROM guard_teams", rs -> {
            System.out.println("  - Đội: " + rs.getString("team_name") + " | description: '" + rs.getString("description") + "' | building_id: " + rs.getObject("building_id"));
        });
        jdbc.query("SELECT id, name FROM buildings", rs -> {
            System.out.println("  - Tòa nhà hiện có: " + rs.getString("name") + " (" + rs.getObject("id") + ")");
        });

        // Thống kê ca trực
        Integer totalShifts = jdbc.queryForObject("SELECT COUNT(*) FROM guard_shifts", Integer.class);
        Integer shiftsWithBuilding = jdbc.queryForObject("SELECT COUNT(*) FROM guard_shifts WHERE building_id IS NOT NULL", Integer.class);
        Integer shiftsWithoutBuilding = jdbc.queryForObject("SELECT COUNT(*) FROM guard_shifts WHERE building_id IS NULL", Integer.class);
        Integer crossBuildingShifts = jdbc.queryForObject("SELECT COUNT(*) FROM guard_shifts WHERE is_cross_building = true", Integer.class);

        System.out.println("=== THỐNG KÊ CA TRỰC SAU V91 ===");
        System.out.println("Tổng số ca: " + totalShifts);
        System.out.println("Ca có tòa nhà: " + shiftsWithBuilding);
        System.out.println("Ca không có tòa nhà: " + shiftsWithoutBuilding);
        System.out.println("Ca luân chuyển (cross-building): " + crossBuildingShifts);

        assertNotNull(totalTeams);
        assertNotNull(totalShifts);
    }

    @Test
    @DisplayName("V91: Kiểm tra logic điền sẵn dữ liệu đội cũ và ca cũ (rollback)")
    void testBackfillLogicScenarios() {
        // V97_1 đã xóa Tòa Alpha khỏi DB -> test tự tạo Tòa Alpha tạm (nếu chưa có) và dọn sạch trong finally
        boolean createdAlpha = false;
        UUID alphaId = null;
        boolean createdSecond = false;
        UUID secondBuildingId = null;
        UUID testTeamId = UUID.randomUUID();
        try {
            List<UUID> existingAlpha = jdbc.queryForList(
                    "SELECT id FROM buildings WHERE name = 'Tòa Alpha'", UUID.class);
            createdAlpha = existingAlpha.isEmpty();
            alphaId = createdAlpha ? UUID.randomUUID() : existingAlpha.get(0);
            if (createdAlpha) {
                jdbc.update("INSERT INTO buildings (id, name, description, total_floors, is_active) VALUES (?, 'Tòa Alpha', 'Tòa test V91', 3, true)",
                        alphaId);
            }

            List<UUID> existingSecond = jdbc.queryForList(
                    "SELECT id FROM buildings WHERE name IN ('Tòa Epsilon', 'Tòa Beta') ORDER BY created_at ASC LIMIT 1",
                    UUID.class);
            createdSecond = existingSecond.isEmpty();
            secondBuildingId = createdSecond ? UUID.randomUUID() : existingSecond.get(0);
            if (createdSecond) {
                jdbc.update("INSERT INTO buildings (id, name, description, total_floors, is_active) VALUES (?, 'Tòa Epsilon', 'Tòa test V91', 5, true)",
                        secondBuildingId);
            }

            assertNotNull(alphaId);
            assertNotNull(secondBuildingId);

            // Tạo đội tạm thời có description = 'Tòa Alpha'
            jdbc.update("INSERT INTO guard_teams (id, team_name, description, is_active) VALUES (?, ?, ?, true)",
                    testTeamId, "Đội Test V91 Alpha " + testTeamId, "Tòa Alpha");

            // Chạy lại câu UPDATE của V91 cho đội
            jdbc.update("""
                UPDATE guard_teams gt
                SET building_id = b.id
                FROM buildings b
                WHERE gt.id = ?
                  AND gt.description IS NOT NULL
                  AND lower(trim(gt.description)) = lower(trim(b.name))
            """, testTeamId);

            UUID assignedBuildingId = jdbc.queryForObject(
                    "SELECT building_id FROM guard_teams WHERE id = ?", UUID.class, testTeamId);
            assertEquals(alphaId, assignedBuildingId);
        } finally {
            // Dọn dẹp đội test và tòa test tạm (đội xóa trước tòa vì FK ON DELETE RESTRICT)
            jdbc.update("DELETE FROM guard_teams WHERE id = ?", testTeamId);
            if (createdAlpha && alphaId != null) {
                jdbc.update("DELETE FROM buildings WHERE id = ?", alphaId);
            }
            if (createdSecond && secondBuildingId != null) {
                jdbc.update("DELETE FROM buildings WHERE id = ?", secondBuildingId);
            }
        }
    }
}
