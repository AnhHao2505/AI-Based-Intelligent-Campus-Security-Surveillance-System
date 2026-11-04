package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.guest.GuestTestSupport;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * fix/type-change-guest-visits: ADMIN đổi loại khu vực -> lượt khách chưa kết thúc không còn hợp lệ với loại mới
 * (BR-GV-04: chỉ khu Bảo mật nhận khách) được xử lý như vô hiệu hoá khu vực (BR-AD-09): PENDING huỷ, APPROVED thu hồi,
 * báo host sau commit, audit GUEST_VISIT / CANCEL hoặc REVOKE. Lượt khách còn hợp lệ giữ nguyên; đổi loại bị chặn thì
 * không đổi gì. Xem trước trả số lượt khách bị ảnh hưởng.
 */
class AreaTypeChangeGuestVisitsTest extends GuestTestSupport {

    static final String TYPE_REASON = "Điều chỉnh loại khu vực theo quyết định mới";

    @Autowired JdbcTemplate jdbc;

    private Long dbVersion(Area area) {
        return jdbc.queryForObject("SELECT version FROM areas WHERE id = ?", Long.class, area.getId());
    }

    private Map<String, Object> visitRow(GuestVisit v) {
        return jdbc.queryForMap("SELECT status, revoked_by, revoke_reason, cancel_reason FROM guest_visits WHERE id = ?", v.getId());
    }

    private List<Map<String, Object>> notifs(com.fa26se040.icss.entity.User user, String type) {
        return jdbc.queryForList("SELECT message, reference_id, reference_type FROM notifications WHERE recipient_id = ? AND type = ?",
                user.getId(), type);
    }

    private MvcResult changeType(Area area, AreaLevel newLevel) throws Exception {
        Area current = reload(area);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", current.getName());
        body.put("areaLevel", newLevel.name());
        body.put("floorId", floor.getId());
        body.put("centerLatitude", LAT);
        body.put("centerLongitude", LNG);
        body.put("version", dbVersion(area));
        body.put("reason", TYPE_REASON);
        return send(put("/api/areas/{id}", area.getId()), admin, body).andReturn();
    }

    private JsonNode preview(Area area, AreaLevel newLevel) throws Exception {
        MvcResult r = send(get("/api/areas/{id}/type-change-preview", area.getId()).param("newAreaLevel", newLevel.name()),
                admin, null).andReturn();
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        return json(r).path("data");
    }

    @Test
    @DisplayName("Đổi loại Bảo mật -> Tuyệt mật: lượt APPROVED chưa kết thúc bị thu hồi (revoked_by ADMIN), host nhận GUEST_VISIT_REVOKED, có audit REVOKE")
    void approvedVisit_revoked_hostNotified() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        GuestVisit approved = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(area), "Khách duyệt");

        MvcResult r = changeType(area, AreaLevel.HIGHLY_CONFIDENTIAL);

        assertEquals(200, status(r), r.getResponse().getContentAsString());
        Map<String, Object> row = visitRow(approved);
        assertEquals("REVOKED", row.get("status"));
        assertEquals(admin.getId(), row.get("revoked_by"));
        String reason = (String) row.get("revoke_reason");
        assertTrue(reason != null && reason.contains(area.getName()), "lý do thu hồi nêu tên khu vực: " + reason);
        assertEquals(1, notificationsOf(hostL2, NotificationType.GUEST_VISIT_REVOKED).size(), "host nhận thông báo thu hồi");
        List<Map<String, Object>> revokeNotifs = notifs(hostL2, "GUEST_VISIT_REVOKED");
        assertEquals(approved.getId(), revokeNotifs.get(0).get("reference_id"));
        assertEquals(1, audits(approved.getId().toString(), AuditTargetType.GUEST_VISIT, AuditAction.REVOKE).size());
        assertEquals(AreaLevel.HIGHLY_CONFIDENTIAL, reload(area).getAreaLevel());
    }

    @Test
    @DisplayName("Đổi loại Bảo mật -> Công khai: lượt PENDING bị huỷ, host nhận GUEST_VISIT_CANCELLED_BY_SYSTEM, có audit CANCEL")
    void pendingVisit_cancelled_hostNotified() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        GuestVisit pending = newVisit(otherHost, GuestVisitStatus.PENDING, future(0), future(60), List.of(area), "Khách chờ");

        MvcResult r = changeType(area, AreaLevel.PUBLIC);

        assertEquals(200, status(r), r.getResponse().getContentAsString());
        Map<String, Object> row = visitRow(pending);
        assertEquals("CANCELLED", row.get("status"));
        String reason = (String) row.get("cancel_reason");
        assertTrue(reason != null && reason.contains(area.getName()), "lý do huỷ nêu tên khu vực: " + reason);
        List<Map<String, Object>> cancelNotifs = notifs(otherHost, "GUEST_VISIT_CANCELLED_BY_SYSTEM");
        assertEquals(1, cancelNotifs.size(), "host lượt PENDING nhận thông báo huỷ");
        assertEquals(pending.getId(), cancelNotifs.get(0).get("reference_id"));
        assertEquals("GUEST_VISIT", cancelNotifs.get(0).get("reference_type"));
        assertEquals(1, audits(pending.getId().toString(), AuditTargetType.GUEST_VISIT, AuditAction.CANCEL).size());
    }

    @Test
    @DisplayName("Lượt khách còn hợp lệ (loại mới vẫn nhận khách), lượt ở khu khác, lượt đã huỷ / đã kết thúc: giữ nguyên")
    void stillValidOrUnrelatedVisits_unchanged() throws Exception {
        // Loại mới là Bảo mật (vẫn nhận khách) -> lượt khách của khu giữ nguyên
        Area toConfidential = newArea(AreaLevel.HIGHLY_CONFIDENTIAL);
        GuestVisit keptApproved = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(toConfidential), "Khách giữ");

        // Khu đổi sang Công khai: chỉ lượt PENDING/APPROVED chưa kết thúc bị xử lý
        Area toPublic = newArea(AreaLevel.CONFIDENTIAL);
        Area other = newArea(AreaLevel.CONFIDENTIAL);
        GuestVisit otherAreaVisit = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(other), "Khách khu khác");
        GuestVisit cancelled = newVisit(hostL2, GuestVisitStatus.CANCELLED, future(0), future(120), List.of(toPublic), "Khách đã huỷ");
        OffsetDateTime past = OffsetDateTime.now().minusDays(1);
        GuestVisit ended = newVisit(hostL2, GuestVisitStatus.APPROVED, past.minusHours(2), past, List.of(toPublic), "Khách đã xong");

        assertEquals(200, status(changeType(toConfidential, AreaLevel.CONFIDENTIAL)));
        assertEquals(200, status(changeType(toPublic, AreaLevel.PUBLIC)));

        assertEquals("APPROVED", visitRow(keptApproved).get("status"));
        assertEquals("APPROVED", visitRow(otherAreaVisit).get("status"));
        assertEquals("CANCELLED", visitRow(cancelled).get("status"));
        assertNull(visitRow(cancelled).get("revoked_by"));
        assertEquals("APPROVED", visitRow(ended).get("status"), "lượt đã kết thúc không bị thu hồi");
        assertTrue(notificationsOf(hostL2, NotificationType.GUEST_VISIT_REVOKED).isEmpty(), "không có lượt nào của hostL2 bị thu hồi");
        assertTrue(notifs(hostL2, "GUEST_VISIT_CANCELLED_BY_SYSTEM").isEmpty());
    }

    @Test
    @DisplayName("Đổi loại bị chặn (còn quyền chỉ định hiệu lực khi sang Công khai, ERR_AREA_049): lượt khách không đổi, không thông báo")
    void blockedTypeChange_visitsUnchanged() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        OffsetDateTime now = OffsetDateTime.now();
        newAp(area, hostL1, now.minusDays(1), now.plusDays(3));
        GuestVisit pending = newVisit(otherHost, GuestVisitStatus.PENDING, future(0), future(60), List.of(area), "Khách chờ");
        GuestVisit approved = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(area), "Khách duyệt");

        MvcResult r = changeType(area, AreaLevel.PUBLIC);

        assertEquals(409, status(r), r.getResponse().getContentAsString());
        assertEquals("ERR_AREA_049", errorCode(r));
        assertEquals("PENDING", visitRow(pending).get("status"));
        assertEquals("APPROVED", visitRow(approved).get("status"));
        assertTrue(notificationsOf(hostL2, NotificationType.GUEST_VISIT_REVOKED).isEmpty());
        assertTrue(notifs(otherHost, "GUEST_VISIT_CANCELLED_BY_SYSTEM").isEmpty());
        assertTrue(audits(approved.getId().toString(), AuditTargetType.GUEST_VISIT, AuditAction.REVOKE).isEmpty());
        assertEquals(AreaLevel.CONFIDENTIAL, reload(area).getAreaLevel());
    }

    @Test
    @DisplayName("Xem trước: trả đúng số lượt khách PENDING sẽ bị huỷ và APPROVED sẽ bị thu hồi; loại mới vẫn nhận khách thì 0; không đổi dữ liệu")
    void preview_returnsGuestVisitCounts() throws Exception {
        Area area = newArea(AreaLevel.CONFIDENTIAL);
        GuestVisit p1 = newVisit(otherHost, GuestVisitStatus.PENDING, future(0), future(60), List.of(area), "Khách chờ 1");
        newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(60), List.of(area), "Khách duyệt 1");
        newVisit(otherHost, GuestVisitStatus.APPROVED, future(0), future(60), List.of(area), "Khách duyệt 2");
        newVisit(hostL2, GuestVisitStatus.CANCELLED, future(0), future(60), List.of(area), "Khách đã huỷ");

        JsonNode toHighly = preview(area, AreaLevel.HIGHLY_CONFIDENTIAL);
        assertEquals(1, toHighly.path("guestVisitsToCancelCount").asInt());
        assertEquals(2, toHighly.path("guestVisitsToRevokeCount").asInt());

        Area highly = newArea(AreaLevel.HIGHLY_CONFIDENTIAL);
        newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(60), List.of(highly), "Khách khu tuyệt mật");
        JsonNode toConfidential = preview(highly, AreaLevel.CONFIDENTIAL);
        assertEquals(0, toConfidential.path("guestVisitsToCancelCount").asInt());
        assertEquals(0, toConfidential.path("guestVisitsToRevokeCount").asInt());

        assertEquals("PENDING", visitRow(p1).get("status"), "xem trước chỉ đọc");
        assertEquals(AreaLevel.CONFIDENTIAL, reload(area).getAreaLevel());
    }
}
