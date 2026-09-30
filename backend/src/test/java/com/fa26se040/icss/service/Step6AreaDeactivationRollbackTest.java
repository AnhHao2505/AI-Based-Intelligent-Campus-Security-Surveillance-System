package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.guest.GuestTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Step 6 — lỗi giữa chừng khi vô hiệu hoá (sau khi đã thu hồi AP, huỷ đơn) -> rollback toàn bộ:
 * khu vực vẫn hoạt động, AP / đơn / lượt khách giữ nguyên, ảnh khách KHÔNG bị xoá khỏi MinIO, không thông báo.
 * Tách class riêng vì @SpyBean AuditService tạo Spring context riêng.
 * Cách ép lỗi: audit GUEST_VISIT / REVOKE (bước cuối, sau AP và đơn) ném RuntimeException.
 */
class Step6AreaDeactivationRollbackTest extends GuestTestSupport {

    @SpyBean
    private AuditService auditService;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void resetSpy() {
        Mockito.reset(auditService);
    }

    @Test
    @DisplayName("Rollback: lỗi khi thu hồi lượt khách -> khu vực, AP, đơn, lượt khách, ảnh giữ nguyên; không audit, không thông báo")
    void failureMidway_rollsBackEverything() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime now = OffsetDateTime.now();
        AreaAssignedPersonnel ap = newAp(area, otherHost, now.minusDays(1), now.plusDays(3));
        AccessRequest request = accessRequestRepository.save(AccessRequest.builder()
                .area(area).requester(otherHost).requestType(RequestType.INDIVIDUAL)
                .purpose("Đơn test rollback step 6").startTime(now.plusHours(2)).endTime(now.plusHours(3))
                .status(RequestStatus.PENDING).build());
        GuestVisit visit = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(area), "Khách rollback");
        Guest g = withPhoto(guestsOf(visit.getId()).get(0), future(120).plusHours(24));
        Long v0 = jdbc.queryForObject("SELECT version FROM areas WHERE id = ?", Long.class, area.getId());

        AtomicInteger guestRevokeAudits = new AtomicInteger();
        doAnswer(inv -> {
            if (inv.getArgument(0) == AuditTargetType.GUEST_VISIT && inv.getArgument(1) == AuditAction.REVOKE) {
                guestRevokeAudits.incrementAndGet();
                throw new RuntimeException("Step 6: ép lỗi khi thu hồi lượt khách");
            }
            return inv.callRealMethod();
        }).when(auditService).record(any(), any(), any(), any(), any(), any(), any(), any(), nullable(AuditActor.class));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", "Khu vực sửa chữa, ngừng sử dụng theo quyết định");
        body.put("version", v0);
        MvcResult r = send(post("/api/areas/{id}/deactivate", area.getId()), admin, body).andReturn();

        assertTrue(status(r) >= 500, "Lỗi giữa chừng phải trả 5xx: HTTP " + status(r));
        assertEquals(1, guestRevokeAudits.get(), "Tiền đề: luồng phải chạy tới bước thu hồi lượt khách");

        Map<String, Object> areaRow = jdbc.queryForMap("SELECT is_active, deleted_at, version FROM areas WHERE id = ?", area.getId());
        assertEquals(true, areaRow.get("is_active"));
        assertNull(areaRow.get("deleted_at"));
        assertEquals(v0, ((Number) areaRow.get("version")).longValue());
        assertNull(jdbc.queryForObject("SELECT revoked_at FROM area_assigned_personnel WHERE id = ?", Object.class, ap.getId()),
                "AP đã thu hồi trong transaction phải rollback");
        assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, request.getId()));
        assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM guest_visits WHERE id = ?", String.class, visit.getId()));
        assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus());
        assertTrue(embeddingRepository.existsById(g.getId()));
        verify(photoStorage, never()).removeObject(g.getPhotoObjectKey());

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE area_id = ? AND action IN ('DEACTIVATE','REVOKE','CANCEL')",
                Integer.class, area.getId()), "Rollback thì không còn audit nào");
        assertTrue(auditsForTarget(request.getId().toString()).isEmpty());
        assertTrue(notificationsOf(otherHost, NotificationType.REQUEST_SYSTEM_CANCELLED).isEmpty());
        assertTrue(notificationsOf(hostL2, NotificationType.GUEST_VISIT_REVOKED).isEmpty());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'ACCESS_PERMISSION_REVOKED'",
                Integer.class, otherHost.getId()), "Rollback thì không báo thu hồi AP");
    }
}
