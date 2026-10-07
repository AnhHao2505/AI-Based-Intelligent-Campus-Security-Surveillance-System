package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.guest.GuestTestSupport;
import com.fa26se040.icss.repository.AreaEventScheduleRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Step 6 — vô hiệu hoá khu vực (BR-AD-01..09).
 *
 * Hợp đồng API:
 * - POST /api/areas/{id}/deactivate: body {reason, version}, chỉ ADMIN, trả AreaResponse.
 * - GET /api/areas/{id}/dependencies: {areaId, version, canDeactivate, blockers[{errorCode,count,message}],
 *   apToRevoke, requestsToCancel, guestVisitsToCancel, guestVisitsToRevoke}.
 * - Mã: 051 sự cố mở, 052 sự kiện đang bật, 053 đã vô hiệu hoá.
 * Dựng trên GuestTestSupport: kho ảnh MinIO mock, user fixture bị vô hiệu hoá và sự kiện được đóng sau mỗi test.
 */
class Step6AreaDeactivationTest extends GuestTestSupport {

    static final String REASON = "Khu vực sửa chữa, ngừng sử dụng theo quyết định";
    static final String PREFIX = "Khu vực bị vô hiệu hoá: ";

    @Autowired JdbcTemplate jdbc;
    @Autowired CameraRepository cameraRepository;
    @Autowired SecurityIncidentRepository incidentRepository;
    @Autowired AreaEventScheduleRepository scheduleRepository;

    private final List<UUID> createdCameras = new ArrayList<>();
    private final List<UUID> createdIncidents = new ArrayList<>();

    /** DB test không rollback: gỡ camera (xoá mềm) và xoá sự cố do test tạo, để không lọt sang test module khác. */
    @AfterEach
    void cleanupStep6Fixtures() {
        transactionTemplate.executeWithoutResult(tx -> {
            for (Camera c : cameraRepository.findAllById(createdCameras)) {
                c.setArea(null);
                c.setDeletedAt(OffsetDateTime.now());
                cameraRepository.save(c);
            }
            incidentRepository.deleteAllById(createdIncidents);
        });
        createdCameras.clear();
        createdIncidents.clear();
    }

    // ------------------------------------------------------------------ dữ liệu

    Camera newCamera(Area area, String name) {
        Camera c = cameraRepository.save(Camera.builder()
                .cameraCode("CAM6-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .name(name)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build());
        createdCameras.add(c.getId());
        return c;
    }

    SecurityIncident newIncident(Area area, IncidentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        SecurityIncident i = incidentRepository.save(SecurityIncident.builder()
                .cameraCode("CAM6-INC")
                .area(area)
                .building(building.getName())
                .eventType("INTRUSION")
                .detectedAt(now.minusMinutes(10))
                .status(status)
                .version(0)
                .createdAt(now)
                .updatedAt(now)
                .build());
        createdIncidents.add(i.getId());
        return i;
    }

    AccessRequest newRequest(Area area, User requester, RequestStatus status, OffsetDateTime start, OffsetDateTime end,
                             User... members) {
        AccessRequest req = AccessRequest.builder()
                .area(area).requester(requester)
                .requestType(members.length > 0 ? RequestType.GROUP : RequestType.INDIVIDUAL)
                .purpose("Đơn test step 6 " + suffix).startTime(start).endTime(end).status(status)
                .reviewer(status == RequestStatus.PENDING ? null : fm)
                .reviewedAt(status == RequestStatus.PENDING ? null : OffsetDateTime.now().minusHours(1))
                .build();
        for (User m : members) {
            req.getMembers().add(AccessRequestMember.builder().accessRequest(req).user(m).build());
        }
        return accessRequestRepository.save(req);
    }

    Long dbVersion(Area area) {
        return jdbc.queryForObject("SELECT version FROM areas WHERE id = ?", Long.class, area.getId());
    }

    Map<String, Object> areaRow(Area area) {
        return jdbc.queryForMap("SELECT is_active, deleted_at, version FROM areas WHERE id = ?", area.getId());
    }

    Map<String, Object> apRow(AreaAssignedPersonnel ap) {
        return jdbc.queryForMap("SELECT revoked_at, revoked_by, revoke_reason FROM area_assigned_personnel WHERE id = ?", ap.getId());
    }

    Map<String, Object> requestRow(AccessRequest r) {
        return jdbc.queryForMap("SELECT status, cancel_source, cancel_reason FROM access_requests WHERE id = ?", r.getId());
    }

    Map<String, Object> visitRow(GuestVisit v) {
        return jdbc.queryForMap("SELECT status, revoked_by, revoke_reason, cancel_reason, version FROM guest_visits WHERE id = ?", v.getId());
    }

    List<Map<String, Object>> auditRows(String targetType, String action, String targetId) {
        return jdbc.queryForList("SELECT reason, correlation_id, changed_by, actor_type, actor_source FROM audit_logs "
                + "WHERE target_type = ? AND action = ? AND target_id = ?", targetType, action, targetId);
    }

    /** Thông báo theo loại (so chuỗi, không phụ thuộc enum Java). */
    List<Map<String, Object>> notifs(User u, String type) {
        return jdbc.queryForList("SELECT title, message, reference_id, reference_type FROM notifications WHERE recipient_id = ? AND type = ?",
                u.getId(), type);
    }

    // ------------------------------------------------------------------ HTTP

    Map<String, Object> body(String reason, Long version) {
        Map<String, Object> b = new LinkedHashMap<>();
        if (reason != null) b.put("reason", reason);
        if (version != null) b.put("version", version);
        return b;
    }

    MvcResult deactivate(User actor, Area area, String reason, Long version) throws Exception {
        return send(post("/api/areas/{id}/deactivate", area.getId()), actor, body(reason, version)).andReturn();
    }

    MvcResult preview(User actor, Area area) throws Exception {
        return send(get("/api/areas/{id}/dependencies", area.getId()), actor, null).andReturn();
    }

    String describe(MvcResult r) throws Exception {
        return "HTTP " + status(r) + " " + r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    void assertUntouched(Area area, Long version) {
        Map<String, Object> row = areaRow(area);
        assertEquals(true, row.get("is_active"), "Khu vực phải còn hoạt động");
        assertNull(row.get("deleted_at"));
        assertEquals(version, ((Number) row.get("version")).longValue(), "version không được đổi");
    }

    List<String> blockerCodes(JsonNode data) {
        List<String> codes = new ArrayList<>();
        data.path("blockers").forEach(b -> codes.add(b.path("errorCode").asText()));
        return codes;
    }

    // ================================================================== BR-AD-01

    @Test
    @DisplayName("BR-AD-01: ADMIN vô hiệu hoá -> is_active=false, deleted_at, version +1, audit AREA/DEACTIVATE kèm lý do")
    void ad01_admin_deactivates() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = dbVersion(area);

        MvcResult r = deactivate(admin, area, "  " + REASON + "  ", v0);

        assertEquals(200, status(r), describe(r));
        Map<String, Object> row = areaRow(area);
        assertEquals(false, row.get("is_active"));
        assertNotNull(row.get("deleted_at"));
        assertEquals(v0 + 1, ((Number) row.get("version")).longValue(), "version tăng đúng 1");
        List<Map<String, Object>> audits = auditRows("AREA", "DEACTIVATE", area.getId().toString());
        assertEquals(1, audits.size());
        assertEquals(REASON, audits.get(0).get("reason"), "lý do đã trim");
        assertEquals(admin.getId(), audits.get(0).get("changed_by"));
    }

    @Test
    @DisplayName("BR-AD-01: chỉ ADMIN — FM, GUARD, NORMAL_USER bị 403; khu vực không đổi")
    void ad01_onlyAdmin() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = dbVersion(area);
        for (User u : List.of(fm, guard, hostL2)) {
            assertEquals(403, status(deactivate(u, area, REASON, v0)), u.getRole().name());
        }
        assertUntouched(area, v0);
    }

    @Test
    @DisplayName("BR-AD-01: lý do trim 10–500 (050), thiếu version (044), lệch version (045) -> không đổi gì")
    void ad01_validation() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = dbVersion(area);

        MvcResult shortR = deactivate(admin, area, "   123456789   ", v0);
        assertEquals(400, status(shortR), describe(shortR));
        assertEquals("ERR_AREA_050", errorCode(shortR));
        MvcResult blank = deactivate(admin, area, "          ", v0);
        assertEquals("ERR_AREA_050", errorCode(blank));
        MvcResult none = deactivate(admin, area, null, v0);
        assertEquals("ERR_AREA_050", errorCode(none));
        MvcResult tooLong = deactivate(admin, area, "x".repeat(501), v0);
        assertEquals("ERR_AREA_050", errorCode(tooLong));

        MvcResult noVersion = deactivate(admin, area, REASON, null);
        assertEquals(400, status(noVersion), describe(noVersion));
        assertEquals("ERR_AREA_044", errorCode(noVersion));

        MvcResult stale = deactivate(admin, area, REASON, v0 + 7);
        assertEquals(409, status(stale), describe(stale));
        assertEquals("ERR_AREA_045", errorCode(stale));

        assertUntouched(area, v0);
        assertTrue(auditRows("AREA", "DEACTIVATE", area.getId().toString()).isEmpty());
    }

    @Test
    @DisplayName("BR-AD-01: lý do đúng 500 ký tự -> vẫn vô hiệu hoá được (lý do dẫn xuất cho AP/đơn/khách không vượt 500)")
    void ad01_reason500_derivedReasonsFit() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaAssignedPersonnel ap = newAp(area, hostL2, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(3));
        GuestVisit visit = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(area), "Khách A");
        String reason = "L".repeat(500);

        MvcResult r = deactivate(admin, area, reason, dbVersion(area));

        assertEquals(200, status(r), describe(r));
        String apReason = (String) apRow(ap).get("revoke_reason");
        assertTrue(apReason.startsWith(PREFIX) && apReason.length() <= 500, apReason.length() + "");
        String visitReason = (String) visitRow(visit).get("revoke_reason");
        assertTrue(visitReason.startsWith(PREFIX) && visitReason.length() <= 500, visitReason.length() + "");
    }

    @Test
    @DisplayName("BR-AD-01 + H3: DELETE /api/areas/{id} đã bỏ -> 405 METHOD_NOT_ALLOWED (ApiResponse), khu vực không đổi")
    void ad01_deleteEndpointRemoved() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = dbVersion(area);
        MvcResult r = send(delete("/api/areas/{id}", area.getId()), admin, null).andReturn();
        // H3: shared handler trả 405 theo định dạng ApiResponse (trước đây rơi vào handler chung -> 500)
        assertEquals(405, status(r), describe(r));
        assertEquals("METHOD_NOT_ALLOWED", errorCode(r), describe(r));
        assertEquals(405, json(r).path("httpCode").asInt(), describe(r));
        assertFalse(message(r).isBlank(), describe(r));
        assertUntouched(area, v0);
    }

    @Test
    @DisplayName("BR-AD-01: vô hiệu hoá khu vực đã vô hiệu hoá (version khớp) -> 409 ERR_AREA_053; khu vực không tồn tại -> 404 002")
    void ad01_alreadyDeactivated_andNotFound() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        assertEquals(200, status(deactivate(admin, area, REASON, dbVersion(area))));
        Long v1 = dbVersion(area);

        MvcResult again = deactivate(admin, area, REASON, v1);
        assertEquals(409, status(again), describe(again));
        assertEquals("ERR_AREA_053", errorCode(again));
        assertEquals(v1, dbVersion(area));

        MvcResult missing = send(post("/api/areas/{id}/deactivate", UUID.randomUUID()), admin, body(REASON, 0L)).andReturn();
        assertEquals(404, status(missing), describe(missing));
        assertEquals("ERR_AREA_002", errorCode(missing));
    }

    // ================================================================== BR-AD-02, 03, 03b, 06 (blocker)

    @Test
    @DisplayName("BR-AD-02: sự cố NEW + CLAIMED -> 409 ERR_AREA_051 nêu số lượng (2); sự cố đã xử lý không tính")
    void ad02_openIncidents_block() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        newIncident(area, IncidentStatus.NEW);
        newIncident(area, IncidentStatus.CLAIMED);
        newIncident(area, IncidentStatus.RESOLVED_DISMISSED);
        newIncident(area, IncidentStatus.RESOLVED_VERIFIED);
        Long v0 = dbVersion(area);

        MvcResult r = deactivate(admin, area, REASON, v0);

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_051", errorCode(r));
        assertTrue(message(r).contains("2"), message(r));
        assertUntouched(area, v0);
    }

    @Test
    @DisplayName("BR-AD-02: chỉ còn sự cố đã xử lý -> không chặn")
    void ad02_resolvedIncidents_doNotBlock() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        newIncident(area, IncidentStatus.RESOLVED_VERIFIED);
        MvcResult r = deactivate(admin, area, REASON, dbVersion(area));
        assertEquals(200, status(r), describe(r));
    }

    @Test
    @DisplayName("BR-AD-03: sự kiện đang bật -> 409 ERR_AREA_052; sự kiện đã quá open_until -> không chặn")
    void ad03_eventActive_blocks() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime now = OffsetDateTime.now();
        openEvent(area, now.minusMinutes(30), now.plusHours(1));
        Long v0 = dbVersion(area);

        MvcResult r = deactivate(admin, area, REASON, v0);
        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_052", errorCode(r));
        assertUntouched(area, v0);

        Area expired = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(expired, now.minusHours(2), now.minusMinutes(5));
        MvcResult ok = deactivate(admin, expired, REASON, dbVersion(expired));
        assertEquals(200, status(ok), describe(ok));
    }

    @Test
    @DisplayName("BR-AD-03b: còn lịch SCHEDULED -> 409 ERR_AREA_042 (giữ nguyên)")
    void ad03b_schedule_blocks() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime start = OffsetDateTime.now().plusDays(2);
        scheduleRepository.save(AreaEventSchedule.builder()
                .area(area).startAt(start).endAt(start.plusHours(2)).status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode("OTHER").reasonLabel("Khác").note("Lịch test step 6").createdBy(fm).build());
        Long v0 = dbVersion(area);

        MvcResult r = deactivate(admin, area, REASON, v0);

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_042", errorCode(r));
        assertUntouched(area, v0);
    }

    @Test
    @DisplayName("BR-AD-06: còn camera gán -> 409 ERR_AREA_009 liệt kê mã + tên, 'Gỡ camera khỏi khu vực trước'; không ghi bảng cameras")
    void ad06_camera_blocks() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Camera c1 = newCamera(area, "Cổng A step6");
        Camera c2 = newCamera(area, "Hành lang step6");
        Camera removed = newCamera(area, "Camera đã xoá step6");
        transactionTemplate.executeWithoutResult(tx -> {
            Camera c = cameraRepository.findById(removed.getId()).orElseThrow();
            c.setDeletedAt(OffsetDateTime.now());
            cameraRepository.save(c);
        });
        Map<String, Object> camBefore = jdbc.queryForMap("SELECT area_id, updated_at FROM cameras WHERE id = ?", c1.getId());
        Long v0 = dbVersion(area);

        MvcResult r = deactivate(admin, area, REASON, v0);

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_009", errorCode(r));
        String msg = message(r);
        assertTrue(msg.contains(c1.getCameraCode()) && msg.contains(c1.getName()), msg);
        assertTrue(msg.contains(c2.getCameraCode()) && msg.contains(c2.getName()), msg);
        assertFalse(msg.contains(removed.getCameraCode()), "camera đã xoá không tính: " + msg);
        assertTrue(msg.contains("2"), msg);
        assertTrue(msg.contains("Gỡ camera khỏi khu vực trước"), msg);
        assertEquals(camBefore, jdbc.queryForMap("SELECT area_id, updated_at FROM cameras WHERE id = ?", c1.getId()));
        assertUntouched(area, v0);
    }

    @Test
    @DisplayName("Thứ tự: còn blocker thì AP / đơn / lượt khách KHÔNG bị xử lý, không audit, không thông báo")
    void order_blockerFirst_nothingProcessed() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        newCamera(area, "Camera chặn step6");
        AreaAssignedPersonnel ap = newAp(area, hostL2, OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(3));
        AccessRequest pending = newRequest(area, hostL2, RequestStatus.PENDING, future(0), future(60));
        GuestVisit visit = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(area), "Khách A");
        Guest g = withPhoto(guestsOf(visit.getId()).get(0), future(120).plusHours(24));
        Long v0 = dbVersion(area);

        MvcResult r = deactivate(admin, area, REASON, v0);

        assertEquals(409, status(r), describe(r));
        assertUntouched(area, v0);
        assertNull(apRow(ap).get("revoked_at"));
        assertEquals("PENDING", requestRow(pending).get("status"));
        assertEquals("APPROVED", visitRow(visit).get("status"));
        assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus());
        verify(photoStorage, never()).removeObject(g.getPhotoObjectKey());
        assertTrue(notificationsOf(hostL2, NotificationType.GUEST_VISIT_REVOKED).isEmpty());
        assertTrue(notificationsOf(hostL2, NotificationType.REQUEST_SYSTEM_CANCELLED).isEmpty());
        assertTrue(notifs(hostL2, "ACCESS_PERMISSION_REVOKED").isEmpty());
    }

    // ================================================================== BR-AD-04

    @Test
    @DisplayName("BR-AD-04: AP ACTIVE + UPCOMING bị thu hồi (lý do, revoked_by ADMIN, audit từng AP cùng correlation); EXPIRED / đã thu hồi giữ nguyên")
    void ad04_revokesActiveAndUpcomingAp() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime now = OffsetDateTime.now();
        AreaAssignedPersonnel active = newAp(area, hostL2, now.minusDays(1), now.plusDays(3));
        AreaAssignedPersonnel openEnded = newAp(area, otherHost, now.minusDays(1), null);
        AreaAssignedPersonnel upcoming = newAp(area, fm2, now.plusDays(2), now.plusDays(5));
        AreaAssignedPersonnel expired = newAp(area, guard, now.minusDays(5), now.minusDays(1));
        AreaAssignedPersonnel revoked = newAp(area, hostL1, now.minusDays(1), now.plusDays(3));
        jdbc.update("UPDATE area_assigned_personnel SET revoked_at = now(), revoked_by = ?, revoke_reason = 'thu hồi trước' WHERE id = ?",
                fm.getId(), revoked.getId());

        MvcResult r = deactivate(admin, area, REASON, dbVersion(area));

        assertEquals(200, status(r), describe(r));
        UUID correlation = (UUID) auditRows("AREA", "DEACTIVATE", area.getId().toString()).get(0).get("correlation_id");
        for (AreaAssignedPersonnel ap : List.of(active, openEnded, upcoming)) {
            Map<String, Object> row = apRow(ap);
            assertNotNull(row.get("revoked_at"), "AP phải bị thu hồi");
            assertEquals(admin.getId(), row.get("revoked_by"));
            assertEquals(PREFIX + REASON, row.get("revoke_reason"));
            List<Map<String, Object>> audits = auditRows("AREA_ASSIGNMENT", "REVOKE", ap.getId().toString());
            assertEquals(1, audits.size(), "mỗi AP một audit");
            assertEquals(PREFIX + REASON, audits.get(0).get("reason"));
            assertEquals(correlation, audits.get(0).get("correlation_id"), "cùng correlation với thao tác vô hiệu hoá");
        }
        // H1 (BR-AD-04): mỗi người được gán của AP bị thu hồi nhận 1 ACCESS_PERMISSION_REVOKED (tên khu vực + lý do)
        for (Object[] pair : new Object[][]{{hostL2, active}, {otherHost, openEnded}, {fm2, upcoming}}) {
            User u = (User) pair[0];
            AreaAssignedPersonnel ap = (AreaAssignedPersonnel) pair[1];
            List<Map<String, Object>> n = notifs(u, "ACCESS_PERMISSION_REVOKED");
            assertEquals(1, n.size(), "thông báo thu hồi AP cho " + u.getUserCode());
            String msg = (String) n.get(0).get("message");
            assertTrue(msg.contains(area.getName()) && msg.contains(REASON), msg);
            assertEquals(ap.getId(), n.get(0).get("reference_id"));
            assertEquals("AREA_ASSIGNMENT", n.get(0).get("reference_type"));
        }
        assertTrue(notifs(guard, "ACCESS_PERMISSION_REVOKED").isEmpty(), "AP hết hạn không bị thu hồi -> không báo");
        assertTrue(notifs(hostL1, "ACCESS_PERMISSION_REVOKED").isEmpty(), "AP đã thu hồi trước -> không báo");
        assertNull(apRow(expired).get("revoked_at"), "AP hết hạn giữ nguyên");
        assertEquals("thu hồi trước", apRow(revoked).get("revoke_reason"), "AP đã thu hồi giữ nguyên");
        assertTrue(auditRows("AREA_ASSIGNMENT", "REVOKE", expired.getId().toString()).isEmpty());
    }

    // ================================================================== BR-AD-05

    @Test
    @DisplayName("BR-AD-05: đơn PENDING + APPROVED chưa kết thúc -> CANCELLED SYSTEM, audit, thông báo người gửi + thành viên; đơn khác giữ nguyên")
    void ad05_cancelsRequestsBySystem() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Area other = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime now = OffsetDateTime.now();
        AccessRequest pending = newRequest(area, hostL2, RequestStatus.PENDING, now.plusHours(2), now.plusHours(3));
        AccessRequest approvedGroup = newRequest(area, otherHost, RequestStatus.APPROVED, now.minusHours(1), now.plusHours(2), hostL1);
        AccessRequest approvedEnded = newRequest(area, hostL2, RequestStatus.APPROVED, now.minusHours(3), now.minusHours(1));
        AccessRequest rejected = newRequest(area, hostL2, RequestStatus.REJECTED, now.plusHours(4), now.plusHours(5));
        AccessRequest otherArea = newRequest(other, hostL2, RequestStatus.PENDING, now.plusHours(2), now.plusHours(3));

        MvcResult r = deactivate(admin, area, REASON, dbVersion(area));

        assertEquals(200, status(r), describe(r));
        UUID correlation = (UUID) auditRows("AREA", "DEACTIVATE", area.getId().toString()).get(0).get("correlation_id");
        for (AccessRequest req : List.of(pending, approvedGroup)) {
            Map<String, Object> row = requestRow(req);
            assertEquals("CANCELLED", row.get("status"));
            assertEquals("SYSTEM", row.get("cancel_source"));
            assertEquals(PREFIX + REASON, row.get("cancel_reason"));
            List<Map<String, Object>> audits = auditRows("ACCESS_REQUEST", "CANCEL", req.getId().toString());
            assertEquals(1, audits.size());
            assertEquals("SYSTEM", audits.get(0).get("actor_type"));
            assertEquals(correlation, audits.get(0).get("correlation_id"));
        }
        assertEquals("APPROVED", requestRow(approvedEnded).get("status"), "đơn đã kết thúc giữ nguyên");
        assertEquals("REJECTED", requestRow(rejected).get("status"));
        assertEquals("PENDING", requestRow(otherArea).get("status"), "đơn khu vực khác giữ nguyên");

        assertEquals(1, notificationsOf(hostL2, NotificationType.REQUEST_SYSTEM_CANCELLED).size(), "người gửi đơn PENDING");
        assertEquals(1, notificationsOf(otherHost, NotificationType.REQUEST_SYSTEM_CANCELLED).size(), "người gửi đơn nhóm");
        assertEquals(1, notificationsOf(hostL1, NotificationType.REQUEST_SYSTEM_CANCELLED).size(), "thành viên đơn nhóm");
    }

    // ================================================================== BR-AD-09

    @Test
    @DisplayName("BR-AD-09: lượt khách PENDING -> CANCELLED; APPROVED -> REVOKED (revoked_by ADMIN), ảnh + embedding xoá sau commit, báo host")
    void ad09_guestVisits() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Area other = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        GuestVisit pending = newVisit(otherHost, GuestVisitStatus.PENDING, future(0), future(60), List.of(area), "Khách chờ");
        GuestVisit approved = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(area, other), "Khách duyệt");
        GuestVisit otherVisit = newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(other), "Khách khu khác");
        GuestVisit cancelled = newVisit(hostL2, GuestVisitStatus.CANCELLED, future(0), future(120), List.of(area), "Khách đã huỷ");
        Guest g = withPhoto(guestsOf(approved.getId()).get(0), future(120).plusHours(24));
        String key = g.getPhotoObjectKey();

        MvcResult r = deactivate(admin, area, REASON, dbVersion(area));

        assertEquals(200, status(r), describe(r));
        Map<String, Object> p = visitRow(pending);
        assertEquals("CANCELLED", p.get("status"));
        assertEquals(PREFIX + REASON, p.get("cancel_reason"));
        Map<String, Object> a = visitRow(approved);
        assertEquals("REVOKED", a.get("status"));
        assertEquals(admin.getId(), a.get("revoked_by"));
        assertEquals(PREFIX + REASON, a.get("revoke_reason"));
        assertEquals(GuestBiometricStatus.DELETED, guest(g.getId()).getBiometricStatus());
        assertFalse(embeddingRepository.existsById(g.getId()), "embedding phải bị xoá");
        verify(photoStorage).removeObject(key);
        assertNull(guest(g.getId()).getPendingDeleteObjectKey(), "xoá sau commit thành công -> bỏ đánh dấu");
        assertEquals(1, notificationsOf(hostL2, NotificationType.GUEST_VISIT_REVOKED).size(), "host nhận thông báo thu hồi");
        // H2 (BR-AD-09): host lượt PENDING bị huỷ nhận GUEST_VISIT_CANCELLED_BY_SYSTEM (tên khu vực + lý do)
        List<Map<String, Object>> cancelNotifs = notifs(otherHost, "GUEST_VISIT_CANCELLED_BY_SYSTEM");
        assertEquals(1, cancelNotifs.size(), "host lượt PENDING nhận thông báo huỷ");
        String cancelMsg = (String) cancelNotifs.get(0).get("message");
        assertTrue(cancelMsg.contains(area.getName()) && cancelMsg.contains(REASON), cancelMsg);
        assertEquals(pending.getId(), cancelNotifs.get(0).get("reference_id"));
        assertEquals("GUEST_VISIT", cancelNotifs.get(0).get("reference_type"));
        assertTrue(notifs(hostL2, "GUEST_VISIT_CANCELLED_BY_SYSTEM").isEmpty(), "lượt APPROVED bị thu hồi, không phải huỷ");
        assertEquals(1, audits(approved.getId().toString(), AuditTargetType.GUEST_VISIT, AuditAction.REVOKE).size());
        assertEquals(1, audits(pending.getId().toString(), AuditTargetType.GUEST_VISIT, AuditAction.CANCEL).size());

        assertEquals("APPROVED", visitRow(otherVisit).get("status"), "lượt chỉ ở khu vực khác giữ nguyên");
        assertEquals("CANCELLED", visitRow(cancelled).get("status"));
        assertTrue(audits(cancelled.getId().toString(), AuditTargetType.GUEST_VISIT, AuditAction.CANCEL).isEmpty());
    }

    // ================================================================== BR-AD-08

    @Test
    @DisplayName("BR-AD-08: xem trước chỉ đọc — blockers (009, 051, 052, 042) + số AP / đơn / lượt khách, canDeactivate=false; không còn 010")
    void ad08_preview_withBlockers() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime now = OffsetDateTime.now();
        newCamera(area, "Camera xem trước");
        newIncident(area, IncidentStatus.NEW);
        openEvent(area, now.minusMinutes(10), now.plusHours(1));
        OffsetDateTime start = now.plusDays(2);
        scheduleRepository.save(AreaEventSchedule.builder()
                .area(area).startAt(start).endAt(start.plusHours(2)).status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode("OTHER").reasonLabel("Khác").note("Lịch test step 6").createdBy(fm).build());
        newAp(area, hostL2, now.minusDays(1), now.plusDays(3));
        newAp(area, otherHost, now.plusDays(1), now.plusDays(3));
        newRequest(area, hostL2, RequestStatus.PENDING, now.plusHours(2), now.plusHours(3));
        newVisit(hostL2, GuestVisitStatus.PENDING, future(0), future(60), List.of(area), "Khách chờ");
        newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(60), List.of(area), "Khách duyệt 1");
        newVisit(otherHost, GuestVisitStatus.APPROVED, future(0), future(60), List.of(area), "Khách duyệt 2");
        Long v0 = dbVersion(area);

        MvcResult r = preview(admin, area);

        assertEquals(200, status(r), describe(r));
        JsonNode d = json(r).path("data");
        assertFalse(d.path("canDeactivate").asBoolean(true));
        assertEquals(List.of("ERR_AREA_009", "ERR_AREA_051", "ERR_AREA_052", "ERR_AREA_042"), blockerCodes(d));
        assertEquals(2, d.path("apToRevoke").asInt());
        assertEquals(1, d.path("requestsToCancel").asInt());
        assertEquals(1, d.path("guestVisitsToCancel").asInt());
        assertEquals(2, d.path("guestVisitsToRevoke").asInt());
        assertEquals(v0, d.path("version").asLong());
        assertUntouched(area, v0);

        assertEquals(403, status(preview(fm, area)));
    }

    @Test
    @DisplayName("BR-AD-08: không blocker -> canDeactivate=true, blockers rỗng; deactivate cùng đánh giá cho cùng số liệu")
    void ad08_preview_matchesDeactivate() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime now = OffsetDateTime.now();
        AreaAssignedPersonnel ap = newAp(area, hostL2, now.minusDays(1), now.plusDays(3));
        AccessRequest req = newRequest(area, hostL2, RequestStatus.APPROVED, now.minusHours(1), now.plusHours(1));

        JsonNode d = json(preview(admin, area)).path("data");
        assertTrue(d.path("canDeactivate").asBoolean(false));
        assertEquals(0, d.path("blockers").size());
        assertEquals(1, d.path("apToRevoke").asInt());
        assertEquals(1, d.path("requestsToCancel").asInt());

        assertEquals(200, status(deactivate(admin, area, REASON, d.path("version").asLong())));
        assertNotNull(apRow(ap).get("revoked_at"));
        assertEquals("CANCELLED", requestRow(req).get("status"));
    }

    // ================================================================== đường ghi khác

    @Test
    @DisplayName("PUT /{id}/cameras trên khu vực đã vô hiệu hoá -> 400 ERR_AREA_017, không ghi cameras")
    void cameras_onDeactivatedArea_rejected() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        assertEquals(200, status(deactivate(admin, area, REASON, dbVersion(area))));
        Area other = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Camera cam = newCamera(other, "Camera khu khác");

        MvcResult r = send(put("/api/areas/{id}/cameras", area.getId()), admin, Map.of("cameraIds", List.of(cam.getId()))).andReturn();

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_017", errorCode(r));
        assertEquals(other.getId(), jdbc.queryForObject("SELECT area_id FROM cameras WHERE id = ?", UUID.class, cam.getId()));
    }

    @Test
    @DisplayName("Sau vô hiệu hoá: access-rules, event-mode, lịch, PUT, geometry, tạo đơn, tạo lượt khách vào khu vực đều bị chặn")
    void otherWritePaths_blockedAfterDeactivation() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        assertEquals(200, status(deactivate(admin, area, REASON, dbVersion(area))));
        Long v1 = dbVersion(area);
        OffsetDateTime now = OffsetDateTime.now();

        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("areaAccessLevel", 3);
        rules.put("explicitAuthorizationRequired", true);
        rules.put("reason", "Điều chỉnh quy tắc sau khi vô hiệu hoá");
        rules.put("version", v1);
        assertBlocked(send(patch("/api/areas/{id}/access-rules", area.getId()), admin, rules).andReturn(), "access-rules", 400, "ERR_AREA_017");

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("action", "ENABLE");
        event.put("openUntil", now.plusHours(2).toString());
        event.put("reasonCode", "OTHER");
        event.put("note", "Hội thảo sau khi vô hiệu hoá khu vực");
        event.put("version", v1);
        assertBlocked(send(patch("/api/areas/{id}/event-mode", area.getId()), fm, event).andReturn(), "event-mode", 400, "ERR_AREA_017");

        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("startAt", now.plusDays(2).toString());
        schedule.put("endAt", now.plusDays(2).plusHours(2).toString());
        schedule.put("reasonCode", "OTHER");
        schedule.put("note", "Lịch sau khi vô hiệu hoá khu vực");
        assertBlocked(send(post("/api/areas/{id}/event-schedules", area.getId()), fm, schedule).andReturn(), "event-schedules", 400, "ERR_AREA_017");

        Map<String, Object> putBody = new LinkedHashMap<>();
        putBody.put("name", area.getName() + " moi");
        putBody.put("areaLevel", "INTERNAL_CONFIDENTIAL");
        putBody.put("floorId", floor.getId());
        putBody.put("centerLatitude", LAT);
        putBody.put("centerLongitude", LNG);
        putBody.put("version", v1);
        assertBlocked(send(put("/api/areas/{id}", area.getId()), admin, putBody).andReturn(), "PUT", 404, "ERR_AREA_002");

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("areaId", area.getId());
        request.put("purpose", "Vào khu vực đã vô hiệu hoá để thử");
        request.put("startTime", now.plusHours(2).toString());
        request.put("endTime", now.plusHours(3).toString());
        assertBlocked(send(post("/api/access-requests/individual"), hostL2, request).andReturn(), "tạo đơn", 404, null);

        assertBlocked(send(post("/api/guest-visits"), hostL2,
                createBody(future(0), future(60), List.of(area), List.of(guestInput("Khách thử", null)))).andReturn(), "tạo lượt khách", 400, "ERR_GUEST_008");

        assertEquals(v1, dbVersion(area), "không đường ghi nào được đổi khu vực");
        assertEquals(false, areaRow(area).get("is_active"));
    }

    void assertBlocked(MvcResult r, String what, int expectedStatus, String expectedCode) throws Exception {
        assertEquals(expectedStatus, status(r), what + " phải bị chặn: " + describe(r));
        if (expectedCode != null) {
            assertEquals(expectedCode, errorCode(r), what + ": " + describe(r));
        } else {
            assertTrue(message(r).contains("vô hiệu hoá"), what + ": " + describe(r));
        }
    }
}
