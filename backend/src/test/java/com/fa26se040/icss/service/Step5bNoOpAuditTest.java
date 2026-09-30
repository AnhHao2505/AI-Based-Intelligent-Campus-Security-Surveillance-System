package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.area.AreaGeometry;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.enums.AreaLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Step 5b follow-up (BR-TC-17): Lần lưu KHÔNG đổi dữ liệu thật của khu vực:
 * - Không tăng version
 * - KHÔNG ghi audit
 * - Không gửi thông báo
 * - Trả 200 với dữ liệu hiện tại
 *
 * Phạm vi:
 * - PUT /api/areas/{id}
 * - PATCH /api/areas/{id}/access-rules
 * - PATCH /api/areas/{id}/geometry
 * - DELETE /api/areas/{id}/geometry (khi khu vực vốn không có hình)
 */
public class Step5bNoOpAuditTest extends Step5bTestSupport {

    @Test
    @DisplayName("BR-TC-17: PUT area với dữ liệu y hệt -> 200, version không đổi, KHÔNG ghi audit")
    void putArea_identicalData_noVersionBump_noAudit() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);
        long auditBefore = auditCountForArea(area);

        MvcResult r = putArea(admin, area, area.getName(), AreaLevel.INTERNAL_CONFIDENTIAL, null, v0);

        assertEquals(200, status(r), describe(r));
        assertEquals(v0, apiVersion(area), "Version không được đổi khi dữ liệu không đổi");
        assertEquals(v0, dbVersion(area), "DB version không được đổi khi dữ liệu không đổi");
        assertEquals(auditBefore, auditCountForArea(area), "Không được ghi thêm audit khi dữ liệu không đổi");
    }

    @Test
    @DisplayName("BR-TC-17: PATCH access-rules với dữ liệu y hệt -> 200, version không đổi, KHÔNG ghi audit")
    void patchAccessRules_identicalData_noVersionBump_noAudit() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);
        long auditBefore = auditCountForArea(area);

        MvcResult r = patchAccessRules(fm, area, 2, false, v0);

        assertEquals(200, status(r), describe(r));
        assertEquals(v0, apiVersion(area), "Version không được đổi khi access rules không đổi");
        assertEquals(v0, dbVersion(area), "DB version không được đổi khi access rules không đổi");
        assertEquals(auditBefore, auditCountForArea(area), "Không được ghi thêm audit khi access rules không đổi");
    }

    @Test
    @DisplayName("BR-TC-17: PATCH geometry với hình học y hệt -> 200, version không đổi, KHÔNG ghi audit")
    void patchGeometry_identicalGeometry_noVersionBump_noAudit() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaGeometry geom = square(0.1, 0.1, 0.2);
        Long v0 = apiVersion(area);

        // Lưu lần đầu để có hình học ban đầu
        MvcResult firstSave = saveGeometry(admin, area, geom, v0);
        assertEquals(200, status(firstSave), describe(firstSave));
        Long v1 = apiVersion(area);
        assertEquals(v0 + 1, v1);
        long auditAfterFirstSave = auditCountForArea(area);

        // Gửi lại đúng hình học đó cùng version v1
        MvcResult secondSave = saveGeometry(admin, area, geom, v1);

        assertEquals(200, status(secondSave), describe(secondSave));
        assertEquals(v1, apiVersion(area), "Version không được đổi khi hình học không đổi");
        assertEquals(v1, dbVersion(area), "DB version không được đổi khi hình học không đổi");
        assertEquals(auditAfterFirstSave, auditCountForArea(area), "Không được ghi thêm audit khi hình học không đổi");
    }

    @Test
    @DisplayName("BR-TC-17: DELETE geometry khi khu vực vốn không có hình -> 200, version không đổi, KHÔNG ghi audit")
    void deleteGeometry_whenNoGeometry_noVersionBump_noAudit() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);
        long auditBefore = auditCountForArea(area);

        MvcResult r = deleteGeometry(admin, area, v0);

        assertEquals(200, status(r), describe(r));
        assertEquals(v0, apiVersion(area), "Version không được đổi khi khu vực vốn không có hình");
        assertEquals(v0, dbVersion(area), "DB version không được đổi khi khu vực vốn không có hình");
        assertEquals(auditBefore, auditCountForArea(area), "Không được ghi thêm audit khi khu vực vốn không có hình");
    }
}
