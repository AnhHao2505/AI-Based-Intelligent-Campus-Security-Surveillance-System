package com.fa26se040.icss.guest;

import com.fa26se040.icss.TestFaces;
import com.fa26se040.icss.dto.guest.GuestVisitRevokeRequest;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.service.GuestFaceEmbeddingClient;
import com.fa26se040.icss.service.GuestVisitJobService;
import com.fa26se040.icss.service.GuestVisitService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * P4 — ảnh khách (BR-GV-14..18, 30): host gắn / thay ảnh khách của lượt mình khi lượt còn chờ duyệt hoặc đã duyệt mà
 * chưa tới giờ bắt đầu (A5); ADMIN không còn gắn ảnh (B2); host / FM / ADMIN xem ảnh qua URL có hạn (B1, B3).
 * Kho MinIO và dịch vụ trích khuôn mặt được mock (dự án chưa có container cho test; test MinIO hiện có đều dùng Mockito).
 */
public class GuestPhotoTest extends GuestTestSupport {

    @Autowired
    private GuestVisitService guestVisitService;

    @Autowired
    private GuestVisitJobService jobService;

    private static GuestFaceEmbeddingClient.Result oneFace() {
        return new GuestFaceEmbeddingClient.Result(1, TestFaces.random());
    }

    private String photoUrl(GuestVisit v, Guest g) {
        return "/api/guest-visits/" + v.getId() + "/guests/" + g.getId() + "/photo";
    }

    private MvcResult attach(User actor, GuestVisit v, Guest g, Boolean consent, String version) throws Exception {
        var b = multipart(photoUrl(v, g)).file(new MockMultipartFile("file", "khach.jpg", "image/jpeg", JPEG));
        if (consent != null) b.param("consentConfirmed", consent.toString());
        if (version != null) b.param("consentNoticeVersion", version);
        b.header("Authorization", bearer(actor));
        return mockMvc.perform(b).andReturn();
    }

    private MvcResult viewUrl(User actor, GuestVisit v, Guest g) throws Exception {
        return send(post(photoUrl(v, g) + "-url"), actor, null).andReturn();
    }

    private void assertRejected(MvcResult r, int http, String code) throws Exception {
        assertEquals(http, status(r), "HTTP: " + r.getResponse().getContentAsString());
        assertEquals(code, errorCode(r), "Mã lỗi: " + message(r));
    }

    private GuestVisit approved() {
        return newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(internalArea), "Nguyễn Văn Khách");
    }

    @Test
    @DisplayName("A5/GV-14..17: host gắn ảnh khách của lượt đã duyệt chưa bắt đầu -> PHOTO_READY, người xác nhận đồng ý = host, embedding bảng riêng expires = end + GUEST_FACE_RETENTION_HOURS, audit ATTACH_PHOTO (actor host), version +1, response không có key / URL")
    void attach_ok() throws Exception {
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        resetRefreshCalls();
        MvcResult r = attach(hostL2, v, g, true, "v1");
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        assertEquals(List.of(true), refreshCallsInTransaction, "L3: gắn ảnh -> báo bên nhận diện làm mới kho khuôn mặt 1 lần, đăng ký trong transaction (gửi sau commit)");
        String raw = r.getResponse().getContentAsString();
        assertFalse(raw.contains("visits/") || raw.contains("http://") || raw.contains("https://") || raw.contains("objectKey"), raw);

        Guest after = guest(g.getId());
        assertEquals(GuestBiometricStatus.PHOTO_READY, after.getBiometricStatus());
        assertNotNull(after.getPhotoObjectKey());
        assertEquals(hostL2.getId(), after.getConsentConfirmedBy().getId(), "người xác nhận đồng ý = host");
        assertNotNull(after.getConsentConfirmedAt());
        assertEquals("v1", after.getConsentNoticeVersion());
        assertEquals(1L, visit(v.getId()).getVersion(), "thay ảnh tăng version để FM đang xem bản cũ phải tải lại");
        GuestFaceEmbedding e = embeddingRepository.findById(g.getId()).orElseThrow();
        assertEquals(0, Duration.between(v.getEndTime().plusHours(systemConfigService.getInt(ConfigKey.GUEST_FACE_RETENTION_HOURS)),
                e.getExpiresAt()).getSeconds(), "expires_at = end + GUEST_FACE_RETENTION_HOURS");
        verify(photoStorage).upload(eq(after.getPhotoObjectKey()), any(), eq("image/jpeg"));

        List<AuditLog> a = audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.ATTACH_PHOTO);
        assertEquals(1, a.size());
        assertEquals(hostL2.getId(), a.get(0).getChangedBy().getId(), "audit ghi đúng host");
        String snap = String.valueOf(a.get(0).getNewValue()) + a.get(0).getOldValue();
        assertFalse(snap.contains(after.getPhotoObjectKey()) || snap.contains("embedding") || snap.contains("http"), "snapshot không chứa ảnh / URL / embedding: " + snap);
    }

    @Test
    @DisplayName("GV-15: thiếu / false đồng ý, sai phiên bản -> 400 ERR_GUEST_031 (câu nêu phiên bản hiện hành); đổi config sang v2 thì v2 mới hợp lệ")
    void attach_consent() throws Exception {
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        assertRejected(attach(hostL2, v, g, null, "v1"), 400, "ERR_GUEST_031");
        assertRejected(attach(hostL2, v, g, false, "v1"), 400, "ERR_GUEST_031");
        MvcResult wrong = attach(hostL2, v, g, true, "v0");
        assertRejected(wrong, 400, "ERR_GUEST_031");
        assertTrue(message(wrong).contains("v1"), message(wrong));
        assertRejected(attach(hostL2, v, g, true, null), 400, "ERR_GUEST_031");
        setConfig(ConfigKey.GUEST_CONSENT_NOTICE_VERSION, "v2");
        assertRejected(attach(hostL2, v, g, true, "v1"), 400, "ERR_GUEST_031");
        assertEquals(200, status(attach(hostL2, v, g, true, "v2")));
        assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus());
        verify(faceClient, times(1)).extract(any(), any(), any());
    }

    @Test
    @DisplayName("A5: thay ảnh được khi PENDING / APPROVED chưa bắt đầu; APPROVED đã bắt đầu, đã qua end, REJECTED, CANCELLED, EXPIRED -> 409 ERR_GUEST_039; khách không thuộc lượt -> 404 ERR_GUEST_032")
    void attach_visitState() throws Exception {
        GuestVisit pending = newVisit(hostL2, GuestVisitStatus.PENDING, future(0), future(60), List.of(internalArea), "Khách chờ");
        assertEquals(200, status(attach(hostL2, pending, guestsOf(pending.getId()).get(0), true, "v1")), "PENDING thay được");

        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit started = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusMinutes(10), now.plusHours(1),
                List.of(internalArea), "Khách đang trong lượt");
        assertRejected(attach(hostL2, started, guestsOf(started.getId()).get(0), true, "v1"), 409, "ERR_GUEST_039");
        GuestVisit ended = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusHours(3), now.minusHours(1),
                List.of(internalArea), "Khách cũ");
        assertRejected(attach(hostL2, ended, guestsOf(ended.getId()).get(0), true, "v1"), 409, "ERR_GUEST_039");
        for (GuestVisitStatus closed : List.of(GuestVisitStatus.CANCELLED, GuestVisitStatus.EXPIRED)) {
            GuestVisit x = newVisit(hostL2, closed, future(0), future(60), List.of(internalArea), "Khách " + closed);
            assertRejected(attach(hostL2, x, guestsOf(x.getId()).get(0), true, "v1"), 409, "ERR_GUEST_039");
        }
        // REJECTED cần lý do (CHECK chk_guest_visits_reject_reason): dựng PENDING rồi chuyển trạng thái
        GuestVisit rejected = newVisit(hostL2, GuestVisitStatus.PENDING, future(0), future(60), List.of(internalArea), "Khách bị từ chối");
        transactionTemplate.executeWithoutResult(tx -> {
            GuestVisit y = guestVisitRepository.findById(rejected.getId()).orElseThrow();
            y.setStatus(GuestVisitStatus.REJECTED);
            y.setReviewedBy(fm);
            y.setReviewedAt(OffsetDateTime.now());
            y.setReviewReason(REASON);
            guestVisitRepository.save(y);
        });
        assertRejected(attach(hostL2, rejected, guestsOf(rejected.getId()).get(0), true, "v1"), 409, "ERR_GUEST_039");
        GuestVisit other = approved();
        assertRejected(attach(hostL2, other, guestsOf(pending.getId()).get(0), true, "v1"), 404, "ERR_GUEST_032");
        verify(photoStorage, times(1)).upload(any(), any(), any());
    }

    @Test
    @DisplayName("GV-16/A5: thay ảnh -> xoá ảnh cũ trên kho + embedding cũ (audit DELETE_BIOMETRIC), còn đúng 1 ảnh / 1 embedding; ảnh mới cùng khuôn mặt của chính khách đó không bị coi là trùng")
    void attach_again_replaces() throws Exception {
        List<Float> face = TestFaces.random();
        when(faceClient.extract(any(), any(), any()))
                .thenReturn(new GuestFaceEmbeddingClient.Result(1, face))
                .thenReturn(new GuestFaceEmbeddingClient.Result(1, TestFaces.sameAs(face)));
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        assertEquals(200, status(attach(hostL2, v, g, true, "v1")));
        String firstKey = guest(g.getId()).getPhotoObjectKey();
        String firstVec = embeddingRepository.findById(g.getId()).orElseThrow().getEmbedding();
        MvcResult again = attach(hostL2, v, g, true, "v1");
        assertEquals(200, status(again), again.getResponse().getContentAsString());
        Guest after = guest(g.getId());
        assertNotEquals(firstKey, after.getPhotoObjectKey());
        verify(photoStorage).removeObject(firstKey);
        assertNotEquals(firstVec, embeddingRepository.findById(g.getId()).orElseThrow().getEmbedding(), "embedding cũ bị thay");
        assertEquals(1, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC).size());
        assertEquals(2, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.ATTACH_PHOTO).size());
        assertEquals(2L, visit(v.getId()).getVersion());
    }

    @Test
    @DisplayName("GV-14/17: ảnh 0 hoặc 2 khuôn mặt -> 400 ERR_GUEST_034; dịch vụ trích khuôn mặt lỗi -> 503 ERR_GUEST_035; kho lỗi -> 503 ERR_GUEST_036; không lưu gì")
    void attach_failures() throws Exception {
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        when(faceClient.extract(any(), any(), any())).thenReturn(new GuestFaceEmbeddingClient.Result(2, TestFaces.random()));
        assertRejected(attach(hostL2, v, g, true, "v1"), 400, "ERR_GUEST_034");
        when(faceClient.extract(any(), any(), any())).thenReturn(new GuestFaceEmbeddingClient.Result(0, List.of()));
        assertRejected(attach(hostL2, v, g, true, "v1"), 400, "ERR_GUEST_034");
        when(faceClient.extract(any(), any(), any())).thenThrow(new IllegalStateException("service down"));
        assertRejected(attach(hostL2, v, g, true, "v1"), 503, "ERR_GUEST_035");
        reset(faceClient);
        when(faceClient.extract(any(), any(), any())).thenReturn(oneFace());
        doThrow(new RuntimeException("MinIO down")).when(photoStorage).upload(any(), any(), any());
        assertRejected(attach(hostL2, v, g, true, "v1"), 503, "ERR_GUEST_036");
        Guest after = guest(g.getId());
        assertEquals(GuestBiometricStatus.NO_PHOTO, after.getBiometricStatus());
        assertNull(after.getPhotoObjectKey());
        assertFalse(embeddingRepository.existsById(g.getId()));
        assertEquals(0L, visit(v.getId()).getVersion(), "lỗi -> version không đổi");
    }

    @Test
    @DisplayName("GV-18/B1/B3: FM, host, ADMIN xin URL xem ảnh -> URL có hạn GUEST_PHOTO_URL_TTL_SECONDS, mỗi lần cấp ghi 1 audit VIEW_PHOTO (không chứa URL, actor đúng người xem); khách chưa có ảnh -> 404 ERR_GUEST_037")
    void viewUrl() throws Exception {
        when(photoStorage.presignedGetUrl(any(), anyInt())).thenReturn("http://minio.local/guest-photos/x?X-Amz-Signature=abc");
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        assertRejected(viewUrl(fm, v, g), 404, "ERR_GUEST_037");
        assertEquals(200, status(attach(hostL2, v, g, true, "v1")));
        setConfig(ConfigKey.GUEST_PHOTO_URL_TTL_SECONDS, "45");
        MvcResult r1 = viewUrl(fm, v, g);
        assertEquals(200, status(r1), r1.getResponse().getContentAsString());
        assertEquals(45, json(r1).path("data").path("expiresInSeconds").asInt());
        assertTrue(json(r1).path("data").path("url").asText().contains("X-Amz-Signature"));
        verify(photoStorage).presignedGetUrl(guest(g.getId()).getPhotoObjectKey(), 45);
        assertEquals(200, status(viewUrl(hostL2, v, g)), "host xem khách của lượt mình");
        assertEquals(200, status(viewUrl(admin, v, g)), "ADMIN vẫn xem được");
        List<AuditLog> a = audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.VIEW_PHOTO);
        assertEquals(3, a.size());
        assertTrue(a.stream().anyMatch(x -> fm.getId().equals(x.getChangedBy().getId())), "audit ghi FM đã xem");
        assertFalse((a.get(0).getNewValue() + "" + a.get(0).getOldValue()).contains("X-Amz"), "audit không chứa URL");
    }

    @Test
    @DisplayName("B2/A5: ADMIN, GUARD gắn ảnh -> 403; host khác, FM không phải host -> 404 ERR_GUEST_001; xin URL: GUARD 403, host khác 404")
    void otherRoles_forbidden() throws Exception {
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        assertEquals(403, status(attach(admin, v, g, true, "v1")), "ADMIN không còn gắn ảnh");
        assertEquals(403, status(attach(guard, v, g, true, "v1")));
        assertRejected(attach(otherHost, v, g, true, "v1"), 404, "ERR_GUEST_001");
        assertRejected(attach(fm, v, g, true, "v1"), 404, "ERR_GUEST_001");
        assertEquals(403, status(viewUrl(guard, v, g)));
        assertRejected(viewUrl(otherHost, v, g), 404, "ERR_GUEST_001");
        verifyNoInteractions(photoStorage, faceClient);
        assertEquals(GuestBiometricStatus.NO_PHOTO, guest(g.getId()).getBiometricStatus());
    }

    @Test
    @DisplayName("GV-18 + BR-194 (GV-27): lượt REVOKED còn ảnh trong thời hạn lưu -> ADMIN và FM xin được URL xem, audit VIEW_PHOTO; job xoá xong -> 404 ERR_GUEST_037, không audit thêm")
    void viewUrl_revokedVisitWithinRetention_thenDeleted() throws Exception {
        when(photoStorage.presignedGetUrl(any(), anyInt())).thenReturn("http://minio.local/guest-photos/x?X-Amz-Signature=abc");
        int retention = systemConfigService.getInt(ConfigKey.GUEST_FACE_RETENTION_HOURS);
        int ttl = systemConfigService.getInt(ConfigKey.GUEST_PHOTO_URL_TTL_SECONDS);
        OffsetDateTime now = OffsetDateTime.now();
        GuestVisit v = newVisit(hostL2, GuestVisitStatus.APPROVED, now.minusHours(1), now.plusHours(2), List.of(internalArea), "Khách bị thu hồi");
        Guest g = withPhoto(guestsOf(v.getId()).get(0), v.getEndTime().plusHours(retention));
        String key = g.getPhotoObjectKey();
        guestVisitService.revoke(v.getId(), new GuestVisitRevokeRequest(0L, REASON), fm.getEmail());
        assertEquals(GuestVisitStatus.REVOKED, visit(v.getId()).getStatus());

        MvcResult r = viewUrl(admin, v, g);
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        assertTrue(json(r).path("data").path("url").asText().contains("X-Amz-Signature"));
        verify(photoStorage).presignedGetUrl(key, ttl);
        assertEquals(1, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.VIEW_PHOTO).size());
        assertEquals(200, status(viewUrl(fm, v, g)), "B1: FM xem ảnh ở mọi trạng thái còn ảnh");
        assertEquals(2, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.VIEW_PHOTO).size());

        jobService.deleteDueBiometrics(visit(v.getId()).getRevokedAt().plusHours(retention));
        assertEquals(GuestBiometricStatus.DELETED, guest(g.getId()).getBiometricStatus());
        assertRejected(viewUrl(admin, v, g), 404, "ERR_GUEST_037");
        assertEquals(2, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.VIEW_PHOTO).size(),
                "không cấp URL -> không audit thêm");
    }
}
