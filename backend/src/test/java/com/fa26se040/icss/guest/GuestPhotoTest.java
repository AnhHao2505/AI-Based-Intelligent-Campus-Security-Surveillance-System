package com.fa26se040.icss.guest;

import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.service.GuestFaceEmbeddingClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * P4 — ADMIN gắn ảnh khách (đã đồng ý) vào kho riêng, xem ảnh qua URL có hạn (BR-GV-14..18, 30).
 * Kho MinIO và ai-service được mock (dự án chưa có container cho test; test MinIO hiện có đều dùng Mockito).
 */
public class GuestPhotoTest extends GuestTestSupport {

    private static final byte[] JPEG = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x10, 0x20, 0x30};

    private static List<Float> vec(float v) {
        List<Float> l = new ArrayList<>();
        for (int i = 0; i < 512; i++) {
            l.add(v);
        }
        return l;
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
    @DisplayName("GV-14..17: ADMIN gắn ảnh đúng đồng ý -> PHOTO_READY, lưu ADMIN + thời điểm + phiên bản, embedding bảng riêng expires = end + 24h, audit ATTACH_PHOTO, response không có key / URL")
    void attach_ok() throws Exception {
        when(faceClient.extract(any(), any(), any())).thenReturn(new GuestFaceEmbeddingClient.Result(1, vec(0.02f)));
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        MvcResult r = attach(admin, v, g, true, "v1");
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        String raw = r.getResponse().getContentAsString();
        assertFalse(raw.contains("visits/") || raw.contains("http://") || raw.contains("https://") || raw.contains("objectKey"), raw);

        Guest after = guest(g.getId());
        assertEquals(GuestBiometricStatus.PHOTO_READY, after.getBiometricStatus());
        assertNotNull(after.getPhotoObjectKey());
        assertEquals(admin.getId(), after.getConsentConfirmedBy().getId());
        assertNotNull(after.getConsentConfirmedAt());
        assertEquals("v1", after.getConsentNoticeVersion());
        GuestFaceEmbedding e = embeddingRepository.findById(g.getId()).orElseThrow();
        assertEquals(0, Duration.between(v.getEndTime().plusHours(24), e.getExpiresAt()).getSeconds(), "expires_at = end + GUEST_FACE_RETENTION_HOURS");
        verify(photoStorage).upload(eq(after.getPhotoObjectKey()), any(), eq("image/jpeg"));

        List<AuditLog> a = audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.ATTACH_PHOTO);
        assertEquals(1, a.size());
        String snap = String.valueOf(a.get(0).getNewValue()) + a.get(0).getOldValue();
        assertFalse(snap.contains(after.getPhotoObjectKey()) || snap.contains("embedding") || snap.contains("http"), "snapshot không chứa ảnh / URL / embedding: " + snap);
    }

    @Test
    @DisplayName("GV-15: thiếu / false đồng ý, sai phiên bản -> 400 ERR_GUEST_031 (câu nêu phiên bản hiện hành); đổi config sang v2 thì v2 mới hợp lệ")
    void attach_consent() throws Exception {
        when(faceClient.extract(any(), any(), any())).thenReturn(new GuestFaceEmbeddingClient.Result(1, vec(0.02f)));
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        assertRejected(attach(admin, v, g, null, "v1"), 400, "ERR_GUEST_031");
        assertRejected(attach(admin, v, g, false, "v1"), 400, "ERR_GUEST_031");
        MvcResult wrong = attach(admin, v, g, true, "v0");
        assertRejected(wrong, 400, "ERR_GUEST_031");
        assertTrue(message(wrong).contains("v1"), message(wrong));
        assertRejected(attach(admin, v, g, true, null), 400, "ERR_GUEST_031");
        setConfig(ConfigKey.GUEST_CONSENT_NOTICE_VERSION, "v2");
        assertRejected(attach(admin, v, g, true, "v1"), 400, "ERR_GUEST_031");
        assertEquals(200, status(attach(admin, v, g, true, "v2")));
        assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus());
        verify(faceClient, times(1)).extract(any(), any(), any());
    }

    @Test
    @DisplayName("GV-14: lượt PENDING / APPROVED đã qua end -> 409 ERR_GUEST_030; khách không thuộc lượt -> 404 ERR_GUEST_032")
    void attach_visitState() throws Exception {
        when(faceClient.extract(any(), any(), any())).thenReturn(new GuestFaceEmbeddingClient.Result(1, vec(0.02f)));
        GuestVisit pending = newVisit(hostL2, GuestVisitStatus.PENDING, future(0), future(60), List.of(internalArea), "Khách chờ");
        assertRejected(attach(admin, pending, guestsOf(pending.getId()).get(0), true, "v1"), 409, "ERR_GUEST_030");
        GuestVisit ended = newVisit(hostL2, GuestVisitStatus.APPROVED, OffsetDateTime.now().minusHours(3), OffsetDateTime.now().minusHours(1),
                List.of(internalArea), "Khách cũ");
        assertRejected(attach(admin, ended, guestsOf(ended.getId()).get(0), true, "v1"), 409, "ERR_GUEST_030");
        GuestVisit other = approved();
        assertRejected(attach(admin, other, guestsOf(pending.getId()).get(0), true, "v1"), 404, "ERR_GUEST_032");
        verifyNoInteractions(photoStorage);
    }

    @Test
    @DisplayName("GV-16: gắn lại -> xoá ảnh cũ trên kho + embedding cũ (audit DELETE_BIOMETRIC), còn đúng 1 ảnh / 1 embedding")
    void attach_again_replaces() throws Exception {
        when(faceClient.extract(any(), any(), any()))
                .thenReturn(new GuestFaceEmbeddingClient.Result(1, vec(0.02f)))
                .thenReturn(new GuestFaceEmbeddingClient.Result(1, vec(0.05f)));
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        assertEquals(200, status(attach(admin, v, g, true, "v1")));
        String firstKey = guest(g.getId()).getPhotoObjectKey();
        String firstVec = embeddingRepository.findById(g.getId()).orElseThrow().getEmbedding();
        assertEquals(200, status(attach(admin, v, g, true, "v1")));
        Guest after = guest(g.getId());
        assertNotEquals(firstKey, after.getPhotoObjectKey());
        verify(photoStorage).removeObject(firstKey);
        assertNotEquals(firstVec, embeddingRepository.findById(g.getId()).orElseThrow().getEmbedding(), "embedding cũ bị thay");
        assertEquals(1, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC).size());
        assertEquals(2, audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.ATTACH_PHOTO).size());
    }

    @Test
    @DisplayName("GV-14/17: ảnh 0 hoặc 2 khuôn mặt -> 400 ERR_GUEST_034; ai-service lỗi -> 503 ERR_GUEST_035; kho lỗi -> 503 ERR_GUEST_036; không lưu gì")
    void attach_failures() throws Exception {
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        when(faceClient.extract(any(), any(), any())).thenReturn(new GuestFaceEmbeddingClient.Result(2, vec(0.02f)));
        assertRejected(attach(admin, v, g, true, "v1"), 400, "ERR_GUEST_034");
        when(faceClient.extract(any(), any(), any())).thenReturn(new GuestFaceEmbeddingClient.Result(0, List.of()));
        assertRejected(attach(admin, v, g, true, "v1"), 400, "ERR_GUEST_034");
        when(faceClient.extract(any(), any(), any())).thenThrow(new IllegalStateException("AI down"));
        assertRejected(attach(admin, v, g, true, "v1"), 503, "ERR_GUEST_035");
        reset(faceClient);
        when(faceClient.extract(any(), any(), any())).thenReturn(new GuestFaceEmbeddingClient.Result(1, vec(0.02f)));
        doThrow(new RuntimeException("MinIO down")).when(photoStorage).upload(any(), any(), any());
        assertRejected(attach(admin, v, g, true, "v1"), 503, "ERR_GUEST_036");
        Guest after = guest(g.getId());
        assertEquals(GuestBiometricStatus.NO_PHOTO, after.getBiometricStatus());
        assertNull(after.getPhotoObjectKey());
        assertFalse(embeddingRepository.existsById(g.getId()));
    }

    @Test
    @DisplayName("GV-18: ADMIN xin URL xem ảnh -> URL có hạn GUEST_PHOTO_URL_TTL_SECONDS, mỗi lần cấp ghi 1 audit VIEW_PHOTO (không chứa URL); khách chưa có ảnh -> 404 ERR_GUEST_037")
    void viewUrl() throws Exception {
        when(faceClient.extract(any(), any(), any())).thenReturn(new GuestFaceEmbeddingClient.Result(1, vec(0.02f)));
        when(photoStorage.presignedGetUrl(any(), anyInt())).thenReturn("http://minio.local/guest-photos/x?X-Amz-Signature=abc");
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        assertRejected(viewUrl(admin, v, g), 404, "ERR_GUEST_037");
        assertEquals(200, status(attach(admin, v, g, true, "v1")));
        setConfig(ConfigKey.GUEST_PHOTO_URL_TTL_SECONDS, "45");
        MvcResult r1 = viewUrl(admin, v, g);
        assertEquals(200, status(r1), r1.getResponse().getContentAsString());
        assertEquals(45, json(r1).path("data").path("expiresInSeconds").asInt());
        assertTrue(json(r1).path("data").path("url").asText().contains("X-Amz-Signature"));
        verify(photoStorage).presignedGetUrl(guest(g.getId()).getPhotoObjectKey(), 45);
        assertEquals(200, status(viewUrl(admin, v, g)));
        List<AuditLog> a = audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.VIEW_PHOTO);
        assertEquals(2, a.size());
        assertFalse((a.get(0).getNewValue() + "" + a.get(0).getOldValue()).contains("X-Amz"), "audit không chứa URL");
    }

    @Test
    @DisplayName("GV-14/18: FM, NORMAL_USER (kể cả host), GUARD gắn ảnh / xin URL -> 403")
    void otherRoles_forbidden() throws Exception {
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        assertEquals(403, status(attach(fm, v, g, true, "v1")));
        assertEquals(403, status(attach(hostL2, v, g, true, "v1")));
        assertEquals(403, status(attach(guard, v, g, true, "v1")));
        assertEquals(403, status(viewUrl(fm, v, g)));
        assertEquals(403, status(viewUrl(hostL2, v, g)));
        verifyNoInteractions(photoStorage, faceClient);
    }
}
