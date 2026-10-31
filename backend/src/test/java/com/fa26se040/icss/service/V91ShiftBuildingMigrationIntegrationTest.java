package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

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
        // Lấy tòa Alpha và Beta từ DB test
        UUID alphaId = jdbc.queryForObject("SELECT id FROM buildings WHERE name = 'Tòa Alpha'", UUID.class);
        UUID betaId = jdbc.queryForObject("SELECT id FROM buildings WHERE name = 'Tòa Beta'", UUID.class);
        assertNotNull(alphaId);
        assertNotNull(betaId);

        // Tạo đội tạm thời có description = 'Tòa Alpha'
        UUID testTeamId = UUID.randomUUID();
        jdbc.update("INSERT INTO guard_teams (id, team_name, description, is_active) VALUES (?, ?, ?, true)",
                testTeamId, "Đội Test V91 Alpha", "Tòa Alpha");

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

        // Dọn dẹp đội test
        jdbc.update("DELETE FROM guard_teams WHERE id = ?", testTeamId);
    }
}
