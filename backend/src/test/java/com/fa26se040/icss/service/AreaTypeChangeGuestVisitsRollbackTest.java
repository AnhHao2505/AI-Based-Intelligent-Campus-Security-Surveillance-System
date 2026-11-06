package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.guest.GuestTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * fix/type-change-guest-visits: lỗi giữa chừng khi đổi loại (lúc thu hồi lượt khách) -> rollback toàn bộ:
 * khu vực giữ loại cũ, lượt khách giữ nguyên, không thông báo. Tách class riêng vì @SpyBean AuditService tạo context riêng
 * (cùng cách với Step6AreaDeactivationRollbackTest).
 */
class AreaTypeChangeGuestVisitsRollbackTest extends GuestTestSupport {

    @SpyBean
    private AuditService auditService;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void resetSpy() {
        Mockito.reset(auditService);
    }

    @Test
    @DisplayName("Rollback: lỗi khi thu hồi lượt khách trong lúc đổi loại -> loại khu vực, lượt khách giữ nguyên; không thông báo")
    void failureMidway_rollsBackTypeChangeAndVisits() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        GuestVisit pending = newVisit(otherHost, GuestVisitStatus.PENDING, future(0), future(60), List.of(area), "Khách chờ rollback");
        GuestVisit approved = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(area), "Khách duyệt rollback");
        Long v0 = jdbc.queryForObject("SELECT version FROM areas WHERE id = ?", Long.class, area.getId());

        AtomicInteger guestRevokeAudits = new AtomicInteger();
        doAnswer(inv -> {
            if (inv.getArgument(0) == AuditTargetType.GUEST_VISIT && inv.getArgument(1) == AuditAction.REVOKE) {
                guestRevokeAudits.incrementAndGet();
                throw new RuntimeException("type-change-guest-visits: ép lỗi khi thu hồi lượt khách");
            }
            return inv.callRealMethod();
        }).when(auditService).record(any(), any(), any(), any(), any(), any(), any(), any(), nullable(AuditActor.class));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", reload(area).getName());
        body.put("areaLevel", AreaLevel.HIGHLY_CONFIDENTIAL.name());
        body.put("floorId", floor.getId());
        body.put("centerLatitude", LAT);
        body.put("centerLongitude", LNG);
        body.put("version", v0);
        body.put("reason", "Điều chỉnh loại khu vực theo quyết định mới");
        MvcResult r = send(put("/api/areas/{id}", area.getId()), admin, body).andReturn();

        assertTrue(status(r) >= 500, "Lỗi giữa chừng phải trả 5xx: HTTP " + status(r));
        assertEquals(1, guestRevokeAudits.get(), "Tiền đề: luồng phải chạy tới bước thu hồi lượt khách");

        Map<String, Object> areaRow = jdbc.queryForMap("SELECT area_level, version FROM areas WHERE id = ?", area.getId());
        assertEquals("CONFIDENTIAL", areaRow.get("area_level"), "loại khu vực giữ nguyên");
        assertEquals(v0, ((Number) areaRow.get("version")).longValue(), "version giữ nguyên");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM guest_visits WHERE id = ?", String.class, pending.getId()));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM guest_visits WHERE id = ?", String.class, approved.getId()));
        assertNull(jdbc.queryForObject("SELECT revoked_by FROM guest_visits WHERE id = ?", Object.class, approved.getId()));
        assertTrue(notificationsOf(hostL2, NotificationType.GUEST_VISIT_REVOKED).isEmpty(), "rollback -> không thông báo");
        assertTrue(notificationsOf(otherHost, NotificationType.GUEST_VISIT_CANCELLED_BY_SYSTEM).isEmpty());
    }
}
