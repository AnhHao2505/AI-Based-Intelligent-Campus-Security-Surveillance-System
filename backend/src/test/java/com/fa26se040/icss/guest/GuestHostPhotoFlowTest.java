package com.fa26se040.icss.guest;

import com.fa26se040.icss.TestFaces;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.repository.FaceDataRepository;
import com.fa26se040.icss.service.InAppNotificationService;
import com.fa26se040.icss.service.GuestFaceEmbeddingClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Host gắn ảnh khách ngay khi tạo lượt (A1–A4), xoá sinh trắc khi lượt bị từ chối / huỷ trước giờ bắt đầu (A6),
 * kiểm trùng khuôn mặt khi gắn ảnh khách (D1, D2: so với người dùng đang hoạt động, khách khác cùng lượt, và khách của lượt
 * chờ duyệt / đã duyệt chồng giờ), version tăng khi thay ảnh (C3).
 * Dịch vụ trích khuôn mặt và kho ảnh được mock; vector khuôn mặt ngẫu nhiên (TestFaces) để không đụng dữ liệu cũ.
 */
public class GuestHostPhotoFlowTest extends GuestTestSupport {

    private static final String BASE = "/api/guest-visits";

    @Autowired
    private FaceDataRepository faceDataRepository;

    private final List<UUID> createdFaceData = new ArrayList<>();

    @AfterEach
    void removeFaceData() {
        createdFaceData.forEach(faceDataRepository::deleteById);
        createdFaceData.clear();
        setThreshold(ConfigKey.AI_FACE_MATCH_THRESHOLD.getDefaultValue());
    }

    /**
     * Đổi AI_FACE_MATCH_THRESHOLD mà không đẩy cấu hình sang dịch vụ nhận diện (profile test trỏ localhost:8000,
     * có thể là tiến trình thật của máy dev): tạm để trống ai.service.url của SystemConfigService rồi trả lại.
     */
    private void setThreshold(String value) {
        Object target = AopTestUtils.getTargetObject(systemConfigService);
        Object url = ReflectionTestUtils.getField(target, "aiServiceUrl");
        ReflectionTestUtils.setField(target, "aiServiceUrl", "");
        try {
            setConfig(ConfigKey.AI_FACE_MATCH_THRESHOLD, value);
        } finally {
            ReflectionTestUtils.setField(target, "aiServiceUrl", url);
        }
    }

    // ------------------------------------------------------------------ helpers

    private long visitsOf(User host) {
        return guestVisitRepository.findByHostIdOrderByCreatedAtDesc(host.getId(), Pageable.unpaged()).getTotalElements();
    }

    private UUID createdId(MvcResult r) throws Exception {
        assertEquals(201, status(r), r.getResponse().getContentAsString());
        return UUID.fromString(json(r).path("data").path("id").asText());
    }

    private void assertGuestError(MvcResult r, int http, String code, int guestIndex, String guestName) throws Exception {
        assertEquals(http, status(r), r.getResponse().getContentAsString());
        assertEquals(code, errorCode(r), message(r));
        assertTrue(message(r).startsWith("Khách thứ " + (guestIndex + 1) + " (" + guestName + "): "), message(r));
        assertEquals(guestIndex, json(r).path("data").path("guestIndex").asInt(-1), "data.guestIndex chỉ đúng khách lỗi");
    }

    private User userWithFace(String tag, List<Float> face, boolean active) {
        User u = newUser(tag, Role.NORMAL_USER, 2, active);
        FaceData fd = faceDataRepository.save(FaceData.builder()
                .user(u).code(u.getUserCode()).imageFrontUrl("face-profiles/" + u.getUserCode() + ".jpg")
                .embeddingFront(TestFaces.text(face)).build());
        createdFaceData.add(fd.getId());
        return u;
    }

    /** Vector có cosine ≈ target với base (thêm thành phần ngẫu nhiên gần như vuông góc). */
    private static List<Float> withCosine(List<Float> base, double target) {
        List<Float> noise = TestFaces.random();
        double nb = norm(base);
        double nn = norm(noise);
        double b = Math.sqrt(1 - target * target);
        List<Float> v = new ArrayList<>();
        for (int i = 0; i < base.size(); i++) {
            v.add((float) (target * base.get(i) / nb + b * noise.get(i) / nn));
        }
        return v;
    }

    private static double norm(List<Float> v) {
        double s = 0;
        for (Float x : v) s += x * x;
        return Math.sqrt(s);
    }

    @SafeVarargs
    private void faces(List<Float>... faces) {
        var stub = when(faceClient.extract(any(), any(), any()));
        var chain = stub.thenReturn(new GuestFaceEmbeddingClient.Result(1, faces[0]));
        for (int i = 1; i < faces.length; i++) {
            chain = chain.thenReturn(new GuestFaceEmbeddingClient.Result(1, faces[i]));
        }
    }

    // ================================================================== A1–A4: tạo lượt kèm ảnh

    @Test
    @DisplayName("A2–A4: tạo lượt 2 khách kèm ảnh + đồng ý -> 201, cả 2 PHOTO_READY, người xác nhận đồng ý = host, mỗi khách 1 audit ATTACH_PHOTO (actor host), 2 embedding, 2 ảnh lên kho")
    void create_withPhotos_ok() throws Exception {
        MvcResult r = createVisit(hostL2, validBody()).andReturn();
        UUID id = createdId(r);
        JsonNode guests = json(r).path("data").path("guests");
        assertEquals("PHOTO_READY", guests.get(0).path("biometricStatus").asText());
        assertEquals("PHOTO_READY", guests.get(1).path("biometricStatus").asText());
        assertEquals("v1", guests.get(0).path("consentNoticeVersion").asText());
        String raw = r.getResponse().getContentAsString();
        assertFalse(raw.contains("visits/") || raw.contains("objectKey"), "response không chứa key ảnh");

        for (Guest g : guestsOf(id)) {
            assertEquals(GuestBiometricStatus.PHOTO_READY, g.getBiometricStatus());
            assertEquals(hostL2.getId(), transactionTemplate.execute(tx -> guestRepository.findById(g.getId()).orElseThrow()
                    .getConsentConfirmedBy().getId()), "consent_confirmed_by = host");
            assertTrue(embeddingRepository.existsById(g.getId()));
            List<AuditLog> a = audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.ATTACH_PHOTO);
            assertEquals(1, a.size());
            assertEquals(hostL2.getId(), a.get(0).getChangedBy().getId(), "audit ghi đúng host");
        }
        verify(photoStorage, times(2)).upload(anyString(), any(), eq("image/jpeg"));
        verify(faceClient, times(2)).extract(any(), any(), any());
        assertEquals(1, audits(id.toString(), AuditTargetType.GUEST_VISIT, AuditAction.CREATE).size());
    }

    @Test
    @DisplayName("A2: khách thứ 2 ảnh 2 khuôn mặt -> 400 ERR_GUEST_034 nêu đúng khách thứ 2 (data.guestIndex = 1); không tạo lượt, không ảnh nào lên kho")
    void create_secondGuestTwoFaces_rejectedWhole() throws Exception {
        long before = visitsOf(hostL2);
        when(faceClient.extract(any(), any(), any()))
                .thenReturn(new GuestFaceEmbeddingClient.Result(1, TestFaces.random()))
                .thenReturn(new GuestFaceEmbeddingClient.Result(2, TestFaces.random()));
        MvcResult r = createVisit(hostL2, validBody()).andReturn();
        assertGuestError(r, 400, "ERR_GUEST_034", 1, "Trần Thị Mời");
        assertTrue(message(r).contains("phát hiện 2"), message(r));
        assertEquals(before, visitsOf(hostL2), "không tạo lượt nửa vời");
        verify(photoStorage, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("A1/A2: thiếu ảnh khách thứ 2 -> 400 ERR_GUEST_020; khách thứ 1 không tick đồng ý -> 400 ERR_GUEST_031; ảnh sai định dạng / quá dung lượng -> 400 ERR_GUEST_033; thừa ảnh -> 400 ERR_GUEST_040; không gọi trích khuôn mặt, không tạo lượt")
    void create_photoAndConsentRequired() throws Exception {
        long before = visitsOf(hostL2);
        Map<String, Object> body = withConsent(validBody());

        assertGuestError(createVisitRaw(hostL2, body, List.of(photo())).andReturn(), 400, "ERR_GUEST_020", 1, "Trần Thị Mời");

        Map<String, Object> noConsent = withConsent(validBody());
        @SuppressWarnings("unchecked")
        Map<String, Object> g0 = (Map<String, Object>) ((List<?>) noConsent.get("guests")).get(0);
        g0.put("consentConfirmed", false);
        MvcResult consent = createVisitRaw(hostL2, noConsent, List.of(photo(), photo())).andReturn();
        assertGuestError(consent, 400, "ERR_GUEST_031", 0, "Nguyễn Văn Khách");
        assertTrue(message(consent).contains("v1"), message(consent));

        Map<String, Object> oldNotice = new LinkedHashMap<>(body);
        oldNotice.put("consentNoticeVersion", "v0");
        assertGuestError(createVisitRaw(hostL2, oldNotice, List.of(photo(), photo())).andReturn(), 400, "ERR_GUEST_031", 0, "Nguyễn Văn Khách");

        MvcResult gif = createVisitRaw(hostL2, body, List.of(photo(), photo("khach.gif", "image/gif", JPEG))).andReturn();
        assertGuestError(gif, 400, "ERR_GUEST_033", 1, "Trần Thị Mời");
        MvcResult big = createVisitRaw(hostL2, body, List.of(photo("to.jpg", "image/jpeg", new byte[2049 * 1024]), photo())).andReturn();
        assertGuestError(big, 400, "ERR_GUEST_033", 0, "Nguyễn Văn Khách");
        assertTrue(message(big).contains("2048"), message(big));

        MvcResult extra = createVisitRaw(hostL2, body, List.of(photo(), photo(), photo())).andReturn();
        assertEquals(400, status(extra));
        assertEquals("ERR_GUEST_040", errorCode(extra), message(extra));

        verifyNoInteractions(faceClient, photoStorage);
        assertEquals(before, visitsOf(hostL2));
    }

    @Test
    @DisplayName("A3: dịch vụ trích khuôn mặt không chạy -> 503 ERR_GUEST_035 nêu khách, đơn chưa được gửi (không lượt, FM không nhận thông báo)")
    void create_faceServiceDown() throws Exception {
        long before = visitsOf(hostL2);
        when(faceClient.extract(any(), any(), any())).thenThrow(new IllegalStateException("connection refused"));
        assertGuestError(createVisit(hostL2, validBody()).andReturn(), 503, "ERR_GUEST_035", 0, "Nguyễn Văn Khách");
        assertEquals(before, visitsOf(hostL2));
        verify(photoStorage, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("A2: kho ảnh lỗi khi lưu ảnh khách thứ 2 -> 503 ERR_GUEST_036, rollback cả lượt, ảnh khách thứ 1 đã lên kho bị gỡ")
    void create_storageFailsOnSecond_rollsBackAndRemovesFirst() throws Exception {
        long before = visitsOf(hostL2);
        doNothing().doThrow(new RuntimeException("MinIO down")).when(photoStorage).upload(any(), any(), any());
        assertGuestError(createVisit(hostL2, validBody()).andReturn(), 503, "ERR_GUEST_036", 1, "Trần Thị Mời");
        assertEquals(before, visitsOf(hostL2));
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(photoStorage, times(2)).upload(keys.capture(), any(), any());
        verify(photoStorage).removeObject(keys.getAllValues().get(0));
    }

    @Test
    @DisplayName("A1: gửi JSON không kèm ảnh (cách cũ) -> 415, không tạo lượt")
    void create_jsonWithoutPhotos_unsupported() throws Exception {
        long before = visitsOf(hostL2);
        MvcResult r = mockMvc.perform(post(BASE).header("Authorization", bearer(hostL2))
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(withConsent(validBody())))).andReturn();
        assertEquals(415, status(r), r.getResponse().getContentAsString());
        assertEquals(before, visitsOf(hostL2));
    }

    // ================================================================== A6: từ chối / huỷ trước giờ bắt đầu -> xoá ngay

    @Test
    @DisplayName("A6: FM từ chối -> ảnh + embedding của mọi khách bị xoá ngay (DELETED, object xoá sau commit), audit DELETE_BIOMETRIC actor FM")
    void reject_deletesBiometrics() throws Exception {
        UUID id = createdId(createVisit(hostL2, validBody()).andReturn());
        List<String> keys = guestsOf(id).stream().map(Guest::getPhotoObjectKey).toList();
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("version", 0);
        review.put("decision", "REJECTED");
        review.put("reason", REASON);
        MvcResult r = send(patch(BASE + "/" + id + "/review"), fm, review).andReturn();
        assertEquals(200, status(r), r.getResponse().getContentAsString());
        assertEquals(GuestVisitStatus.REJECTED, visit(id).getStatus());
        for (Guest g : guestsOf(id)) {
            assertEquals(GuestBiometricStatus.DELETED, g.getBiometricStatus());
            assertNull(g.getPhotoObjectKey());
            assertNull(g.getPendingDeleteObjectKey(), "xoá trên kho thành công -> bỏ đánh dấu");
            assertFalse(embeddingRepository.existsById(g.getId()));
            List<AuditLog> a = audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC);
            assertEquals(1, a.size());
            assertEquals(fm.getId(), a.get(0).getChangedBy().getId());
        }
        keys.forEach(k -> {
            try {
                verify(photoStorage).removeObject(k);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    @Test
    @DisplayName("A6: host huỷ lượt chờ duyệt (trước giờ bắt đầu) -> xoá ảnh + embedding ngay, audit DELETE_BIOMETRIC actor host")
    void cancelPending_deletesBiometrics() throws Exception {
        UUID id = createdId(createVisit(hostL2, validBody()).andReturn());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", 0);
        assertEquals(200, status(send(patch(BASE + "/" + id + "/cancel"), hostL2, body).andReturn()));
        for (Guest g : guestsOf(id)) {
            assertEquals(GuestBiometricStatus.DELETED, g.getBiometricStatus());
            assertFalse(embeddingRepository.existsById(g.getId()));
            assertEquals(hostL2.getId(), audits(g.getId().toString(), AuditTargetType.GUEST, AuditAction.DELETE_BIOMETRIC)
                    .get(0).getChangedBy().getId());
        }
    }

    // ================================================================== D1, D2: trùng khuôn mặt

    @Test
    @DisplayName("D1/D2: ảnh khách trùng khuôn mặt người dùng đang hoạt động -> 409 ERR_GUEST_038 \"Khuôn mặt này đã được đăng ký cho tài khoản <mã>\", không tạo lượt; người dùng đã khoá thì không tính")
    void create_faceOfActiveUser_blocked() throws Exception {
        List<Float> face = TestFaces.random();
        User owner = userWithFace("own", face, true);
        long before = visitsOf(hostL2);
        faces(TestFaces.random(), TestFaces.sameAs(face));
        MvcResult r = createVisit(hostL2, validBody()).andReturn();
        assertGuestError(r, 409, "ERR_GUEST_038", 1, "Trần Thị Mời");
        assertTrue(message(r).endsWith("Khuôn mặt này đã được đăng ký cho tài khoản " + owner.getUserCode()), message(r));
        assertFalse(message(r).contains("http") || message(r).contains("face-profiles"), "không lộ ảnh người kia");
        assertEquals(before, visitsOf(hostL2));

        List<Float> lockedFace = TestFaces.random();
        userWithFace("off", lockedFace, false);
        faces(TestFaces.sameAs(lockedFace));
        createdId(createVisit(hostL2, createBody(future(0), future(60), List.of(internalArea),
                List.of(guestInput("Khách giống người đã khoá", null)))).andReturn());
    }

    /** Lượt 1 khách (ảnh = face) của otherHost, khung giờ [s, e). */
    private UUID visitWithGuest(User host, OffsetDateTime s, OffsetDateTime e, String guestName, List<Float> face) throws Exception {
        faces(face);
        return createdId(createVisit(host, createBody(s, e, List.of(internalArea), List.of(guestInput(guestName, null)))).andReturn());
    }

    private MvcResult tryVisit(OffsetDateTime s, OffsetDateTime e, List<Float> face) throws Exception {
        faces(face);
        return createVisit(hostL2, createBody(s, e, List.of(internalArea), List.of(guestInput("Khách quay lại", null)))).andReturn();
    }

    @Test
    @DisplayName("D1 (a): cùng một khách ở 2 lượt không chồng giờ -> lượt 2 tạo được, kể cả khi lượt 1 đã duyệt")
    void sameGuest_nonOverlappingVisits_allowed() throws Exception {
        List<Float> face = TestFaces.random();
        OffsetDateTime s = future(0);
        UUID first = visitWithGuest(otherHost, s, s.plusMinutes(60), "Lê Văn Đã Mời", face);
        createdId(tryVisit(s.plusMinutes(120), s.plusMinutes(180), TestFaces.sameAs(face)));

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("version", 0);
        review.put("decision", "APPROVED");
        assertEquals(200, status(send(patch(BASE + "/" + first + "/review"), fm, review).andReturn()));
        createdId(tryVisit(s.plusMinutes(240), s.plusMinutes(300), TestFaces.sameAs(face)));
    }

    @Test
    @DisplayName("D1 (b): cùng khuôn mặt ở 2 lượt chồng giờ (lượt kia chờ duyệt hoặc đã duyệt) -> 409 ERR_GUEST_038 nêu khách + lượt kia (mục đích, khung giờ)")
    void sameGuest_overlappingVisits_blocked() throws Exception {
        List<Float> face = TestFaces.random();
        OffsetDateTime s = future(0);
        UUID first = visitWithGuest(otherHost, s, s.plusMinutes(60), "Lê Văn Đã Mời", face);

        MvcResult pendingOverlap = tryVisit(s.plusMinutes(30), s.plusMinutes(90), TestFaces.sameAs(face));
        assertGuestError(pendingOverlap, 409, "ERR_GUEST_038", 0, "Khách quay lại");
        assertTrue(message(pendingOverlap).endsWith("Khuôn mặt này đã được đăng ký cho khách Lê Văn Đã Mời của lượt \"" + PURPOSE
                + "\" (" + InAppNotificationService.formatTimeRange(s, s.plusMinutes(60)) + ")"), message(pendingOverlap));

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("version", 0);
        review.put("decision", "APPROVED");
        assertEquals(200, status(send(patch(BASE + "/" + first + "/review"), fm, review).andReturn()));
        // lượt mới bao trọn lượt kia cũng là chồng giờ
        MvcResult approvedOverlap = tryVisit(s.minusMinutes(30), s.plusMinutes(90), TestFaces.sameAs(face));
        assertEquals(409, status(approvedOverlap), approvedOverlap.getResponse().getContentAsString());
        assertEquals("ERR_GUEST_038", errorCode(approvedOverlap));
    }

    @Test
    @DisplayName("D1 (c): lượt 1 kết thúc đúng lúc lượt 2 bắt đầu (và ngược lại) -> không tính là chồng giờ, tạo được")
    void sameGuest_touchingWindows_allowed() throws Exception {
        List<Float> face = TestFaces.random();
        OffsetDateTime s = future(60);
        visitWithGuest(otherHost, s, s.plusMinutes(60), "Lê Văn Đã Mời", face);
        createdId(tryVisit(s.plusMinutes(60), s.plusMinutes(120), TestFaces.sameAs(face)));
        createdId(tryVisit(s.minusMinutes(60), s, TestFaces.sameAs(face)));
    }

    @Test
    @DisplayName("D1 (d): lượt 1 chồng giờ nhưng đã bị từ chối / đã huỷ / bị thu hồi -> lượt 2 tạo được")
    void sameGuest_overlappingButRejectedOrCancelled_allowed() throws Exception {
        List<Float> face = TestFaces.random();
        OffsetDateTime s = future(0);
        UUID rejected = visitWithGuest(otherHost, s, s.plusMinutes(60), "Lê Văn Bị Từ Chối", face);
        Map<String, Object> review = new LinkedHashMap<>();
        review.put("version", 0);
        review.put("decision", "REJECTED");
        review.put("reason", REASON);
        assertEquals(200, status(send(patch(BASE + "/" + rejected + "/review"), fm, review).andReturn()));
        createdId(tryVisit(s, s.plusMinutes(60), TestFaces.sameAs(face)));

        List<Float> face2 = TestFaces.random();
        UUID cancelled = visitWithGuest(otherHost, s, s.plusMinutes(60), "Lê Văn Bị Huỷ", face2);
        Map<String, Object> cancel = new LinkedHashMap<>();
        cancel.put("version", 0);
        assertEquals(200, status(send(patch(BASE + "/" + cancelled + "/cancel"), otherHost, cancel).andReturn()));
        createdId(tryVisit(s.plusMinutes(10), s.plusMinutes(50), TestFaces.sameAs(face2)));

        // lượt chồng giờ đã bị thu hồi, còn embedding trong thời hạn lưu -> không tính
        List<Float> oldFace = TestFaces.random();
        GuestVisit revoked = newVisit(otherHost, GuestVisitStatus.APPROVED, s, s.plusMinutes(60), List.of(internalArea), "Lê Văn Bị Thu Hồi");
        // withPhoto đặt đủ cột đồng ý ảnh (CHECK chk_guests_photo_consent), rồi thay embedding bằng khuôn mặt của test
        withPhoto(guestsOf(revoked.getId()).get(0), s.plusHours(80));
        transactionTemplate.executeWithoutResult(tx -> {
            Guest rg = guestsOf(revoked.getId()).get(0);
            GuestFaceEmbedding e = embeddingRepository.findById(rg.getId()).orElseThrow();
            e.setEmbedding(TestFaces.text(oldFace));
            embeddingRepository.save(e);
            GuestVisit y = guestVisitRepository.findById(revoked.getId()).orElseThrow();
            y.setStatus(GuestVisitStatus.REVOKED);
            y.setRevokedBy(fm);
            y.setRevokedAt(OffsetDateTime.now());
            y.setRevokeReason(REASON);
            guestVisitRepository.save(y);
        });
        createdId(tryVisit(s, s.plusMinutes(60), TestFaces.sameAs(oldFace)));
    }

    @Test
    @DisplayName("D1: thay ảnh cũng áp quy tắc chồng giờ — thay ảnh khách lượt 2 bằng khuôn mặt khách của lượt 1 chồng giờ -> 409; không chồng giờ -> được")
    void replace_overlapRuleApplies() throws Exception {
        List<Float> face = TestFaces.random();
        OffsetDateTime s = future(0);
        visitWithGuest(otherHost, s, s.plusMinutes(60), "Lê Văn Đã Mời", face);

        UUID overlapping = createdId(tryVisit(s.plusMinutes(30), s.plusMinutes(90), TestFaces.random()));
        UUID separate = createdId(tryVisit(s.plusMinutes(120), s.plusMinutes(180), TestFaces.random()));

        for (UUID id : List.of(overlapping, separate)) {
            Guest g = guestsOf(id).get(0);
            faces(TestFaces.sameAs(face));
            MvcResult r = mockMvc.perform(multipart(BASE + "/" + id + "/guests/" + g.getId() + "/photo")
                    .file(new MockMultipartFile("file", "moi.jpg", "image/jpeg", JPEG))
                    .param("consentConfirmed", "true").param("consentNoticeVersion", "v1")
                    .header("Authorization", bearer(hostL2))).andReturn();
            if (id.equals(overlapping)) {
                assertEquals(409, status(r), r.getResponse().getContentAsString());
                assertTrue(message(r).contains("khách Lê Văn Đã Mời của lượt"), message(r));
            } else {
                assertEquals(200, status(r), r.getResponse().getContentAsString());
            }
        }
    }

    @Test
    @DisplayName("D1: host thay ảnh khách thứ 2 bằng khuôn mặt của khách thứ 1 cùng lượt -> 409 ERR_GUEST_038 \"khách <tên> trong cùng lượt này\", ảnh cũ giữ nguyên")
    void replace_withFaceOfOtherGuestInSameVisit_blocked() throws Exception {
        List<Float> faceA = TestFaces.random();
        faces(faceA, TestFaces.random());
        UUID id = createdId(createVisit(hostL2, validBody()).andReturn());
        Guest second = guestsOf(id).stream().filter(g -> g.getFullName().equals("Trần Thị Mời")).findFirst().orElseThrow();
        String key = second.getPhotoObjectKey();

        faces(TestFaces.sameAs(faceA));
        MvcResult r = mockMvc.perform(multipart(BASE + "/" + id + "/guests/" + second.getId() + "/photo")
                .file(new MockMultipartFile("file", "moi.jpg", "image/jpeg", JPEG))
                .param("consentConfirmed", "true").param("consentNoticeVersion", "v1")
                .header("Authorization", bearer(hostL2))).andReturn();
        assertEquals(409, status(r), r.getResponse().getContentAsString());
        assertEquals("ERR_GUEST_038", errorCode(r));
        assertEquals("Khuôn mặt này đã được đăng ký cho khách Nguyễn Văn Khách trong cùng lượt này", message(r));
        assertEquals(key, guest(second.getId()).getPhotoObjectKey());
    }

    @Test
    @DisplayName("D1: hai khách trong cùng lượt cùng khuôn mặt -> 409 ERR_GUEST_038 nêu khách thứ 2 trùng khách thứ 1 \"trong cùng lượt này\"")
    void create_twoGuestsSameFace_blocked() throws Exception {
        List<Float> face = TestFaces.random();
        faces(face, TestFaces.sameAs(face));
        MvcResult r = createVisit(hostL2, validBody()).andReturn();
        assertGuestError(r, 409, "ERR_GUEST_038", 1, "Trần Thị Mời");
        assertTrue(message(r).contains("khách Nguyễn Văn Khách trong cùng lượt này"), message(r));
        verify(photoStorage, never()).upload(any(), any(), any());
    }

    @Test
    @DisplayName("D1: ngưỡng đọc từ AI_FACE_MATCH_THRESHOLD — cosine ≈ 0.60: ngưỡng 0.70 cho qua, ngưỡng 0.50 chặn")
    void threshold_fromSystemConfiguration() throws Exception {
        List<Float> face = TestFaces.random();
        userWithFace("thr", face, true);
        List<Float> similar = withCosine(face, 0.60);

        setThreshold("0.70");
        faces(similar);
        createdId(createVisit(hostL2, createBody(future(0), future(60), List.of(internalArea),
                List.of(guestInput("Khách hơi giống", null)))).andReturn());

        setThreshold("0.50");
        faces(withCosine(face, 0.60));
        MvcResult r = createVisit(hostL2, createBody(future(120), future(180), List.of(internalArea),
                List.of(guestInput("Khách hơi giống", null)))).andReturn();
        assertEquals(409, status(r), r.getResponse().getContentAsString());
        assertEquals("ERR_GUEST_038", errorCode(r));
    }

    @Test
    @DisplayName("D1/A5: host thay ảnh khách bằng khuôn mặt của người dùng khác -> 409 ERR_GUEST_038, ảnh cũ giữ nguyên")
    void replace_withFaceOfUser_blocked() throws Exception {
        UUID id = createdId(createVisit(hostL2, createBody(future(0), future(60), List.of(internalArea),
                List.of(guestInput("Khách thay ảnh", null)))).andReturn());
        Guest g = guestsOf(id).get(0);
        String key = g.getPhotoObjectKey();
        List<Float> face = TestFaces.random();
        User owner = userWithFace("rep", face, true);
        faces(TestFaces.sameAs(face));
        MvcResult r = mockMvc.perform(multipart(BASE + "/" + id + "/guests/" + g.getId() + "/photo")
                .file(new MockMultipartFile("file", "moi.jpg", "image/jpeg", JPEG))
                .param("consentConfirmed", "true").param("consentNoticeVersion", "v1")
                .header("Authorization", bearer(hostL2))).andReturn();
        assertEquals(409, status(r), r.getResponse().getContentAsString());
        assertEquals("ERR_GUEST_038", errorCode(r));
        assertTrue(message(r).contains("tài khoản " + owner.getUserCode()), message(r));
        assertEquals(key, guest(g.getId()).getPhotoObjectKey());
        assertEquals(0L, visit(id).getVersion());
    }

    // ================================================================== C3: thay ảnh -> FM đang xem bản cũ phải tải lại

    @Test
    @DisplayName("C3: host thay ảnh sau khi FM mở danh sách -> FM duyệt bằng version cũ nhận 409 ERR_GUEST_016, lượt vẫn PENDING; tải lại rồi duyệt được")
    void replaceThenReviewWithOldVersion_conflict() throws Exception {
        UUID id = createdId(createVisit(hostL2, createBody(future(0), future(60), List.of(internalArea),
                List.of(guestInput("Khách đổi ảnh", null)))).andReturn());
        Guest g = guestsOf(id).get(0);
        MvcResult replaced = mockMvc.perform(multipart(BASE + "/" + id + "/guests/" + g.getId() + "/photo")
                .file(new MockMultipartFile("file", "moi.jpg", "image/jpeg", JPEG))
                .param("consentConfirmed", "true").param("consentNoticeVersion", "v1")
                .header("Authorization", bearer(hostL2))).andReturn();
        assertEquals(200, status(replaced), replaced.getResponse().getContentAsString());

        Map<String, Object> review = new LinkedHashMap<>();
        review.put("version", 0);
        review.put("decision", "APPROVED");
        MvcResult stale = send(patch(BASE + "/" + id + "/review"), fm, review).andReturn();
        assertEquals(409, status(stale));
        assertEquals("ERR_GUEST_016", errorCode(stale));
        assertEquals(GuestVisitStatus.PENDING, visit(id).getStatus());

        review.put("version", visit(id).getVersion());
        assertEquals(200, status(send(patch(BASE + "/" + id + "/review"), fm, review).andReturn()));
        assertEquals(GuestVisitStatus.APPROVED, visit(id).getStatus());
    }
}
