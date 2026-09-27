package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * Step 5b — ADMIN đổi loại khu vực (BR-TC-01..12, 14, 15). Test viết trước, phải ĐỎ trước khi hiện thực.
 * TC-11b (rollback giữa chừng) nằm ở Step5bAreaTypeChangeRollbackTest vì cần @SpyBean riêng.
 */
public class Step5bAreaTypeChangeTest extends Step5bTestSupport {

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

    // ------------------------------------------------------------------ TC-01

    @Test
    @DisplayName("TC-01 (BR-TC-01): FM / GUARD / NORMAL_USER đổi loại khu vực -> 403, không đổi gì, không audit")
    void tc01_BR_TC_01_nonAdminChangeType_forbidden() throws Exception {
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

    // ------------------------------------------------------------------ TC-02

    @Test
    @DisplayName("TC-02a (BR-TC-02): đổi loại thiếu reason -> 400, không đổi gì, không audit")
    void tc02a_BR_TC_02_changeTypeWithoutReason_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, AreaLevel.HIGHLY_CONFIDENTIAL, null, v0);

        assertEquals(400, status(r), describe(r));
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, reload(area).getAreaLevel());
        assertEquals(auditBefore, auditCountForArea(area));
        assertEquals(0, notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).size());
    }

    @Test
    @DisplayName("TC-02b (BR-TC-02): đổi loại với reason 5 ký tự -> 400, không đổi gì, không audit")
    void tc02b_BR_TC_02_changeTypeReasonTooShort_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        Long v0 = apiVersion(area);
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, AreaLevel.HIGHLY_CONFIDENTIAL, "abcde", v0);

        assertEquals(400, status(r), describe(r));
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, reload(area).getAreaLevel());
        assertEquals(auditBefore, auditCountForArea(area));
        assertEquals(0, notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).size());
    }

    @Test
    @DisplayName("TC-02c (BR-TC-02): đổi loại hợp lệ -> đúng 1 audit AREA/CHANGE_TYPE, snapshot trước/sau có loại, cấp, cờ, tên")
    void tc02c_BR_TC_02_validChangeType_exactlyOneChangeTypeAudit() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaLevelPreset target = presetRepository.findById(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED).orElseThrow();
        Long v0 = apiVersion(area);

        MvcResult r = changeType(admin, area, AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, TYPE_CHANGE_REASON, v0);
        assertEquals(200, status(r), describe(r));

        List<AuditLog> changeTypeAudits = auditsWithAction(area, "CHANGE_TYPE");
        assertEquals(1, changeTypeAudits.size(), "Phải có đúng 1 audit CHANGE_TYPE");
        AuditLog audit = changeTypeAudits.get(0);
        assertEquals(AuditTargetType.AREA, audit.getTargetType());
        assertEquals(TYPE_CHANGE_REASON, audit.getReason());

        JsonNode oldV = objectMapper.readTree(audit.getOldValue());
        JsonNode newV = objectMapper.readTree(audit.getNewValue());
        assertEquals("INTERNAL_CONFIDENTIAL", oldV.path("areaLevel").asText());
        assertEquals(2, oldV.path("areaAccessLevel").asInt());
        assertFalse(oldV.path("explicitAuthorizationRequired").asBoolean(true));
        assertEquals(area.getName(), oldV.path("name").asText());
        assertEquals("CONFIDENTIAL_CONTACT_REQUIRED", newV.path("areaLevel").asText());
        assertEquals(target.getAreaAccessLevel().intValue(), newV.path("areaAccessLevel").asInt());
        assertEquals(target.getExplicitAuthorizationRequired(), newV.path("explicitAuthorizationRequired").asBoolean());
        assertEquals(area.getName(), newV.path("name").asText());
    }

    // ------------------------------------------------------------------ TC-03

    @Test
    @DisplayName("TC-03 (BR-TC-03): xem trước đổi loại trả đủ trường; gọi xong DB không đổi (areas / access_requests / audit)")
    void tc03_BR_TC_03_preview_returnsAllFields_readOnly() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusMinutes(10), now.plusHours(2));
        newSchedule(area, now.plusDays(1), now.plusDays(1).plusHours(2), "SEMINAR", "Hội thảo/sự kiện chuyên môn");
        newActiveAp(area, userL3);
        AccessRequest approved = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(3), now.plusHours(5));
        newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.PENDING, now.plusHours(6), now.plusHours(8));
        AreaLevelPreset target = presetRepository.findById(AreaLevel.HIGHLY_CONFIDENTIAL).orElseThrow();

        long areasBefore = areaRepository.count();
        long requestsBefore = accessRequestRepository.count();
        long auditBefore = auditLogRepository.count();

        MvcResult r = send(get("/api/areas/{id}/type-change-preview", area.getId())
                .param("newAreaLevel", AreaLevel.HIGHLY_CONFIDENTIAL.name()), admin, null).andReturn();
        assertEquals(200, status(r), describe(r));

        JsonNode d = json(r).path("data");
        assertEquals("INTERNAL_CONFIDENTIAL", d.path("currentAreaLevel").asText());
        assertEquals("HIGHLY_CONFIDENTIAL", d.path("newAreaLevel").asText());
        assertEquals(2, d.path("currentAreaAccessLevel").asInt());
        assertEquals(target.getAreaAccessLevel().intValue(), d.path("newAreaAccessLevel").asInt());
        assertFalse(d.path("currentExplicitAuthorizationRequired").asBoolean(true));
        assertEquals(target.getExplicitAuthorizationRequired(), d.path("newExplicitAuthorizationRequired").asBoolean());
        assertEquals(1, d.path("activeAssignedPersonnelCount").asInt(-1));
        assertTrue(d.path("approvedRequestsToCancel").isArray(), "approvedRequestsToCancel phải là danh sách");
        assertTrue(d.path("approvedRequestsToCancel").toString().contains(approved.getId().toString()),
                "Danh sách đơn APPROVED sẽ bị huỷ phải có đơn " + approved.getId());
        assertEquals(1, d.path("pendingRequestsNotApprovableCount").asInt(-1));
        assertTrue(d.path("eventActive").asBoolean(false), "Phải báo sự kiện đang mở");
        assertEquals(1, d.path("pendingScheduleCount").asInt(-1));
        assertTrue(d.path("blockingReasons").isArray() && d.path("blockingReasons").size() > 0,
                "Phải liệt kê lý do bị chặn (sự kiện đang mở, lịch chờ)");

        assertEquals(areasBefore, areaRepository.count(), "Xem trước không được ghi areas");
        assertEquals(requestsBefore, accessRequestRepository.count(), "Xem trước không được ghi access_requests");
        assertEquals(auditBefore, auditLogRepository.count(), "Xem trước không được ghi audit");
        Area after = reload(area);
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, after.getAreaLevel());
        assertEquals(2, after.getAreaAccessLevel());
        assertEquals(RequestStatus.APPROVED, requestStatus(approved));
    }

    // ------------------------------------------------------------------ TC-04

    @Test
    @DisplayName("TC-04a (BR-TC-04): đổi loại áp lại preset của loại mới (cấp + cờ cần đơn)")
    void tc04a_BR_TC_04_changeType_appliesTargetPreset() throws Exception {
        // Khu vực INTERNAL tuỳ chỉnh lệch hẳn preset CONTACT hiện tại -> đổi sang CONTACT phải về đúng preset CONTACT.
        // Không giả định giá trị preset: AccessControlAuditLogIntegrationTest đảo preset CONTACT mỗi lần chạy.
        AreaLevelPreset target = presetRepository.findById(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED).orElseThrow();
        int customLevel = target.getAreaAccessLevel() == 3 ? 1 : 3;
        boolean customFlag = !target.getExplicitAuthorizationRequired();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, customLevel, customFlag);

        MvcResult r = changeType(admin, area, AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, TYPE_CHANGE_REASON, apiVersion(area));
        assertEquals(200, status(r), describe(r));

        Area after = reload(area);
        assertEquals(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, after.getAreaLevel());
        assertEquals(target.getAreaAccessLevel(), after.getAreaAccessLevel(), "Cấp phải theo preset loại mới");
        assertEquals(target.getExplicitAuthorizationRequired(), after.getExplicitAuthorizationRequired(), "Cờ cần đơn phải theo preset loại mới");
        assertEquals(1, auditsWithAction(area, "CHANGE_TYPE").size(), "Phải có đúng 1 audit CHANGE_TYPE");
    }

    @Test
    @DisplayName("TC-04b (BR-TC-04): thiếu preset của loại đích -> cấp 3, cờ true (fail-closed)")
    void tc04b_BR_TC_04_missingTargetPreset_failClosed() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaLevelPreset original = presetRepository.findById(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED).orElseThrow();
        Integer origLevel = original.getAreaAccessLevel();
        Boolean origFlag = original.getExplicitAuthorizationRequired();
        OffsetDateTime origUpdatedAt = original.getUpdatedAt();

        presetRepository.delete(original);
        try {
            MvcResult r = changeType(admin, area, AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, TYPE_CHANGE_REASON, apiVersion(area));
            assertEquals(200, status(r), describe(r));

            Area after = reload(area);
            assertEquals(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, after.getAreaLevel());
            assertEquals(3, after.getAreaAccessLevel(), "Thiếu preset -> cấp 3");
            assertTrue(after.getExplicitAuthorizationRequired(), "Thiếu preset -> cờ cần đơn = true");
        } finally {
            if (presetRepository.findById(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED).isEmpty()) {
                presetRepository.save(AreaLevelPreset.builder()
                        .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                        .areaAccessLevel(origLevel)
                        .explicitAuthorizationRequired(origFlag)
                        .updatedAt(origUpdatedAt)
                        .build());
            }
        }
    }

    // ------------------------------------------------------------------ TC-05

    @Test
    @DisplayName("TC-05a (BR-TC-05): đang mở sự kiện -> đổi sang HIGHLY -> 409, không đổi gì")
    void tc05a_BR_TC_05_eventActive_toHighly_conflict() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusMinutes(10), now.plusHours(2));
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, AreaLevel.HIGHLY_CONFIDENTIAL, TYPE_CHANGE_REASON, apiVersion(area));

        assertEquals(409, status(r), describe(r));
        Area after = reload(area);
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, after.getAreaLevel());
        assertEquals(2, after.getAreaAccessLevel());
        assertTrue(after.isEventActive(OffsetDateTime.now()), "Sự kiện vẫn phải đang mở");
        assertTrue(sessionRepository.findByAreaIdAndActualEndIsNull(area.getId()).isPresent(), "Phiên sự kiện vẫn mở");
        assertEquals(auditBefore, auditCountForArea(area));
        assertEquals(0, notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).size());
    }

    @Test
    @DisplayName("TC-05b (BR-TC-05): đang mở sự kiện -> đổi sang PUBLIC -> 409, không đổi gì")
    void tc05b_BR_TC_05_eventActive_toPublic_conflict() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusMinutes(10), now.plusHours(2));
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, AreaLevel.PUBLIC, TYPE_CHANGE_REASON, apiVersion(area));

        assertEquals(409, status(r), describe(r));
        Area after = reload(area);
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, after.getAreaLevel());
        assertTrue(after.isEventActive(OffsetDateTime.now()), "Sự kiện vẫn phải đang mở");
        assertEquals(auditBefore, auditCountForArea(area));
        assertEquals(0, notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).size());
    }

    @Test
    @DisplayName("TC-05c (BR-TC-05): đang mở sự kiện, INTERNAL -> CONTACT -> thành công, giữ nguyên sự kiện")
    void tc05c_BR_TC_05_eventActive_internalToContact_keepsEvent() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AreaEventSession session = openEvent(area, now.minusMinutes(10), now.plusHours(2));
        OffsetDateTime openUntil = reload(area).getOpenUntil();

        MvcResult r = changeType(admin, area, AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, TYPE_CHANGE_REASON, apiVersion(area));

        assertEquals(200, status(r), describe(r));
        Area after = reload(area);
        assertEquals(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, after.getAreaLevel());
        assertTrue(after.getOpenToMembers(), "Cờ sự kiện giữ nguyên");
        assertEquals(openUntil.toInstant(), after.getOpenUntil().toInstant(), "Giờ kết thúc sự kiện giữ nguyên");
        AreaEventSession stillOpen = sessionRepository.findByAreaIdAndActualEndIsNull(area.getId()).orElse(null);
        assertNotNull(stillOpen, "Phiên sự kiện vẫn mở");
        assertEquals(session.getId(), stillOpen.getId());
        assertEquals(1, auditsWithAction(area, "CHANGE_TYPE").size(), "Phải có đúng 1 audit CHANGE_TYPE");
    }

    // ------------------------------------------------------------------ TC-06

    @Test
    @DisplayName("TC-06 (BR-TC-06, hồi quy X1): còn lịch SCHEDULED -> đổi sang HIGHLY / PUBLIC -> 409 ERR_AREA_042, liệt kê lịch")
    void tc06_BR_TC_06_pendingSchedule_toHighlyOrPublic_conflictListsSchedules() throws Exception {
        OffsetDateTime start = OffsetDateTime.now().plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        newSchedule(area, start, start.plusHours(2), "SEMINAR", "Hội thảo/sự kiện chuyên môn");
        long auditBefore = auditCountForArea(area);
        String startText = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.of("Asia/Ho_Chi_Minh")).format(start);

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

    // ------------------------------------------------------------------ TC-07

    @Test
    @DisplayName("TC-07 (BR-TC-07): sang PUBLIC khi còn AP hiệu lực -> 409, thông điệp nêu số AP")
    void tc07_BR_TC_07_toPublic_withActiveAp_conflictMentionsCount() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        newActiveAp(area, userL2);
        newActiveAp(area, userL3);
        newActiveAp(area, userL3b);
        AreaAssignedPersonnel revoked = newActiveAp(area, userL1);
        revoked.setRevokedAt(OffsetDateTime.now().minusMinutes(5));
        revoked.setRevokeReason("Thu hồi dữ liệu test 5b");
        assignedPersonnelRepository.save(revoked);
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, AreaLevel.PUBLIC, TYPE_CHANGE_REASON, apiVersion(area));

        assertEquals(409, status(r), describe(r));
        assertTrue(message(r).contains("3"), "Thông điệp phải nêu số AP còn hiệu lực (3): " + message(r));
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, reload(area).getAreaLevel());
        assertEquals(auditBefore, auditCountForArea(area));
        assertEquals(0, notificationsOf(fm, NotificationType.AREA_TYPE_CHANGED).size());
    }

    // ------------------------------------------------------------------ TC-08

    @Test
    @DisplayName("TC-08 (BR-TC-08, BR-TC-16): đơn APPROVED chưa kết thúc không thoả loại mới -> hệ thống huỷ, audit SYSTEM cùng correlation, báo người tạo + thành viên")
    void tc08_BR_TC_08_approvedNotEnded_systemCancelled() throws Exception {
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

    // ------------------------------------------------------------------ TC-09

    @Test
    @DisplayName("TC-09 (BR-TC-09): đơn PENDING không bị huỷ khi đổi loại; FM duyệt sau đó bị chặn theo cấp mới")
    void tc09_BR_TC_09_pendingKept_fmApproveBlockedByNewLevel() throws Exception {
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

    // ------------------------------------------------------------------ TC-10

    @Test
    @DisplayName("TC-10 (BR-TC-10): đổi loại thành công -> mọi FM đang hoạt động nhận AREA_TYPE_CHANGED, FM bị vô hiệu hoá không nhận")
    void tc10_BR_TC_10_activeFmsNotified_inactiveFmNot() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);

        MvcResult r = changeType(admin, area, AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, TYPE_CHANGE_REASON, apiVersion(area));
        assertEquals(200, status(r), describe(r));

        for (User activeFm : List.of(fm, fm2)) {
            List<Notification> list = notificationsOf(activeFm, NotificationType.AREA_TYPE_CHANGED).stream()
                    .filter(n -> area.getId().equals(n.getReferenceId()))
                    .toList();
            assertEquals(1, list.size(), "FM " + activeFm.getEmail() + " phải nhận đúng 1 thông báo AREA_TYPE_CHANGED");
            assertTrue(list.get(0).getMessage().contains(TYPE_CHANGE_REASON), "Thông báo phải nêu lý do: " + list.get(0).getMessage());
        }
        assertTrue(notificationsOf(fmInactive, NotificationType.AREA_TYPE_CHANGED).isEmpty(), "FM bị vô hiệu hoá không nhận");
        assertTrue(notificationsOf(guard, NotificationType.AREA_TYPE_CHANGED).isEmpty(), "Chỉ FM nhận AREA_TYPE_CHANGED");
    }

    // ------------------------------------------------------------------ TC-11

    @Test
    @DisplayName("TC-11 (BR-TC-11) + TC-05: bị chặn vì sự kiện đang mở -> không audit, không thông báo, version không đổi")
    void tc11_BR_TC_11_blockedByEvent_noSideEffects() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        openEvent(area, now.minusMinutes(10), now.plusHours(2));
        AccessRequest approved = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(3), now.plusHours(4));
        assertBlockedWithoutSideEffects(area, AreaLevel.HIGHLY_CONFIDENTIAL, approved);
    }

    @Test
    @DisplayName("TC-11 (BR-TC-11) + TC-06: bị chặn vì còn lịch chờ -> không audit, không thông báo, version không đổi")
    void tc11_BR_TC_11_blockedBySchedule_noSideEffects() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        newSchedule(area, now.plusDays(1), now.plusDays(1).plusHours(2), "SEMINAR", "Hội thảo/sự kiện chuyên môn");
        AccessRequest approved = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.APPROVED,
                now.plusHours(3), now.plusHours(4));
        assertBlockedWithoutSideEffects(area, AreaLevel.HIGHLY_CONFIDENTIAL, approved);
    }

    @Test
    @DisplayName("TC-11 (BR-TC-11) + TC-07: bị chặn vì còn AP hiệu lực -> không audit, không thông báo, version không đổi")
    void tc11_BR_TC_11_blockedByActiveAp_noSideEffects() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        newActiveAp(area, userL3);
        AccessRequest pending = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.PENDING,
                now.plusHours(3), now.plusHours(4));
        assertBlockedWithoutSideEffects(area, AreaLevel.PUBLIC, pending);
    }

    private void assertBlockedWithoutSideEffects(Area area, AreaLevel target, AccessRequest request) throws Exception {
        RequestStatus requestBefore = requestStatus(request);
        Long v0 = apiVersion(area);
        long auditBefore = auditCountForArea(area);

        MvcResult r = changeType(admin, area, target, TYPE_CHANGE_REASON, v0);

        assertEquals(409, status(r), describe(r));
        assertEquals(AreaLevel.INTERNAL_CONFIDENTIAL, reload(area).getAreaLevel());
        assertEquals(requestBefore, requestStatus(request), "Bị chặn thì đơn không đổi");
        assertEquals(auditBefore, auditCountForArea(area), "Bị chặn thì không ghi audit");
        assertTrue(auditsForTarget(request.getId().toString()).isEmpty(), "Bị chặn thì không có audit huỷ đơn");
        assertEquals(0, countNotifications(fm, fm2, userL2), "Bị chặn thì không có thông báo");
        assertNotNull(v0, "AreaResponse phải trả version (BR-TC-13)");
        assertEquals(v0, apiVersion(area), "Bị chặn thì version không đổi");
    }

    // ------------------------------------------------------------------ TC-12

    @Test
    @DisplayName("TC-12a (BR-TC-12): chỉ đổi tên -> không cần reason, không CHANGE_TYPE, version tăng 1")
    void tc12a_BR_TC_12_renameOnly_noReasonNoChangeType() throws Exception {
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

    @Test
    @DisplayName("TC-12b (BR-TC-12, BR-TC-13): chỉ đổi tên nhưng thiếu version -> 400 ERR_AREA_044, không đổi gì")
    void tc12b_BR_TC_12_renameOnly_missingVersion_badRequest() throws Exception {
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        long auditBefore = auditCountForArea(area);

        MvcResult r = putArea(admin, area, area.getName() + " moi", AreaLevel.INTERNAL_CONFIDENTIAL, null, null);

        assertEquals(400, status(r), describe(r));
        assertEquals("ERR_AREA_044", errorCode(r));
        assertEquals(area.getName(), reload(area).getName());
        assertEquals(auditBefore, auditCountForArea(area));
    }

    // ------------------------------------------------------------------ TC-14

    @Test
    @DisplayName("TC-14 (BR-TC-14): sang PUBLIC -> huỷ mọi PENDING + APPROVED chưa kết thúc, lý do cố định, báo người gửi + thành viên")
    void tc14_BR_TC_14_toPublic_cancelsPendingAndApprovedNotEnded() throws Exception {
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

    // ------------------------------------------------------------------ TC-15

    @Test
    @DisplayName("TC-15 (BR-TC-15): người dùng tự huỷ đơn -> cancel_source USER, cancelled_by = người đó")
    void tc15_BR_TC_15_userSelfCancel_sourceUser() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        Area area = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        AccessRequest pending = newRequest(area, userL2, RequestType.INDIVIDUAL, RequestStatus.PENDING,
                now.plusHours(2), now.plusHours(3));

        MvcResult r = send(patch("/api/access-requests/{id}/cancel", pending.getId()), userL2, null).andReturn();
        assertEquals(200, status(r), describe(r));
        assertEquals(RequestStatus.CANCELLED, requestStatus(pending));
        assertEquals(1, auditsForTarget(pending.getId().toString()).stream()
                .filter(a -> "USER".equals(a.getActorType())).count(), "Tự huỷ -> 1 audit actor USER");

        Map<String, Object> row = cancelColumns(pending);
        assertEquals("USER", row.get("cancel_source"));
        assertEquals(userL2.getId(), row.get("cancelled_by"));
    }
}
