package com.fa26se040.icss.service;

import com.fa26se040.icss.context.AuditContext;
import com.fa26se040.icss.dto.area.EventScheduleCancelRequest;
import com.fa26se040.icss.dto.area.EventScheduleRequest;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaEventSchedule;
import com.fa26se040.icss.entity.AreaEventSession;
import com.fa26se040.icss.entity.AuditLog;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.ReasonCatalog;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaEventScheduleStatus;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Khôi phục các assert thông báo của module khu vực đã mất khi commit 7f3cd69 (04/10/2026) xoá các test Step5b*.
 * Nguồn: 7f3cd69^ — Step5bAreaTypeChangeTest, Step5bAreaTypeChangeRollbackTest, Step5bEventActionTest,
 * Step5bVersionTest, Step5bScheduleReasonTest. Viết lại theo fixture hiện tại (Step5bTestSupport, không geometry).
 * Mỗi method ghi BR + method gốc. @SpyBean AuditService chỉ ép lỗi trong TC-11b, các method khác gọi hàm thật.
 */
class AreaNotificationRulesRestoredIntegrationTest extends Step5bTestSupport {

    @SpyBean
    private AuditService auditService;

    @BeforeEach
    void useShortFloorName() {
        // Tầng của Step5bTestSupport ("Tầng test 5b <suffix>", 21 ký tự) vượt giới hạn 20 ký tự khi PUT khu vực
        floor = floorRepository.save(Floor.builder()
                .name("NR " + suffix)
                .floorOrder(1)
                .building(building)
                .isActive(true)
                .build());
    }

    @AfterEach
    void resetSpy() {
        Mockito.reset(auditService);
    }

    // ------------------------------------------------------------------ tiện ích (từ các class gốc)

    private Set<UUID> referenceIds(User user, NotificationType type) {
        return notificationsOf(user, type).stream().map(Notification::getReferenceId).collect(Collectors.toSet());
    }

    private void assertSystemCancelled(AccessRequest req, String expectedReason) {
        Map<String, Object> row = cancelColumns(req);
        assertEquals("CANCELLED", row.get("status"), "Đơn " + req.getId() + " phải bị huỷ");
        assertEquals("SYSTEM", row.get("cancel_source"), "cancel_source của đơn bị hệ thống huỷ");
        assertNull(row.get("cancelled_by"), "cancelled_by phải NULL khi hệ thống huỷ");
        Object reason = row.get("cancel_reason");
        assertNotNull(reason, "cancel_reason phải có");
        assertFalse(reason.toString().isBlank(), "cancel_reason không được rỗng");
        if (expectedReason != null) {
            assertEquals(expectedReason, reason.toString());
        }
    }

    private void assertBlockedWithoutSideEffects(Area area, AreaLevel target, AccessRequest request, String expectedCode) throws Exception {
        RequestStatus requestBefore = requestStatus(request);
        Long v0 = apiVersion(area);
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, target, TYPE_CHANGE_REASON, v0);

        assertEquals(409, status(r), describe(r));
        assertEquals(expectedCode, errorCode(r));
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, reload(area).getAreaLevel());
        assertEquals(requestBefore, requestStatus(request), "Bị chặn thì đơn không đổi");
        assertEquals(auditBefore, auditCountForArea(area), "Bị chặn thì không ghi audit");
        assertTrue(auditsForTarget(request.getId().toString()).isEmpty(), "Bị chặn thì không có audit huỷ đơn");
        assertEquals(0, countNotifications(fm, fm2, userL2), "Bị chặn thì không có thông báo");
        assertNotNull(v0, "AreaResponse phải trả version (BR-TC-13)");
        assertEquals(v0, apiVersion(area), "Bị chặn thì version không đổi");
    }

    private List<AreaEventSession> openSessions(Area area) {
        return sessionRepository.findAll().stream()
                .filter(s -> s.getArea().getId().equals(area.getId()) && s.getActualEnd() == null)
                .toList();
    }

    private String code(String prefix) {
        return prefix + "_" + suffix.toUpperCase();
    }

    private long schedulesOf(Area area) {
        return eventScheduleRepository.findAll().stream().filter(s -> s.getArea().getId().equals(area.getId())).count();
    }

    // ================================================================== đổi loại khu vực (Step5bAreaTypeChangeTest)

    /** BR-TC-02 — nguồn: Step5bAreaTypeChangeTest#tc02a_BR_TC_02_changeTypeWithoutReason_badRequest */
    @Test
    @DisplayName("TC-02a (BR-TC-02): đổi loại thiếu reason -> 400 ERR_AREA_050, không đổi gì, không audit, không AREA_TYPE_CHANGED")
    void tc02a_changeTypeWithoutReason_noNotification() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, AreaLevel.HIGHLY_CONFIDENTIAL, null, v0);

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_050", errorCode(r));
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, reload(area).getAreaLevel());
        assertEquals(auditBefore, auditCountForArea(area));
        assertEquals(0, notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).size());
    }

    /** BR-TC-01 — nguồn: Step5bAreaTypeChangeTest#tc01_BR_TC_01_nonAdminChangeType_forbidden */
    @Test
    @DisplayName("TC-01 (BR-TC-01): FM / GUARD / NORMAL_USER đổi loại khu vực -> 403, không đổi gì, không audit, không thông báo")
    void tc01_nonAdminChangeType_forbidden_noNotification() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long auditBefore = auditCountForArea(area);

        for (User actor : List.of(fm, guard, userL2)) {
            MvcResult r = changeType(actor, area, AreaLevel.HIGHLY_CONFIDENTIAL, TYPE_CHANGE_REASON, 0L);
            assertEquals(403, status(r), actor.getRole() + ": " + describe(r));
        }

        Area after = reload(area);
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, after.getAreaLevel());
        assertEquals(2, after.getAreaAccessLevel());
        assertEquals(auditBefore, auditCountForArea(area), "Bị 403 thì không được ghi audit");
        assertEquals(0, countNotifications(fm, fm2), "Bị 403 thì không có thông báo");
    }

    /** BR-TC-02 — nguồn: Step5bAreaTypeChangeTest#tc02b_BR_TC_02_changeTypeReasonTooShort_badRequest */
    @Test
    @DisplayName("TC-02b (BR-TC-02): đổi loại với reason 5 ký tự -> 400 ERR_AREA_050, không đổi gì, không AREA_TYPE_CHANGED")
    void tc02b_changeTypeReasonTooShort_noNotification() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, AreaLevel.HIGHLY_CONFIDENTIAL, "abcde", v0);

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_050", errorCode(r));
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, reload(area).getAreaLevel());
        assertEquals(auditBefore, auditCountForArea(area));
        assertEquals(0, notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).size());
    }

    /** BR-TC-05 — nguồn: Step5bAreaTypeChangeTest#tc05a_BR_TC_05_eventActive_toHighly_conflict */
    @Test
    @DisplayName("TC-05a (BR-TC-05): đang mở sự kiện -> đổi sang HIGHLY -> 409 ERR_AREA_048, không đổi gì, không AREA_TYPE_CHANGED")
    void tc05a_eventActive_toHighly_noNotification() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusMinutes(10), now.plusHours(2));
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, AreaLevel.HIGHLY_CONFIDENTIAL, TYPE_CHANGE_REASON, apiVersion(area));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_048", errorCode(r));
        Area after = reload(area);
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, after.getAreaLevel());
        assertEquals(2, after.getAreaAccessLevel());
        assertTrue(after.isEventActive(OffsetDateTime.now()), "Sự kiện vẫn phải đang mở");
        assertTrue(sessionRepository.findByAreaIdAndActualEndIsNull(area.getId()).isPresent(), "Phiên sự kiện vẫn mở");
        assertEquals(auditBefore, auditCountForArea(area));
        assertEquals(0, notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).size());
    }

    /** BR-TC-05 — nguồn: Step5bAreaTypeChangeTest#tc05b_BR_TC_05_eventActive_toPublic_conflict */
    @Test
    @DisplayName("TC-05b (BR-TC-05): đang mở sự kiện -> đổi sang PUBLIC -> 409 ERR_AREA_048, không đổi gì, không AREA_TYPE_CHANGED")
    void tc05b_eventActive_toPublic_noNotification() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusMinutes(10), now.plusHours(2));
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, AreaLevel.PUBLIC, TYPE_CHANGE_REASON, apiVersion(area));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_048", errorCode(r));
        Area after = reload(area);
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, after.getAreaLevel());
        assertTrue(after.isEventActive(OffsetDateTime.now()), "Sự kiện vẫn phải đang mở");
        assertEquals(auditBefore, auditCountForArea(area));
        assertEquals(0, notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).size());
    }

    /** BR-TC-06 — nguồn: Step5bAreaTypeChangeTest#tc06_BR_TC_06_pendingSchedule_toHighlyOrPublic_conflictListsSchedules */
    @Test
    @DisplayName("TC-06 (BR-TC-06): còn lịch SCHEDULED -> đổi sang HIGHLY / PUBLIC -> 409 ERR_AREA_042, liệt kê lịch, không AREA_TYPE_CHANGED")
    void tc06_pendingSchedule_conflict_noNotification() throws Exception {
        OffsetDateTime start = OffsetDateTime.now().plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        newSchedule(area, start, start.plusHours(2), "SEMINAR", "Hội thảo/sự kiện chuyên môn");
        long auditBefore = auditCountForArea(area);
        String startText = java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm")
                .withZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).format(start);

        for (AreaLevel target : List.of(AreaLevel.HIGHLY_CONFIDENTIAL, AreaLevel.PUBLIC)) {
            MvcResult r = changeType(admin, area, target, TYPE_CHANGE_REASON, apiVersion(area));
            assertEquals(409, status(r), target + ": " + describe(r));
            assertEquals("ERR_AREA_042", errorCode(r), target.name());
            assertTrue(message(r).contains(startText), "Thông điệp phải liệt kê lịch " + startText + ": " + message(r));
        }

        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, reload(area).getAreaLevel());
        assertEquals(auditBefore, auditCountForArea(area));
        assertEquals(0, notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).size());
    }

    /** BR-TC-07 — nguồn: Step5bAreaTypeChangeTest#tc07_BR_TC_07_toPublic_withActiveAp_conflictMentionsCount */
    @Test
    @DisplayName("TC-07 (BR-TC-07): sang PUBLIC khi còn AP hiệu lực -> 409 ERR_AREA_049 nêu số AP, không AREA_TYPE_CHANGED")
    void tc07_toPublicWithActiveAp_noNotification() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        newActiveAp(area, userL2);
        newActiveAp(area, userL3);
        newActiveAp(area, userL3b);
        com.fa26se040.icss.entity.AreaAssignedPersonnel revoked = newActiveAp(area, userL1);
        revoked.setRevokedAt(OffsetDateTime.now().minusMinutes(5));
        revoked.setRevokeReason("Thu hồi dữ liệu test 5b");
        assignedPersonnelRepository.save(revoked);
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, AreaLevel.PUBLIC, TYPE_CHANGE_REASON, apiVersion(area));

        assertEquals(409, status(r), describe(r));
        assertEquals("ERR_AREA_049", errorCode(r));
        assertTrue(message(r).contains("3"), "Thông điệp phải nêu số AP còn hiệu lực (3): " + message(r));
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, reload(area).getAreaLevel());
        assertEquals(auditBefore, auditCountForArea(area));
        assertEquals(0, notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).size());
    }

    /** BR-TC-11, BR-TC-06 — nguồn: Step5bAreaTypeChangeTest#tc11_BR_TC_11_blockedBySchedule_noSideEffects */
    @Test
    @DisplayName("TC-11 (BR-TC-11) + TC-06: bị chặn vì còn lịch chờ -> không audit, không thông báo, version không đổi")
    void tc11_blockedBySchedule_noNotification() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        newSchedule(area, now.plusDays(1), now.plusDays(1).plusHours(2), "SEMINAR", "Hội thảo/sự kiện chuyên môn");
        AccessRequest approved = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(3), now.plusHours(4));
        assertBlockedWithoutSideEffects(area, AreaLevel.HIGHLY_CONFIDENTIAL, approved, "ERR_AREA_042");
    }

    /** BR-TC-08, BR-TC-16 — nguồn: Step5bAreaTypeChangeTest#tc08_BR_TC_08_approvedNotEnded_systemCancelled */
    @Test
    @DisplayName("TC-08 (BR-TC-08, BR-TC-16): đơn APPROVED chưa kết thúc không thoả loại mới -> hệ thống huỷ, audit SYSTEM cùng correlation, báo người tạo + thành viên")
    void tc08_approvedNotEnded_systemCancelled_notifiesParticipants() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);

        // Bị huỷ: người tạo cấp 2 < cấp mới 3
        AccessRequest r1 = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(1), now.plusHours(3));
        // Bị huỷ: đơn nhóm đang diễn ra, thành viên cấp 2 thấp hơn cấp mới
        AccessRequest r2 = newRequest(area, userL3, RequestType.GROUP, RequestStatus.APPROVED,
                now.minusHours(1), now.plusMinutes(50), userL2);
        // Bị huỷ: đơn nhóm vào HIGHLY khi config không cho đơn nhóm (mọi người đủ cấp)
        AccessRequest r5 = newRequest(area, userL3, RequestType.GROUP, RequestStatus.APPROVED,
                now.plusHours(4), now.plusHours(6), userL3b);
        // Giữ nguyên: cá nhân đủ cấp
        AccessRequest r3 = newRequest(area, userL3, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(7), now.plusHours(9));
        // Giữ nguyên: đã kết thúc
        AccessRequest r4 = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.minusHours(5), now.minusHours(3));

        MvcResult r = changeType(admin, area, AreaLevel.HIGHLY_CONFIDENTIAL, TYPE_CHANGE_REASON, apiVersion(area));
        assertEquals(200, status(r), describe(r));

        assertEquals(RequestStatus.CANCELLED, requestStatus(r1), "r1 (cá nhân cấp 2) phải bị huỷ");
        assertEquals(RequestStatus.CANCELLED, requestStatus(r2), "r2 (nhóm có thành viên cấp 2, đang diễn ra) phải bị huỷ");
        assertEquals(RequestStatus.CANCELLED, requestStatus(r5), "r5 (đơn nhóm vào HIGHLY khi config cấm) phải bị huỷ");
        assertEquals(RequestStatus.APPROVED, requestStatus(r3), "r3 đủ cấp phải giữ APPROVED");
        assertEquals(RequestStatus.APPROVED, requestStatus(r4), "r4 đã kết thúc phải giữ nguyên");

        List<AuditLog> changeType = auditsWithAction(area, "CHANGE_TYPE");
        assertEquals(1, changeType.size());
        UUID correlationId = changeType.get(0).getCorrelationId();
        assertNotNull(correlationId, "Audit CHANGE_TYPE phải có correlation_id");
        for (AccessRequest cancelled : List.of(r1, r2, r5)) {
            List<AuditLog> sys = auditsForTarget(cancelled.getId().toString()).stream()
                    .filter(a -> "SYSTEM".equals(a.getActorType()) && AREA_TYPE_CHANGE_SOURCE.equals(a.getActorSource()))
                    .toList();
            assertEquals(1, sys.size(), "Đơn " + cancelled.getId() + " phải có 1 audit actor SYSTEM AREA_TYPE_CHANGE");
            assertEquals(AuditTargetType.ACCESS_REQUEST, sys.get(0).getTargetType());
            assertEquals("CANCEL", sys.get(0).getAction().name(), "Dùng action huỷ đơn hiện có");
            assertEquals(correlationId, sys.get(0).getCorrelationId(), "Audit huỷ đơn phải cùng correlation với thao tác ADMIN");
        }
        assertTrue(auditsForTarget(r3.getId().toString()).isEmpty(), "Đơn không bị huỷ thì không có audit");

        assertEquals(Set.of(r1.getId(), r2.getId()), referenceIds(userL2, NotificationType.REQUEST_SYSTEM_CANCELLED));
        assertEquals(Set.of(r2.getId(), r5.getId()), referenceIds(userL3, NotificationType.REQUEST_SYSTEM_CANCELLED));
        assertEquals(Set.of(r5.getId()), referenceIds(userL3b, NotificationType.REQUEST_SYSTEM_CANCELLED));

        for (AccessRequest cancelled : List.of(r1, r2, r5)) {
            assertSystemCancelled(cancelled, null);
        }
    }

    /** BR-TC-09 — nguồn: Step5bAreaTypeChangeTest#tc09_BR_TC_09_pendingKept_fmApproveBlockedByNewLevel */
    @Test
    @DisplayName("TC-09 (BR-TC-09): đơn PENDING không bị huỷ khi đổi loại, không REQUEST_SYSTEM_CANCELLED; FM duyệt sau đó bị chặn, không REQUEST_APPROVED")
    void tc09_pendingKept_noCancelNotification_fmApproveBlocked() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AccessRequest pending = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.PENDING,
                now.plusHours(2), now.plusHours(4));

        MvcResult r = changeType(admin, area, AreaLevel.HIGHLY_CONFIDENTIAL, TYPE_CHANGE_REASON, apiVersion(area));
        assertEquals(200, status(r), describe(r));
        assertEquals(RequestStatus.PENDING, requestStatus(pending), "Đơn PENDING không bị huỷ khi loại mới khác PUBLIC");
        assertTrue(notificationsOf(userL2, NotificationType.REQUEST_SYSTEM_CANCELLED).isEmpty());

        MvcResult review = send(patch("/api/access-requests/{id}/review", pending.getId()), fm,
                Map.of("status", "APPROVED")).andReturn();
        assertEquals(400, status(review), "FM duyệt phải bị chặn theo cấp mới: " + describe(review));
        assertEquals(RequestStatus.PENDING, requestStatus(pending));
        assertTrue(notificationsOf(userL2, NotificationType.REQUEST_APPROVED).isEmpty());

        Map<String, Object> row = cancelColumns(pending);
        assertNull(row.get("cancel_source"));
        assertNull(row.get("cancel_reason"));
    }

    /** BR-TC-10 — nguồn: Step5bAreaTypeChangeTest#tc10_BR_TC_10_activeFmsNotified_inactiveFmNot */
    @Test
    @DisplayName("TC-10 (BR-TC-10): đổi loại thành công -> mọi FM đang hoạt động nhận AREA_TYPE_CHANGED, FM bị vô hiệu hoá và GUARD không nhận")
    void tc10_activeFmsNotified_inactiveFmNot() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);

        MvcResult r = changeType(admin, area, AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, TYPE_CHANGE_REASON, apiVersion(area));
        assertEquals(200, status(r), describe(r));

        for (User activeFm : List.of(fm, fm2)) {
            List<Notification> list = notificationsOf(activeFm, NotificationType.AREA_TYPE_CHANGED).stream()
                    .filter(n -> area.getId().equals(n.getReferenceId()))
                    .toList();
            assertEquals(1, list.size(), "FM " + activeFm.getEmail() + " phải nhận đúng 1 thông báo AREA_TYPE_CHANGED");
            assertEquals("Khu vực " + area.getName() + ": Bảo mật nội bộ → Liên hệ trước. Lý do: " + TYPE_CHANGE_REASON
                    + ". Số đơn bị huỷ: 0.", list.get(0).getMessage());
        }
        assertTrue(notificationsOf(fmInactive, NotificationType.AREA_TYPE_CHANGED).isEmpty(), "FM bị vô hiệu hoá không nhận");
        assertTrue(notificationsOf(guard, NotificationType.AREA_TYPE_CHANGED).isEmpty(), "Chỉ FM nhận AREA_TYPE_CHANGED");
    }

    /** BR-TC-11, BR-TC-07 — nguồn: Step5bAreaTypeChangeTest#tc11_BR_TC_11_blockedByActiveAp_noSideEffects */
    @Test
    @DisplayName("TC-11 (BR-TC-11) + TC-07: bị chặn vì còn AP hiệu lực -> không audit, không thông báo, version không đổi")
    void tc11_blockedByActiveAp_noNotification() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        newActiveAp(area, userL3);
        AccessRequest pending = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.PENDING,
                now.plusHours(3), now.plusHours(4));
        assertBlockedWithoutSideEffects(area, AreaLevel.PUBLIC, pending, "ERR_AREA_049");
    }

    /** BR-TC-11, BR-TC-05 — nguồn: Step5bAreaTypeChangeTest#tc11_BR_TC_11_blockedByEvent_noSideEffects */
    @Test
    @DisplayName("TC-11 (BR-TC-11) + TC-05: bị chặn vì sự kiện đang mở -> không audit, không thông báo, version không đổi")
    void tc11_blockedByEvent_noNotification() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusMinutes(10), now.plusHours(2));
        AccessRequest approved = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(3), now.plusHours(4));
        assertBlockedWithoutSideEffects(area, AreaLevel.HIGHLY_CONFIDENTIAL, approved, "ERR_AREA_048");
    }

    /** BR-TC-12 — nguồn: Step5bAreaTypeChangeTest#tc12a_BR_TC_12_renameOnly_noReasonNoChangeType */
    @Test
    @DisplayName("TC-12a (BR-TC-12): chỉ đổi tên -> không cần reason, không CHANGE_TYPE, không AREA_TYPE_CHANGED, version tăng 1")
    void tc12a_renameOnly_noTypeChangeNotification() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);
        String newName = area.getName() + " moi";

        MvcResult r = putArea(admin, area, newName, AreaLevel.INTERNAL_CONFIDENTIAL, null, v0);

        assertEquals(200, status(r), describe(r));
        assertEquals(newName, reload(area).getName());
        assertEquals(0, auditsWithAction(area, "CHANGE_TYPE").size(), "Chỉ đổi tên thì không ghi CHANGE_TYPE");
        assertEquals(1, auditsWithAction(area, "UPDATE").size(), "Chỉ đổi tên -> 1 audit AREA/UPDATE");
        assertTrue(notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).isEmpty());
        assertNotNull(v0, "AreaResponse phải trả version (BR-TC-13)");
        assertEquals(v0 + 1, responseVersion(r), "Sửa thường vẫn tăng version");
    }

    /** BR-TC-14 — nguồn: Step5bAreaTypeChangeTest#tc14_BR_TC_14_toPublic_cancelsPendingAndApprovedNotEnded */
    @Test
    @DisplayName("TC-14 (BR-TC-14): sang PUBLIC -> huỷ mọi PENDING + APPROVED chưa kết thúc, lý do cố định, báo người gửi + thành viên")
    void tc14_toPublic_cancelsAndNotifies() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, 2, true);
        AccessRequest p1 = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.PENDING,
                now.plusHours(1), now.plusHours(2));
        AccessRequest p2 = newRequest(area, userL3, RequestType.GROUP, RequestStatus.PENDING,
                now.plusHours(3), now.plusHours(4), userL3b);
        AccessRequest a1 = newRequest(area, userL3, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(5), now.plusHours(6));
        AccessRequest ended = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.minusHours(4), now.minusHours(2));
        AccessRequest rejected = newRequest(area, userL1, RequestType.INDIVIDUAL, RequestStatus.REJECTED,
                now.plusHours(7), now.plusHours(8));

        MvcResult r = changeType(admin, area, AreaLevel.PUBLIC, TYPE_CHANGE_REASON, apiVersion(area));
        assertEquals(200, status(r), describe(r));

        assertEquals(RequestStatus.CANCELLED, requestStatus(p1), "PENDING cá nhân phải bị huỷ");
        assertEquals(RequestStatus.CANCELLED, requestStatus(p2), "PENDING nhóm phải bị huỷ");
        assertEquals(RequestStatus.CANCELLED, requestStatus(a1), "APPROVED chưa kết thúc phải bị huỷ");
        assertEquals(RequestStatus.APPROVED, requestStatus(ended), "APPROVED đã kết thúc giữ nguyên");
        assertEquals(RequestStatus.REJECTED, requestStatus(rejected), "REJECTED giữ nguyên");

        assertEquals(Set.of(p1.getId()), referenceIds(userL2, NotificationType.REQUEST_SYSTEM_CANCELLED));
        assertEquals(Set.of(p2.getId(), a1.getId()), referenceIds(userL3, NotificationType.REQUEST_SYSTEM_CANCELLED));
        assertEquals(Set.of(p2.getId()), referenceIds(userL3b, NotificationType.REQUEST_SYSTEM_CANCELLED));
        assertTrue(notificationsOf(userL1, NotificationType.REQUEST_SYSTEM_CANCELLED).isEmpty());

        for (AccessRequest cancelled : List.of(p1, p2, a1)) {
            assertSystemCancelled(cancelled, PUBLIC_CANCEL_REASON);
        }
    }

    /** BR-TC-16 — nguồn: Step5bAreaTypeChangeTest#tc16b_BR_TC_16_requesterAlsoMember_singleNotification */
    @Test
    @DisplayName("TC-16b (BR-TC-16): người gửi cũng là thành viên -> đúng 1 thông báo REQUEST_SYSTEM_CANCELLED / đơn")
    void tc16b_requesterAlsoMember_singleNotification() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AccessRequest group = newRequest(area, userL2, RequestType.GROUP, RequestStatus.APPROVED,
                now.plusHours(1), now.plusHours(2), userL2, userL3);

        MvcResult r = changeType(admin, area, AreaLevel.HIGHLY_CONFIDENTIAL, TYPE_CHANGE_REASON, apiVersion(area));
        assertEquals(200, status(r), describe(r));

        assertEquals(RequestStatus.CANCELLED, requestStatus(group));
        for (User u : List.of(userL2, userL3)) {
            long n = notificationsOf(u, NotificationType.REQUEST_SYSTEM_CANCELLED).stream()
                    .filter(x -> group.getId().equals(x.getReferenceId())).count();
            assertEquals(1, n, u.getEmail() + " phải nhận đúng 1 thông báo cho đơn " + group.getId());
        }
    }

    // ================================================================== rollback (Step5bAreaTypeChangeRollbackTest)

    /** BR-TC-11 — nguồn: Step5bAreaTypeChangeRollbackTest#tc11b_BR_TC_11_failureMidway_rollsBackEverything */
    @Test
    @DisplayName("TC-11b (BR-TC-11): lỗi khi huỷ đơn thứ 2 -> rollback toàn bộ (loại, cấp, đơn thứ 1), không audit, không thông báo")
    void tc11b_failureMidway_rollsBackEverything_noNotification() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AccessRequest r1 = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(1), now.plusHours(2));
        AccessRequest r2 = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(3), now.plusHours(4));
        long auditBefore = auditCountForArea(area);

        AtomicInteger systemCancelAudits = new AtomicInteger();
        doAnswer(inv -> {
            AuditActor actor = inv.getArgument(8);
            AuditActor effective = actor != null ? actor : AuditContext.getCurrentActor();
            if (effective != null && AREA_TYPE_CHANGE_SOURCE.equals(effective.getActorSource())
                    && systemCancelAudits.incrementAndGet() == 2) {
                throw new RuntimeException("TC-11b: ép lỗi khi hệ thống huỷ đơn thứ 2");
            }
            return inv.callRealMethod();
        }).when(auditService).record(any(), any(), any(), any(), any(), any(), any(), any(), nullable(AuditActor.class));

        MvcResult r = changeType(admin, area, AreaLevel.HIGHLY_CONFIDENTIAL, TYPE_CHANGE_REASON, apiVersion(area));

        assertTrue(status(r) >= 500, "Lỗi giữa chừng phải trả 5xx: " + describe(r));
        assertEquals(2, systemCancelAudits.get(), "Tiền đề: luồng đổi loại phải chạy tới lần huỷ đơn thứ 2");

        Area after = reload(area);
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, after.getAreaLevel(), "Loại khu vực phải rollback");
        assertEquals(2, after.getAreaAccessLevel(), "Cấp phải rollback");
        assertFalse(after.getExplicitAuthorizationRequired(), "Cờ cần đơn phải rollback");
        assertEquals(RequestStatus.APPROVED, requestStatus(r1), "Đơn thứ 1 đã huỷ trong transaction phải rollback");
        assertEquals(RequestStatus.APPROVED, requestStatus(r2));
        assertEquals(auditBefore, auditCountForArea(area), "Rollback thì không còn audit nào");
        assertTrue(auditsForTarget(r1.getId().toString()).isEmpty());
        assertEquals(0, countNotifications(userL2, fm, fm2), "Rollback thì không thông báo nào được gửi");
        assertTrue(notificationsOf(userL2, NotificationType.REQUEST_SYSTEM_CANCELLED).isEmpty());

        for (AccessRequest req : List.of(r1, r2)) {
            Map<String, Object> row = cancelColumns(req);
            assertNull(row.get("cancel_source"));
            assertNull(row.get("cancel_reason"));
        }
    }

    // ================================================================== chế độ sự kiện / lịch (Step5bEventActionTest, Step5bVersionTest, Step5bScheduleReasonTest)

    /** BR-EV-A6, BR-EV-31 — nguồn: Step5bEventActionTest#evA6_BR_EV_A6_enableAfterOpenUntil_newSession */
    @Test
    @DisplayName("EV-A6 (BR-EV-A6): quá open_until, job chưa đóng -> ENABLE -> phiên mới, giờ bắt đầu mới, GUARD nhận EVENT_MODE_CHANGED")
    void evA6_enableAfterOpenUntil_newSession_guardNotified() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSession old = openEvent(area, now.minusHours(2), now.minusMinutes(5));
        OffsetDateTime until = now.plusHours(2).withNano(0);

        MvcResult r = patchEventMode(fm, area, eventBody("ENABLE", until, "SEMINAR", EVENT_NOTE, apiVersion(area)));

        assertEquals(200, status(r), describe(r));
        List<AreaEventSession> open = openSessions(area);
        assertEquals(1, open.size(), "Đúng 1 phiên đang mở");
        AreaEventSession fresh = open.get(0);
        assertNotEquals(old.getId(), fresh.getId(), "Phải là phiên mới");
        assertFalse(fresh.getStartedAt().isBefore(now.minusSeconds(1)), "Giờ bắt đầu mới (không lấy giờ phiên cũ)");
        assertEquals(until.toInstant(), fresh.getPlannedEnd().toInstant());
        assertEquals(1, auditsWithAction(area, "ENABLE_EVENT_MODE").size());
        assertFalse(notificationsOf(guard, NotificationType.EVENT_MODE_CHANGED).isEmpty());
    }

    /** BR-TC-13 — nguồn: Step5bVersionTest#tc13_BR_TC_13_eventMode_missingVersion_badRequest */
    @Test
    @DisplayName("TC-13 PATCH event-mode (BR-TC-13): thiếu version -> 400 ERR_AREA_044, không mở sự kiện, không audit, không thông báo")
    void tc13_eventModeMissingVersion_noNotification() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long auditBefore = auditCountForArea(area);

        MvcResult r = patchEventMode(fm, area,
                eventBody("ENABLE", OffsetDateTime.now().plusHours(2), "SEMINAR", EVENT_NOTE, null));

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_044", errorCode(r));
        assertFalse(reload(area).getOpenToMembers());
        assertTrue(openSessions(area).isEmpty());
        assertEquals(auditBefore, auditCountForArea(area));
        assertTrue(notificationsOf(guard, NotificationType.EVENT_MODE_CHANGED).isEmpty());
    }

    /** BR-TC-13, BR-ES (thông báo lịch) — nguồn: Step5bVersionTest#tc18_BR_TC_13_scheduleEndpoints_doNotRequireVersion */
    @Test
    @DisplayName("TC-18 (BR-TC-13): tạo / sửa / huỷ lịch không cần version -> 2xx, 3 audit, GUARD nhận EVENT_MODE_SCHEDULED")
    void tc18_scheduleEndpoints_noVersion_guardNotified() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).withNano(0);

        MvcResult created = send(post("/api/areas/{id}/event-schedules", area.getId()), fm,
                new EventScheduleRequest(start, start.plusHours(2), "OTHER", EVENT_NOTE)).andReturn();
        assertEquals(201, status(created), describe(created));
        UUID scheduleId = UUID.fromString(json(created).path("data").path("id").asText());

        MvcResult updated = send(patch("/api/areas/{id}/event-schedules/{sid}", area.getId(), scheduleId), fm,
                new EventScheduleRequest(start.plusHours(1), start.plusHours(3), "OTHER", EVENT_NOTE)).andReturn();
        assertEquals(200, status(updated), describe(updated));

        MvcResult cancelled = send(post("/api/areas/{id}/event-schedules/{sid}/cancel", area.getId(), scheduleId), fm,
                new EventScheduleCancelRequest("OTHER", "Huỷ lịch dữ liệu test 5b")).andReturn();
        assertEquals(200, status(cancelled), describe(cancelled));

        assertEquals(AreaEventScheduleStatus.CANCELLED, eventScheduleRepository.findById(scheduleId).orElseThrow().getStatus());
        assertEquals(3, auditsForTarget(scheduleId.toString()).size(), "Tạo + sửa + huỷ -> 3 audit");
        assertFalse(notificationsOf(guard, NotificationType.EVENT_MODE_SCHEDULED).isEmpty(), "GUARD nhận thông báo lịch");
    }

    /** BR-ES-L2 — nguồn: Step5bScheduleReasonTest#esL2_BR_ES_L2_createWithEventEnableCode_badRequest026 */
    @Test
    @DisplayName("ES-L2 (BR-ES-L2): tạo lịch với mã thuộc nhóm EVENT_ENABLE -> 400 ERR_AREA_026, không tạo lịch, không audit, không EVENT_MODE_SCHEDULED")
    void esL2_createWithEventEnableCode_noNotification() throws Exception {
        ReasonCatalog enableOnly = newReason("EVENT_ENABLE", code("ESL2EN"), "Lý do bật test 5b");
        try {
            Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
            long auditBefore = auditCountForArea(area);
            OffsetDateTime start = OffsetDateTime.now().plusDays(1).withNano(0);

            MvcResult r = send(post("/api/areas/{id}/event-schedules", area.getId()), fm,
                    new EventScheduleRequest(start, start.plusHours(2), enableOnly.getCode(), EVENT_NOTE)).andReturn();

            assertEquals(400, status(r), describe(r));
            assertEquals("ERR_AREA_026", errorCode(r));
            assertEquals(0, schedulesOf(area));
            assertEquals(auditBefore, auditCountForArea(area));
            assertTrue(notificationsOf(guard, NotificationType.EVENT_MODE_SCHEDULED).isEmpty());
        } finally {
            reasonCatalogRepository.delete(enableOnly);
        }
    }

    /** BR-ES-L3, BR-EV-31 — nguồn: Step5bScheduleReasonTest#esL3_BR_ES_L3_autoActivation_auditKeepsOriginalReason */
    @Test
    @DisplayName("ES-L3 (BR-ES-L3): lịch tự kích hoạt -> audit ENABLE actor SYSTEM ghi lý do đặt lịch gốc, GUARD nhận EVENT_MODE_CHANGED")
    void esL3_autoActivation_guardNotified() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        String originalCode = code("ESL3");
        String originalLabel = "Lý do đặt lịch gốc " + suffix;
        AreaEventSchedule schedule = newSchedule(area, now.minusMinutes(1), now.plusHours(2), originalCode, originalLabel);

        areaService.activateScheduleInTx(schedule.getId(), now);

        assertEquals(AreaEventScheduleStatus.STARTED, eventScheduleRepository.findById(schedule.getId()).orElseThrow().getStatus());
        List<AuditLog> enable = auditsWithAction(area, "ENABLE_EVENT_MODE");
        assertEquals(1, enable.size());
        AuditLog audit = enable.get(0);
        assertEquals("SYSTEM", audit.getActorType());
        JsonNode newValue = objectMapper.readTree(audit.getNewValue());
        assertEquals(originalCode, newValue.path("reasonCode").asText());
        assertEquals(originalLabel, newValue.path("reasonLabel").asText());
        assertFalse(notificationsOf(guard, NotificationType.EVENT_MODE_CHANGED).isEmpty());
    }
}
