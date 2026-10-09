package com.fa26se040.icss.guest;

import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.service.GuestRules;
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
 * P2 — host tạo / xem / huỷ lượt khách (BR-GV-01..06, 09, 30, 32, 33).
 */
public class GuestVisitHostTest extends GuestTestSupport {

    private static final String BASE = "/api/guest-visits";

    private MvcResult create(User actor, Object body) throws Exception {
        return send(post(BASE), actor, body).andReturn();
    }

    private void assertRejected(MvcResult r, int http, String code) throws Exception {
        assertEquals(http, status(r), "HTTP: " + r.getResponse().getContentAsString());
        assertEquals(code, errorCode(r), "Mã lỗi: " + message(r));
    }

    private UUID createdId(MvcResult r) throws Exception {
        assertEquals(201, status(r), r.getResponse().getContentAsString());
        return UUID.fromString(json(r).path("data").path("id").asText());
    }

    // ================================================================== tạo — đúng

    @Test
    @DisplayName("GV-01..05 đúng: host cấp 2 tạo lượt 2 khách, khu vực INTERNAL -> 201 PENDING, audit CREATE, FM nhận 1 thông báo")
    void create_ok() throws Exception {
        Map<String, Object> body = createBody(future(0), future(120), List.of(internalArea),
                List.of(guestInput("  Nguyễn Văn Khách  ", "  Công ty ABC "), guestInput("Trần Thị Mời", "   ")));
        MvcResult r = create(hostL2, body);
        UUID id = createdId(r);
        JsonNode d = json(r).path("data");
        assertEquals("PENDING", d.path("status").asText());
        assertEquals(0, d.path("version").asLong());
        assertEquals(hostL2.getUserCode(), d.path("hostCode").asText());
        assertEquals(2, d.path("guests").size());
        assertEquals("Nguyễn Văn Khách", d.path("guests").get(0).path("fullName").asText(), "họ tên được trim");
        assertEquals("Công ty ABC", d.path("guests").get(0).path("organization").asText());
        assertTrue(d.path("guests").get(1).path("organization").isNull(), "đơn vị rỗng -> NULL");
        assertEquals("NO_PHOTO", d.path("guests").get(0).path("biometricStatus").asText());
        String raw = r.getResponse().getContentAsString();
        assertFalse(raw.contains("photoObjectKey") || raw.contains("objectKey") || raw.contains("url"), "response không chứa key / URL ảnh");

        assertEquals(1, audits(id.toString(), AuditTargetType.GUEST_VISIT, AuditAction.CREATE).size());
        assertEquals(1, notificationsOf(fm, NotificationType.GUEST_VISIT_PENDING).stream()
                .filter(n -> id.equals(n.getReferenceId())).count(), "mỗi FM đang hoạt động nhận 1 thông báo");
        assertEquals(0, notificationsOf(admin, NotificationType.GUEST_VISIT_PENDING).stream()
                .filter(n -> id.equals(n.getReferenceId())).count(), "ADMIN không nhận thông báo lượt mới");
    }

    @Test
    @DisplayName("GV-01 đúng: FM cấp 2 cũng làm host được")
    void create_ok_fm() throws Exception {
        User fmL2 = newUser("fm2l", Role.FACILITY_MANAGER, 2, true);
        createdId(create(fmL2, validBody()));
    }

    @Test
    @DisplayName("B-04: ADMIN (kể cả cấp 3) không làm host — tạo lượt / danh sách khu vực form / lượt của tôi / huỷ -> 403")
    void admin_notHost_forbidden() throws Exception {
        assertEquals(403, status(create(admin, validBody())));
        assertEquals(403, status(send(get("/api/guest-visits/selectable-areas"), admin, null).andReturn()));
        assertEquals(403, status(send(get("/api/guest-visits/my"), admin, null).andReturn()));
        UUID id = createdId(create(hostL2, validBody()));
        assertEquals(403, status(send(patch("/api/guest-visits/" + id + "/cancel"), admin, Map.of("version", 0)).andReturn()));
        assertFalse(GuestRules.HOST_ROLES.contains(Role.ADMIN));
    }

    @Test
    @DisplayName("GV-02/03 biên đúng: họ tên 2 và 100 ký tự, đơn vị 200 ký tự, mục đích đúng 10 ký tự, đủ 10 khách")
    void create_ok_boundaries() throws Exception {
        List<Map<String, Object>> guests = new ArrayList<>();
        guests.add(guestInput("An", "x".repeat(200)));
        guests.add(guestInput("B".repeat(100), null));
        for (int i = 0; i < 8; i++) {
            guests.add(guestInput("Khách số " + i, null));
        }
        Map<String, Object> body = createBody(future(0), future(60), List.of(internalArea), guests);
        body.put("purpose", "0123456789");
        createdId(create(hostL2, body));
    }

    // ================================================================== BR-GV-01

    @Test
    @DisplayName("GV-01: host cấp 1 -> 403 ERR_GUEST_002; GUARD -> 403; nâng GUEST_HOST_MIN_LEVEL = 3 thì host cấp 2 bị chặn, câu nêu số 3")
    void create_hostNotEligible() throws Exception {
        assertRejected(create(hostL1, validBody()), 403, "ERR_GUEST_002");
        assertEquals(403, status(create(guard, validBody())));
        setConfig(ConfigKey.GUEST_HOST_MIN_LEVEL, "3");
        MvcResult r = create(hostL2, validBody());
        assertRejected(r, 403, "ERR_GUEST_002");
        assertTrue(message(r).contains("3"), message(r));
    }

    // ================================================================== BR-GV-02

    @Test
    @DisplayName("GV-02: 0 khách / quá GUEST_MAX_PER_VISIT -> 400 ERR_GUEST_003 (câu nêu giới hạn)")
    void create_guestCount() throws Exception {
        assertRejected(create(hostL2, createBody(future(0), future(60), List.of(internalArea), List.of())), 400, "ERR_GUEST_003");
        setConfig(ConfigKey.GUEST_MAX_PER_VISIT, "2");
        MvcResult r = create(hostL2, createBody(future(0), future(60), List.of(internalArea),
                List.of(guestInput("Khách một", null), guestInput("Khách hai", null), guestInput("Khách ba", null))));
        assertRejected(r, 400, "ERR_GUEST_003");
        assertTrue(message(r).contains("2"), message(r));
    }

    @Test
    @DisplayName("GV-02: họ tên 1 ký tự (sau trim) / 101 ký tự -> 400 ERR_GUEST_004; đơn vị 201 ký tự -> 400 ERR_GUEST_005")
    void create_guestFields() throws Exception {
        assertRejected(create(hostL2, createBody(future(0), future(60), List.of(internalArea), List.of(guestInput("  A ", null)))), 400, "ERR_GUEST_004");
        assertRejected(create(hostL2, createBody(future(0), future(60), List.of(internalArea), List.of(guestInput("B".repeat(101), null)))), 400, "ERR_GUEST_004");
        assertRejected(create(hostL2, createBody(future(0), future(60), List.of(internalArea), List.of(guestInput("Khách hợp lệ", "x".repeat(201))))), 400, "ERR_GUEST_005");
    }

    @Test
    @DisplayName("GV-02: body có trường ngoài mô hình (CCCD, SĐT, email) -> 400, không tạo lượt")
    void create_unknownFieldsRejected() throws Exception {
        Map<String, Object> g = guestInput("Khách có SĐT", null);
        g.put("phone", "0901234567");
        assertEquals(400, status(create(hostL2, createBody(future(0), future(60), List.of(internalArea), List.of(g)))));
        Map<String, Object> body = validBody();
        body.put("cccd", "001099000000");
        assertEquals(400, status(create(hostL2, body)));
        assertEquals(0, guestVisitRepository.findByHostIdOrderByCreatedAtDesc(hostL2.getId(), org.springframework.data.domain.Pageable.unpaged()).getTotalElements());
    }

    // ================================================================== BR-GV-03

    @Test
    @DisplayName("GV-03: mục đích 9 ký tự (sau trim) / 501 ký tự -> 400 ERR_GUEST_006")
    void create_purpose() throws Exception {
        Map<String, Object> b1 = validBody();
        b1.put("purpose", "  123456789  ");
        assertRejected(create(hostL2, b1), 400, "ERR_GUEST_006");
        Map<String, Object> b2 = validBody();
        b2.put("purpose", "x".repeat(501));
        assertRejected(create(hostL2, b2), 400, "ERR_GUEST_006");
    }

    // ================================================================== BR-GV-04

    @Test
    @DisplayName("GV-04: không chọn khu vực / trùng khu vực -> 400 ERR_GUEST_007")
    void create_areasRequiredDistinct() throws Exception {
        assertRejected(create(hostL2, createBody(future(0), future(60), List.of(), List.of(guestInput("Khách một", null)))), 400, "ERR_GUEST_007");
        assertRejected(create(hostL2, createBody(future(0), future(60), List.of(internalArea, internalArea), List.of(guestInput("Khách một", null)))), 400, "ERR_GUEST_007");
    }

    @Test
    @DisplayName("GV-04: khu vực HIGHLY / PUBLIC -> 400 ERR_GUEST_009; ngừng hoạt động / không tồn tại -> 400 ERR_GUEST_008")
    void create_areaTypeAndActive() throws Exception {
        User host3 = newUser("h3", Role.NORMAL_USER, 3, true);
        newAp(highlyArea, host3, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(30));
        assertRejected(create(host3, createBody(future(0), future(60), List.of(highlyArea), List.of(guestInput("Khách một", null)))), 400, "ERR_GUEST_009");
        assertRejected(create(host3, createBody(future(0), future(60), List.of(publicArea), List.of(guestInput("Khách một", null)))), 400, "ERR_GUEST_009");

        Area inactive = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        inactive.setIsActive(false);
        areaRepository.save(inactive);
        assertRejected(create(hostL2, createBody(future(0), future(60), List.of(inactive), List.of(guestInput("Khách một", null)))), 400, "ERR_GUEST_008");

        Map<String, Object> body = createBody(future(0), future(60), List.of(), List.of(guestInput("Khách một", null)));
        body.put("areaIds", List.of(UUID.randomUUID().toString()));
        assertRejected(create(hostL2, body), 400, "ERR_GUEST_008");
    }

    // ================================================================== BR-GV-05

    @Test
    @DisplayName("GV-05: start ở quá khứ -> 010; start = end / end < start -> 011; dài hơn GUEST_VISIT_MAX_HOURS -> 012; quá GUEST_MAX_ADVANCE_DAYS -> 013")
    void create_window() throws Exception {
        List<Map<String, Object>> g = List.of(guestInput("Khách một", null));
        OffsetDateTime past = OffsetDateTime.now().minusMinutes(1);
        assertRejected(create(hostL2, createBody(past, past.plusHours(1), List.of(internalArea), g)), 400, "ERR_GUEST_010");
        assertRejected(create(hostL2, createBody(future(0), future(0), List.of(internalArea), g)), 400, "ERR_GUEST_011");
        assertRejected(create(hostL2, createBody(future(60), future(0), List.of(internalArea), g)), 400, "ERR_GUEST_011");
        MvcResult tooLong = create(hostL2, createBody(future(0), future(8 * 60 + 1), List.of(internalArea), g));
        assertRejected(tooLong, 400, "ERR_GUEST_012");
        assertTrue(message(tooLong).contains("8"), message(tooLong));
        createdId(create(hostL2, createBody(future(0), future(8 * 60), List.of(internalArea), g)));
        OffsetDateTime far = OffsetDateTime.now().plusDays(14).plusMinutes(5);
        MvcResult tooFar = create(hostL2, createBody(far, far.plusHours(1), List.of(internalArea), g));
        assertRejected(tooFar, 400, "ERR_GUEST_013");
        assertTrue(message(tooFar).contains("14"), message(tooFar));
    }

    // ================================================================== BR-GV-06

    @Test
    @DisplayName("GV-06: khu vực CONTACT (bắt buộc chỉ định) mà host không có AP -> 403 ERR_GUEST_014; AP phủ trọn khung -> 201")
    void create_hostAccess_ap() throws Exception {
        List<Map<String, Object>> g = List.of(guestInput("Khách một", null));
        OffsetDateTime s = future(0), e = future(120);
        assertRejected(create(hostL2, createBody(s, e, List.of(contactArea), g)), 403, "ERR_GUEST_014");
        newAp(contactArea, hostL2, s, e);
        createdId(create(hostL2, createBody(s, e, List.of(contactArea), g)));
    }

    @Test
    @DisplayName("GV-06: AP không phủ trọn khung (hết trước end / bắt đầu sau start / đã thu hồi) -> 403 ERR_GUEST_014")
    void create_hostAccess_apNotCovering() throws Exception {
        List<Map<String, Object>> g = List.of(guestInput("Khách một", null));
        OffsetDateTime s = future(0), e = future(120);
        Area a1 = newArea(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, 2, true);
        newAp(a1, hostL2, s.minusDays(1), e.minusMinutes(1));
        assertRejected(create(hostL2, createBody(s, e, List.of(a1), g)), 403, "ERR_GUEST_014");

        Area a2 = newArea(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, 2, true);
        newAp(a2, hostL2, s.plusMinutes(1), e.plusDays(1));
        assertRejected(create(hostL2, createBody(s, e, List.of(a2), g)), 403, "ERR_GUEST_014");

        Area a3 = newArea(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, 2, true);
        AreaAssignedPersonnel ap = newAp(a3, hostL2, s.minusDays(1), null);
        ap.setRevokedAt(OffsetDateTime.now().minusMinutes(1));
        ap.setRevokedBy(fm);
        ap.setRevokeReason("Thu hồi AP để thử BR-GV-06");
        assignedPersonnelRepository.save(ap);
        assertRejected(create(hostL2, createBody(s, e, List.of(a3), g)), 403, "ERR_GUEST_014");
    }

    @Test
    @DisplayName("GV-06: chỉ có đơn truy cập APPROVED / khu vực đang mở sự kiện -> không tính, 403 ERR_GUEST_014; cấp host < cấp khu vực -> 403")
    void create_hostAccess_notAcceptedSources() throws Exception {
        List<Map<String, Object>> g = List.of(guestInput("Khách một", null));
        OffsetDateTime s = future(0), e = future(120);
        Area viaRequest = newArea(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, 2, true);
        newApprovedRequest(viaRequest, hostL2, s.minusHours(1), e.plusHours(1));
        assertRejected(create(hostL2, createBody(s, e, List.of(viaRequest), g)), 403, "ERR_GUEST_014");

        // sự kiện phủ trọn khung lượt khách nhưng vẫn không tính là quyền của host (trong giới hạn 12h của chế độ sự kiện)
        OffsetDateTime now = OffsetDateTime.now();
        Area eventArea = newArea(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, 2, true);
        openEvent(eventArea, now.minusMinutes(5), now.plusHours(1));
        assertRejected(create(hostL2, createBody(now.plusMinutes(10), now.plusMinutes(40), List.of(eventArea), g)), 403, "ERR_GUEST_014");

        Area level3 = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 3, false);
        assertRejected(create(hostL2, createBody(s, e, List.of(level3), g)), 403, "ERR_GUEST_014");
    }

    @Test
    @DisplayName("GV-06: nhiều khu vực — một khu vực không đủ quyền thì cả lượt bị chặn, câu nêu tên khu vực đó")
    void create_hostAccess_eachArea() throws Exception {
        MvcResult r = create(hostL2, createBody(future(0), future(60), List.of(internalArea, contactArea), List.of(guestInput("Khách một", null))));
        assertRejected(r, 403, "ERR_GUEST_014");
        assertTrue(message(r).contains(contactArea.getName()), message(r));
    }

    // ================================================================== xem

    @Test
    @DisplayName("Host chỉ xem lượt của mình: /my không có lượt của người khác; GET lượt người khác -> 404 ERR_GUEST_001")
    void view_ownOnly() throws Exception {
        UUID mine = createdId(create(hostL2, validBody()));
        UUID theirs = createdId(create(otherHost, validBody()));
        JsonNode page = json(send(get(BASE + "/my"), hostL2, null).andReturn()).path("data").path("content");
        List<String> ids = new ArrayList<>();
        page.forEach(n -> ids.add(n.path("id").asText()));
        assertTrue(ids.contains(mine.toString()));
        assertFalse(ids.contains(theirs.toString()));
        assertEquals(200, status(send(get(BASE + "/" + mine), hostL2, null).andReturn()));
        assertRejected(send(get(BASE + "/" + theirs), hostL2, null).andReturn(), 404, "ERR_GUEST_001");
    }

    // ================================================================== BR-GV-09 huỷ

    private MvcResult cancel(User actor, UUID id, Long version, String reason) throws Exception {
        Map<String, Object> b = new HashMap<>();
        if (version != null) {
            b.put("version", version);
        }
        if (reason != null) {
            b.put("reason", reason);
        }
        return send(patch(BASE + "/" + id + "/cancel"), actor, b).andReturn();
    }

    @Test
    @DisplayName("GV-09: host huỷ PENDING không lý do -> 200 CANCELLED, version +1, audit CANCEL; lý do 9 ký tự -> 400 ERR_GUEST_019; lý do hợp lệ được lưu")
    void cancel_pending() throws Exception {
        UUID id = createdId(create(hostL2, validBody()));
        assertRejected(cancel(hostL2, id, 0L, "123456789"), 400, "ERR_GUEST_019");
        MvcResult ok = cancel(hostL2, id, 0L, null);
        assertEquals(200, status(ok), ok.getResponse().getContentAsString());
        GuestVisit v = visit(id);
        assertEquals(GuestVisitStatus.CANCELLED, v.getStatus());
        assertEquals(1L, v.getVersion());
        assertNotNull(v.getCancelledAt());
        assertEquals(1, audits(id.toString(), AuditTargetType.GUEST_VISIT, AuditAction.CANCEL).size());

        UUID id2 = createdId(create(hostL2, validBody()));
        assertEquals(200, status(cancel(hostL2, id2, 0L, "  Khách báo không đến được  ")));
        assertEquals("Khách báo không đến được", visit(id2).getCancelReason());
    }

    @Test
    @DisplayName("GV-09: người khác huỷ -> 404; thiếu version -> 400 ERR_GUEST_015; version cũ -> 409 ERR_GUEST_016")
    void cancel_authAndVersion() throws Exception {
        UUID id = createdId(create(hostL2, validBody()));
        assertRejected(cancel(otherHost, id, 0L, null), 404, "ERR_GUEST_001");
        assertRejected(cancel(hostL2, id, null, null), 400, "ERR_GUEST_015");
        assertRejected(cancel(hostL2, id, 5L, null), 409, "ERR_GUEST_016");
        assertEquals(GuestVisitStatus.PENDING, visit(id).getStatus());
    }

    @Test
    @DisplayName("GV-09 + 27: huỷ APPROVED -> xoá sinh trắc ngay: khách DELETED, embedding bị xoá, ảnh bị xoá khỏi kho, audit DELETE_BIOMETRIC")
    void cancel_approved_deletesBiometrics() throws Exception {
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(internalArea), "Khách có ảnh");
        Guest g = withPhoto(guestsOf(v.getId()).get(0), future(120).plusHours(24));
        String key = g.getPhotoObjectKey();
        MvcResult r = cancel(hostL2, v.getId(), 0L, null);
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        assertEquals(GuestVisitStatus.CANCELLED, visit(v.getId()).getStatus());
        Guest after = guest(g.getId());
        assertEquals(GuestBiometricStatus.DELETED, after.getBiometricStatus());
        assertNull(after.getPhotoObjectKey());
        assertNotNull(after.getBiometricDeletedAt());
        assertNull(after.getPendingDeleteObjectKey());
        assertFalse(embeddingRepository.existsById(g.getId()), "embedding bị xoá");
        verify(photoStorage).removeObject(key);
        assertEquals(1, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC).size());
    }

    @Test
    @DisplayName("GV-09: đã qua end -> 409 ERR_GUEST_018; lượt REJECTED / CANCELLED -> 409 ERR_GUEST_017")
    void cancel_stateAndTime() throws Exception {
        OffsetDateTime s = OffsetDateTime.now().minusHours(3), e = OffsetDateTime.now().minusHours(1);
        GuestVisit ended = newVisit(hostL2, GuestVisitStatus.APPROVED, s, e, List.of(internalArea), "Khách cũ");
        assertRejected(cancel(hostL2, ended.getId(), 0L, null), 409, "ERR_GUEST_018");
        GuestVisit rejected = transactionTemplate.execute(tx -> {
            GuestVisit x = newVisit(hostL2, GuestVisitStatus.PENDING, future(0), future(60), List.of(internalArea), "Khách bị từ chối");
            GuestVisit y = guestVisitRepository.findById(x.getId()).orElseThrow();
            y.setStatus(GuestVisitStatus.REJECTED);
            y.setReviewedBy(fm);
            y.setReviewedAt(OffsetDateTime.now());
            y.setReviewReason(REASON);
            return guestVisitRepository.save(y);
        });
        assertRejected(cancel(hostL2, rejected.getId(), rejected.getVersion(), null), 409, "ERR_GUEST_017");
    }

    @Test
    @DisplayName("GV-32: 2 lệnh huỷ đồng thời cùng version -> đúng 1 thành công, 1 nhận 409; đúng 1 audit CANCEL")
    void cancel_concurrent() throws Exception {
        UUID id = createdId(create(hostL2, validBody()));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                return status(cancel(hostL2, id, 0L, null));
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : fs) {
            codes.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        Collections.sort(codes);
        assertEquals(List.of(200, 409), codes);
        assertEquals(1, audits(id.toString(), AuditTargetType.GUEST_VISIT, AuditAction.CANCEL).size());
    }

    // ================================================================== B-04 điều kiện mời khách (FE)

    @Test
    @DisplayName("B-04: GET /eligibility — host cấp 2 canHost=true; cấp 1 canHost=false (myLevel 1, minLevel 2); nâng GUEST_HOST_MIN_LEVEL = 3 thì cấp 2 false, minLevel 3")
    void eligibility_followsConfig() throws Exception {
        JsonNode ok = json(send(get("/api/guest-visits/eligibility"), hostL2, null).andReturn()).path("data");
        assertTrue(ok.path("canHost").asBoolean());
        assertEquals(2, ok.path("myLevel").asInt());
        assertEquals(2, ok.path("minLevel").asInt());

        JsonNode low = json(send(get("/api/guest-visits/eligibility"), hostL1, null).andReturn()).path("data");
        assertFalse(low.path("canHost").asBoolean());
        assertEquals(1, low.path("myLevel").asInt());
        assertEquals(2, low.path("minLevel").asInt());

        setConfig(ConfigKey.GUEST_HOST_MIN_LEVEL, "3");
        JsonNode raised = json(send(get("/api/guest-visits/eligibility"), hostL2, null).andReturn()).path("data");
        assertFalse(raised.path("canHost").asBoolean());
        assertEquals(3, raised.path("minLevel").asInt());
    }

    @Test
    @DisplayName("B-04: /eligibility cùng quyền với tạo lượt — ADMIN / GUARD -> 403")
    void eligibility_forbiddenForAdminAndGuard() throws Exception {
        assertEquals(403, status(send(get("/api/guest-visits/eligibility"), admin, null).andReturn()));
        assertEquals(403, status(send(get("/api/guest-visits/eligibility"), guard, null).andReturn()));
    }

    @Test
    @DisplayName("B-04: GET /api/auth/me trả accessLevel của người đăng nhập")
    void authMe_returnsAccessLevel() throws Exception {
        assertEquals(2, json(send(get("/api/auth/me"), hostL2, null).andReturn()).path("data").path("accessLevel").asInt());
        assertEquals(1, json(send(get("/api/auth/me"), hostL1, null).andReturn()).path("data").path("accessLevel").asInt());
    }
}
