package com.fa26se040.icss.guest;

import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.service.GuestBiometricService;
import com.fa26se040.icss.service.GuestFaceEmbeddingClient;
import com.fa26se040.icss.service.GuestPhotoService;
import com.fa26se040.icss.dto.audit.AuditActor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * A9 — xoá object ảnh trên MinIO chỉ chạy SAU khi transaction commit (BR-GV-27); giới hạn ảnh 2048 KB (property).
 */
public class GuestPhotoAfterCommitTest extends GuestTestSupport {

    @Autowired private GuestPhotoService photoService;
    @Autowired private GuestBiometricService biometricService;

    private static List<Float> vec(float v) {
        List<Float> l = new ArrayList<>();
        for (int i = 0; i < 512; i++) l.add(v);
        return l;
    }

    private MockMultipartFile jpeg(int bytes) {
        return new MockMultipartFile("file", "khach.jpg", "image/jpeg", new byte[bytes]);
    }

    private GuestVisit approved() {
        return newVisit(hostL2, GuestVisitStatus.APPROVED, future(0), future(120), List.of(internalArea), "Nguyễn Văn Khách");
    }

    private MvcResult attachHttp(GuestVisit v, Guest g, MockMultipartFile f) throws Exception {
        return mockMvc.perform(multipart("/api/guest-visits/" + v.getId() + "/guests/" + g.getId() + "/photo").file(f)
                .param("consentConfirmed", "true").param("consentNoticeVersion", "v1")
                .header("Authorization", bearer(admin))).andReturn();
    }

    @Test
    @DisplayName("A9: transaction rollback sau khi gắn lại ảnh -> object cũ KHÔNG bị xoá, DB vẫn trỏ ảnh + embedding cũ, object mới bị gỡ")
    void reattach_rollback_keepsOldObject() throws Exception {
        when(faceClient.extract(any(), any(), any()))
                .thenReturn(new GuestFaceEmbeddingClient.Result(1, vec(0.02f)))
                .thenReturn(new GuestFaceEmbeddingClient.Result(1, vec(0.05f)));
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        assertEquals(200, status(attachHttp(v, g, jpeg(1000))));
        String oldKey = guest(g.getId()).getPhotoObjectKey();
        String oldVec = embeddingRepository.findById(g.getId()).orElseThrow().getEmbedding();
        clearInvocations(photoStorage);

        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(tx -> {
            photoService.attach(v.getId(), g.getId(), jpeg(1000), true, "v1", admin.getEmail());
            throw new IllegalStateException("rollback giả lập sau khi gắn lại ảnh");
        }));

        verify(photoStorage, never()).removeObject(oldKey);
        ArgumentCaptor<String> uploaded = ArgumentCaptor.forClass(String.class);
        verify(photoStorage).upload(uploaded.capture(), any(), any());
        verify(photoStorage).removeObject(uploaded.getValue());
        Guest after = guest(g.getId());
        assertEquals(GuestBiometricStatus.PHOTO_READY, after.getBiometricStatus());
        assertEquals(oldKey, after.getPhotoObjectKey());
        assertNull(after.getPendingDeleteObjectKey());
        assertEquals(oldVec, embeddingRepository.findById(g.getId()).orElseThrow().getEmbedding());
    }

    @Test
    @DisplayName("A9: gắn lại commit xong -> xoá object cũ sau commit, bỏ đánh dấu; xoá lỗi -> giữ đánh dấu cần xoá cho job")
    void reattach_commit_deletesAfterCommit() throws Exception {
        when(faceClient.extract(any(), any(), any())).thenReturn(new GuestFaceEmbeddingClient.Result(1, vec(0.02f)));
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        assertEquals(200, status(attachHttp(v, g, jpeg(1000))));
        String first = guest(g.getId()).getPhotoObjectKey();
        assertEquals(200, status(attachHttp(v, g, jpeg(1000))));
        verify(photoStorage).removeObject(first);
        assertNull(guest(g.getId()).getPendingDeleteObjectKey());

        String second = guest(g.getId()).getPhotoObjectKey();
        doThrow(new RuntimeException("MinIO down")).when(photoStorage).removeObject(second);
        assertEquals(200, status(attachHttp(v, g, jpeg(1000))));
        assertEquals(second, guest(g.getId()).getPendingDeleteObjectKey(), "xoá sau commit lỗi -> job thử lại");
    }

    @Test
    @DisplayName("A9: xoá sinh trắc trong transaction bị rollback -> không xoá object, khách vẫn PHOTO_READY")
    void deleteBiometrics_rollback_keepsObject() throws Exception {
        GuestVisit v = approved();
        Guest g = withPhoto(guestsOf(v.getId()).get(0), future(120).plusHours(24));
        String key = g.getPhotoObjectKey();
        assertThrows(IllegalStateException.class, () -> transactionTemplate.executeWithoutResult(tx -> {
            Guest x = guestRepository.findById(g.getId()).orElseThrow();
            biometricService.deleteBiometrics(x, OffsetDateTime.now(), AuditActor.system("TEST"), "thử rollback");
            throw new IllegalStateException("rollback giả lập");
        }));
        verify(photoStorage, never()).removeObject(key);
        assertEquals(GuestBiometricStatus.PHOTO_READY, guest(g.getId()).getBiometricStatus());
        assertTrue(embeddingRepository.existsById(g.getId()));
    }

    @Test
    @DisplayName("Giới hạn ảnh icss.guest.photo-max-kb = 2048: đúng 2048 KB nhận, 2049 KB từ chối ERR_GUEST_033 (câu nêu 2048)")
    void photoSizeLimit() throws Exception {
        when(faceClient.extract(any(), any(), any())).thenReturn(new GuestFaceEmbeddingClient.Result(1, vec(0.02f)));
        GuestVisit v = approved();
        Guest g = guestsOf(v.getId()).get(0);
        assertEquals(200, status(attachHttp(v, g, jpeg(2048 * 1024))));
        MvcResult tooBig = attachHttp(v, g, jpeg(2049 * 1024));
        assertEquals(400, status(tooBig));
        assertEquals("ERR_GUEST_033", errorCode(tooBig));
        assertTrue(message(tooBig).contains("2048"), message(tooBig));
    }
}
