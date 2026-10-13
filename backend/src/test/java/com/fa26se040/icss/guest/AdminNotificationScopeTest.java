package com.fa26se040.icss.guest;

import com.fa26se040.icss.entity.Notification;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.NotificationType;
import com.fa26se040.icss.service.InAppNotificationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * BR-NT-26: thông báo chỉ gửi cho role xử lý được. ADMIN không nhận thông báo đơn truy cập (ACCESS_REQUEST) và duyệt /
 * kết quả lượt khách (GUEST_VISIT); ADMIN vẫn nhận GUEST_PHOTO_REQUIRED (gắn ảnh khách). FM giữ nguyên.
 * Dựng trên GuestTestSupport (fixture ADMIN / 2 FM / host cấp 2, khu INTERNAL + CONTACT, MinIO + embedding mock).
 */
class AdminNotificationScopeTest extends GuestTestSupport {

    @Autowired private InAppNotificationService inAppNotificationService;

    private List<Notification> of(User u, NotificationType type, UUID referenceId) {
        return notificationsOf(u, type).stream().filter(n -> referenceId.equals(n.getReferenceId())).toList();
    }

    @Test
    @DisplayName("NT-26: người dùng gửi đơn truy cập -> FM nhận NEW_REQUEST_PENDING, ADMIN không nhận")
    void accessRequestCreated_fmNotifiedAdminNot() throws Exception {
        OffsetDateTime start = OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("areaId", contactArea.getId());
        body.put("startTime", start.toString());
        body.put("endTime", start.plusHours(1).toString());
        body.put("purpose", "Đơn kiểm BR-NT-26 " + suffix);
        MvcResult r = send(post("/api/access-requests/individual"), hostL2, body).andReturn();
        assertEquals(201, status(r), r.getResponse().getContentAsString());
        UUID requestId = UUID.fromString(json(r).path("data").path("id").asText());

        assertEquals(1, of(fm, NotificationType.NEW_REQUEST_PENDING, requestId).size(), "FM nhận");
        assertEquals(1, of(fm2, NotificationType.NEW_REQUEST_PENDING, requestId).size(), "FM thứ hai nhận");
        assertEquals(0, of(admin, NotificationType.NEW_REQUEST_PENDING, requestId).size(), "ADMIN không nhận");
    }

    @Test
    @DisplayName("NT-26: host tạo lượt khách -> FM nhận GUEST_VISIT_PENDING, ADMIN không; FM duyệt -> ADMIN nhận GUEST_PHOTO_REQUIRED, không nhận GUEST_VISIT_APPROVED")
    void guestVisitFlow_adminOnlyPhotoRequired() throws Exception {
        MvcResult created = send(post("/api/guest-visits"), hostL2, validBody()).andReturn();
        assertEquals(201, status(created), created.getResponse().getContentAsString());
        UUID visitId = UUID.fromString(json(created).path("data").path("id").asText());
        assertEquals(1, of(fm, NotificationType.GUEST_VISIT_PENDING, visitId).size(), "FM nhận chờ duyệt");
        assertEquals(0, of(admin, NotificationType.GUEST_VISIT_PENDING, visitId).size(), "ADMIN không nhận chờ duyệt");

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("version", 0);
        review.put("decision", "APPROVED");
        MvcResult approved = send(patch("/api/guest-visits/" + visitId + "/review"), fm, review).andReturn();
        assertEquals(200, status(approved), approved.getResponse().getContentAsString());

        assertEquals(1, of(admin, NotificationType.GUEST_PHOTO_REQUIRED, visitId).size(), "ADMIN vẫn nhận GUEST_PHOTO_REQUIRED");
        assertEquals(1, of(hostL2, NotificationType.GUEST_VISIT_APPROVED, visitId).size(), "host nhận kết quả duyệt");
        assertEquals(0, of(admin, NotificationType.GUEST_VISIT_APPROVED, visitId).size(), "ADMIN không nhận kết quả duyệt");
    }

    @Test
    @DisplayName("NT-26: chặn ở InAppNotificationService — ADMIN bị bỏ với ACCESS_REQUEST / GUEST_VISIT (trừ GUEST_PHOTO_REQUIRED); FM giữ; loại khác (AREA) không đổi")
    void serviceLevelFilter() {
        UUID ref = UUID.randomUUID();
        List<Notification> sent = inAppNotificationService.createForUsers(List.of(admin, fm), NotificationType.REQUEST_APPROVED,
                "t", "m", ref);
        assertEquals(1, sent.size());
        assertEquals(fm.getId(), sent.get(0).getRecipient().getId(), "chỉ FM, ADMIN bị bỏ (ACCESS_REQUEST mặc định)");
        assertNull(inAppNotificationService.createForUser(admin, NotificationType.EXPIRING_SOON, "t", "m", ref), "createForUser ADMIN + ACCESS_REQUEST -> bỏ");

        assertEquals(0, inAppNotificationService.createForUsers(List.of(admin), NotificationType.GUEST_VISIT_APPROVED,
                "t", "m", ref, "GUEST_VISIT").size(), "GUEST_VISIT_* -> bỏ ADMIN");
        assertEquals(1, inAppNotificationService.createForUsers(List.of(admin), NotificationType.GUEST_PHOTO_REQUIRED,
                "t", "m", ref, "GUEST_VISIT").size(), "GUEST_PHOTO_REQUIRED -> ADMIN vẫn nhận");
        assertNotNull(inAppNotificationService.createForUser(admin, NotificationType.AREA_TYPE_CHANGED, "t", "m", ref, "AREA"),
                "loại AREA không đổi");
    }
}
