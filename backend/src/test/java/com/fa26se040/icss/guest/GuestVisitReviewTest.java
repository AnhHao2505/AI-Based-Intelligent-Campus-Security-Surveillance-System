package com.fa26se040.icss.guest;

import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * P3 — FM duyệt / từ chối / thu hồi lượt khách (BR-GV-10..13, 30, 32, 33).
 */
public class GuestVisitReviewTest extends GuestTestSupport {

    private static final String BASE = "/api/guest-visits";

    private UUID createVisit(User host, List<Area> areas, OffsetDateTime s, OffsetDateTime e) throws Exception {
        MvcResult r = send(post(BASE), host, createBody(s, e, areas, List.of(guestInput("Nguyễn Văn Khách", null)))).andReturn();
        assertEquals(201, status(r), r.getResponse().getContentAsString());
        return UUID.fromString(json(r).path("data").path("id").asText());
    }

    private UUID createVisit(User host) throws Exception {
        return createVisit(host, List.of(internalArea), future(0), future(120));
    }

    private MvcResult review(User actor, UUID id, Long version, String decision, String reason) throws Exception {
        Map<String, Object> b = new HashMap<>();
        if (version != null) b.put("version", version);
        if (decision != null) b.put("decision", decision);
        if (reason != null) b.put("reason", reason);
        return send(patch(BASE + "/" + id + "/review"), actor, b).andReturn();
    }

    private MvcResult revoke(User actor, UUID id, Long version, String reason) throws Exception {
        Map<String, Object> b = new HashMap<>();
        if (version != null) b.put("version", version);
        if (reason != null) b.put("reason", reason);
        return send(patch(BASE + "/" + id + "/revoke"), actor, b).andReturn();
    }

    private void assertRejected(MvcResult r, int http, String code) throws Exception {
        assertEquals(http, status(r), "HTTP: " + r.getResponse().getContentAsString());
        assertEquals(code, errorCode(r), "Mã lỗi: " + message(r));
    }

    private long notif(User u, NotificationType t, UUID ref) {
        return notificationsOf(u, t).stream().filter(n -> ref.equals(n.getReferenceId())).count();
    }

    // ================================================================== duyệt / từ chối

    @Test
    @DisplayName("GV-10/33: FM duyệt -> APPROVED, version 1, audit APPROVE; host nhận APPROVED; mọi ADMIN nhận GUEST_PHOTO_REQUIRED")
    void approve_ok() throws Exception {
        UUID id = createVisit(hostL2);
        MvcResult r = review(fm, id, 0L, "APPROVED", null);
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        GuestVisit v = visit(id);
        assertEquals(GuestVisitStatus.APPROVED, v.getStatus());
        assertEquals(1L, v.getVersion());
        assertNotNull(v.getReviewedAt());
        assertEquals(1, audits(id.toString(), AuditTargetType.GUEST_VISIT, AuditAction.APPROVE).size());
        assertEquals(1, notif(hostL2, NotificationType.GUEST_VISIT_APPROVED, id));
        assertEquals(1, notif(admin, NotificationType.GUEST_PHOTO_REQUIRED, id));
        assertEquals(0, notif(fm, NotificationType.GUEST_PHOTO_REQUIRED, id), "FM không nhận yêu cầu gắn ảnh");
    }

    @Test
    @DisplayName("GV-10: từ chối thiếu lý do / lý do 9 ký tự -> 400 ERR_GUEST_019; có lý do -> REJECTED, audit REJECT kèm lý do, host nhận REJECTED")
    void reject() throws Exception {
        UUID id = createVisit(hostL2);
        assertRejected(review(fm, id, 0L, "REJECTED", null), 400, "ERR_GUEST_019");
        assertRejected(review(fm, id, 0L, "REJECTED", "123456789"), 400, "ERR_GUEST_019");
        assertEquals(200, status(review(fm, id, 0L, "REJECTED", REASON)));
        GuestVisit v = visit(id);
        assertEquals(GuestVisitStatus.REJECTED, v.getStatus());
        assertEquals(REASON, v.getReviewReason());
        List<AuditLog> a = audits(id.toString(), AuditTargetType.GUEST_VISIT, AuditAction.REJECT);
        assertEquals(1, a.size());
        assertEquals(REASON, a.get(0).getReason());
        assertEquals(1, notif(hostL2, NotificationType.GUEST_VISIT_REJECTED, id));
    }

    @Test
    @DisplayName("GV-10/32: quyết định lạ -> 400 ERR_GUEST_024; thiếu version -> 400 ERR_GUEST_015; version cũ -> 409 ERR_GUEST_016; lượt đã xử lý -> 409 ERR_GUEST_017")
    void review_dataAndState() throws Exception {
        UUID id = createVisit(hostL2);
        assertRejected(review(fm, id, 0L, "CANCELLED", null), 400, "ERR_GUEST_024");
        assertRejected(review(fm, id, null, "APPROVED", null), 400, "ERR_GUEST_015");
        assertRejected(review(fm, id, 7L, "APPROVED", null), 409, "ERR_GUEST_016");
        assertEquals(200, status(review(fm, id, 0L, "APPROVED", null)));
        assertRejected(review(fm2, id, 1L, "REJECTED", REASON), 409, "ERR_GUEST_017");
    }

    @Test
    @DisplayName("GV-10: FM không duyệt / từ chối / thu hồi lượt mà mình là host -> 403 ERR_GUEST_022")
    void selfReview_blocked() throws Exception {
        UUID id = createVisit(fm);
        assertRejected(review(fm, id, 0L, "APPROVED", null), 403, "ERR_GUEST_022");
        assertRejected(review(fm, id, 0L, "REJECTED", REASON), 403, "ERR_GUEST_022");
        assertEquals(200, status(review(fm2, id, 0L, "APPROVED", null)));
        assertRejected(revoke(fm, id, 1L, REASON), 403, "ERR_GUEST_022");
    }

    @Test
    @DisplayName("GV-10: NORMAL_USER / ADMIN / GUARD gọi duyệt hoặc thu hồi -> 403")
    void review_otherRoles_forbidden() throws Exception {
        UUID id = createVisit(hostL2);
        assertEquals(403, status(review(hostL2, id, 0L, "APPROVED", null)));
        assertEquals(403, status(review(admin, id, 0L, "APPROVED", null)));
        assertEquals(403, status(review(guard, id, 0L, "APPROVED", null)));
        assertEquals(403, status(revoke(admin, id, 0L, REASON)));
        assertEquals(GuestVisitStatus.PENDING, visit(id).getStatus());
    }

    @Test
    @DisplayName("GV-12: duyệt khi đã tới giờ bắt đầu -> 409 ERR_GUEST_023, lượt vẫn PENDING")
    void approve_afterStart() throws Exception {
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.PENDING, OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now().plusHours(1),
                List.of(internalArea), "Khách tới muộn");
        assertRejected(review(fm, v.getId(), 0L, "APPROVED", null), 409, "ERR_GUEST_023");
        assertEquals(GuestVisitStatus.PENDING, visit(v.getId()).getStatus());
    }

    @Test
    @DisplayName("GV-11: host mất AP giữa lúc tạo và lúc duyệt -> 409 ERR_GUEST_021, không duyệt, không audit APPROVE")
    void approve_recheck_hostLostAp() throws Exception {
        OffsetDateTime s = future(0), e = future(120);
        AreaAssignedPersonnel ap = newAp(contactArea, hostL2, s.minusDays(1), e.plusDays(1));
        UUID id = createVisit(hostL2, List.of(contactArea), s, e);
        ap.setRevokedAt(OffsetDateTime.now());
        ap.setRevokedBy(fm);
        ap.setRevokeReason("Thu hồi AP giữa chừng để thử BR-GV-11");
        assignedPersonnelRepository.save(ap);
        MvcResult r = review(fm, id, 0L, "APPROVED", null);
        assertRejected(r, 409, "ERR_GUEST_021");
        assertTrue(message(r).contains(contactArea.getName()), message(r));
        assertEquals(GuestVisitStatus.PENDING, visit(id).getStatus());
        assertTrue(audits(id.toString(), AuditTargetType.GUEST_VISIT, AuditAction.APPROVE).isEmpty());
    }

    @Test
    @DisplayName("GV-11: host bị hạ cấp / khu vực đổi sang HIGHLY / khu vực ngừng hoạt động / config thời lượng giảm -> 409 ERR_GUEST_021")
    void approve_recheck_other() throws Exception {
        UUID a = createVisit(hostL2);
        User h = userRepository.findById(hostL2.getId()).orElseThrow();
        h.setAccessLevel(1);
        userRepository.save(h);
        assertRejected(review(fm, a, 0L, "APPROVED", null), 409, "ERR_GUEST_021");

        Area area2 = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        UUID b = createVisit(otherHost, List.of(area2), future(0), future(120));
        Area x = reload(area2);
        x.setAreaLevel(AreaLevel.HIGHLY_CONFIDENTIAL);
        areaRepository.save(x);
        assertRejected(review(fm, b, 0L, "APPROVED", null), 409, "ERR_GUEST_021");

        Area area3 = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        UUID c = createVisit(otherHost, List.of(area3), future(0), future(120));
        Area y = reload(area3);
        y.setIsActive(false);
        areaRepository.save(y);
        assertRejected(review(fm, c, 0L, "APPROVED", null), 409, "ERR_GUEST_021");

        Area area4 = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        UUID d = createVisit(otherHost, List.of(area4), future(0), future(180));
        setConfig(ConfigKey.GUEST_VISIT_MAX_HOURS, "2");
        assertRejected(review(fm, d, 0L, "APPROVED", null), 409, "ERR_GUEST_021");
    }

    // ================================================================== thu hồi (BR-GV-13)

    @Test
    @DisplayName("GV-13: thu hồi APPROVED -> REVOKED, xoá sinh trắc ngay, audit REVOKE + DELETE_BIOMETRIC, host nhận REVOKED")
    void revoke_ok() throws Exception {
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(internalArea), "Khách có ảnh");
        Guest g = withPhoto(guestsOf(v.getId()).get(0), future(120).plusHours(24));
        String key = g.getPhotoObjectKey();
        assertRejected(revoke(fm, v.getId(), 0L, null), 400, "ERR_GUEST_019");
        MvcResult r = revoke(fm, v.getId(), 0L, REASON);
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        GuestVisit after = visit(v.getId());
        assertEquals(GuestVisitStatus.REVOKED, after.getStatus());
        assertEquals(REASON, after.getRevokeReason());
        assertEquals(GuestBiometricStatus.DELETED, guest(g.getId()).getBiometricStatus());
        assertFalse(embeddingRepository.existsById(g.getId()));
        verify(photoStorage).removeObject(key);
        assertEquals(1, audits(v.getId().toString(), AuditTargetType.GUEST_VISIT, AuditAction.REVOKE).size());
        assertEquals(1, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC).size());
        assertEquals(1, notif(hostL2, NotificationType.GUEST_VISIT_REVOKED, v.getId()));
    }

    @Test
    @DisplayName("GV-13: thu hồi PENDING -> 409 ERR_GUEST_017; thu hồi sau end -> 409 ERR_GUEST_018")
    void revoke_stateAndTime() throws Exception {
        UUID pending = createVisit(hostL2);
        assertRejected(revoke(fm, pending, 0L, REASON), 409, "ERR_GUEST_017");
        GuestVisit ended = newVisit(hostL2, GuestVisitStatus.APPROVED, OffsetDateTime.now().minusHours(3),
                OffsetDateTime.now().minusHours(1), List.of(internalArea), "Khách cũ");
        assertRejected(revoke(fm, ended.getId(), 0L, REASON), 409, "ERR_GUEST_018");
    }

    // ================================================================== đồng thời + danh sách

    @Test
    @DisplayName("GV-32: host huỷ đồng thời với FM duyệt, cùng version -> đúng 1 thành công, 1 nhận 409")
    void cancelVersusApprove_concurrent() throws Exception {
        UUID id = createVisit(hostL2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Future<Integer> c = pool.submit(() -> {
            go.await();
            Map<String, Object> b = Map.of("version", 0);
            return status(send(patch(BASE + "/" + id + "/cancel"), hostL2, b).andReturn());
        });
        Future<Integer> a = pool.submit(() -> {
            go.await();
            return status(review(fm, id, 0L, "APPROVED", null));
        });
        go.countDown();
        List<Integer> codes = new ArrayList<>(List.of(c.get(30, TimeUnit.SECONDS), a.get(30, TimeUnit.SECONDS)));
        pool.shutdown();
        Collections.sort(codes);
        assertEquals(List.of(200, 409), codes);
        GuestVisitStatus st = visit(id).getStatus();
        assertTrue(st == GuestVisitStatus.CANCELLED || st == GuestVisitStatus.APPROVED, st.name());
        assertEquals(1L, visit(id).getVersion());
    }

    @Test
    @DisplayName("FM xem danh sách lượt theo trạng thái; NORMAL_USER gọi danh sách chung -> 403")
    void list_forStaff() throws Exception {
        UUID id = createVisit(hostL2);
        JsonNode content = json(send(get(BASE + "?status=PENDING&size=100"), fm, null).andReturn()).path("data").path("content");
        boolean found = false;
        for (JsonNode n : content) {
            found |= n.path("id").asText().equals(id.toString());
        }
        assertTrue(found, "lượt mới tạo có trong danh sách PENDING (mới nhất trước)");
        assertEquals(403, status(send(get(BASE), hostL2, null).andReturn()));
    }
}
