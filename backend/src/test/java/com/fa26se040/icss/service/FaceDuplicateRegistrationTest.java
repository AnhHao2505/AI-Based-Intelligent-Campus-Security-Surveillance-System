package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.TestFaces;
import com.fa26se040.icss.entity.FaceData;
import com.fa26se040.icss.entity.Guest;
import com.fa26se040.icss.entity.GuestFaceEmbedding;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.GuestBiometricStatus;
import com.fa26se040.icss.enums.GuestVisitStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.exception.FaceDuplicateException;
import com.fa26se040.icss.exception.GuestErrorCode;
import com.fa26se040.icss.exception.GuestException;
import com.fa26se040.icss.repository.FaceDataRepository;
import com.fa26se040.icss.repository.GuestFaceEmbeddingRepository;
import com.fa26se040.icss.repository.GuestVisitRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * D1, D2 phía người dùng: đăng ký khuôn mặt khi tạo tài khoản / import ZIP bị chặn (409 ERR_FACE_001, face-gate 5) nếu
 * trùng khuôn mặt của người dùng chưa xoá mềm, kể cả đang bị vô hiệu hoá (face-gate 2), hoặc khách của lượt còn hiệu lực;
 * dòng import trùng lỗi, dòng khác vẫn chạy (kể cả trùng nhau trong cùng file); thay ảnh của chính mình không bị coi là
 * trùng. face-gate 1: dịch vụ AI trả thiếu dữ liệu -> chặn. face-gate 3: kích hoạt lại / khôi phục lô kiểm lại trùng.
 * face-gate 4: hai lượt đăng ký cùng khuôn mặt cùng lúc -> đúng 1 lượt thành công.
 * Dịch vụ trích khuôn mặt giả bằng MockRestServiceServer; MinIO và mail là mock. Vector khuôn mặt ngẫu nhiên (TestFaces).
 */
class FaceDuplicateRegistrationTest extends AbstractIntegrationTest {

    private static final String AI_PATH = "/api/v1/faces/process-registration";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FaceDataService faceDataService;
    @Autowired private UserService userService;
    @Autowired private FaceDataRepository faceDataRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private GuestVisitRepository guestVisitRepository;
    @Autowired private GuestFaceEmbeddingRepository embeddingRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private MinioStorageService minioStorageService;
    @MockBean private NotificationService notificationService;

    private AiServiceRestTemplate aiRestTemplate;
    private ClientHttpRequestFactory originalFactory;
    private MockRestServiceServer aiServer;

    private String suffix;
    private User admin;
    private final List<String> codes = new ArrayList<>();
    private final List<UUID> guestIds = new ArrayList<>();

    @BeforeAll
    void bindAiServer() {
        aiRestTemplate = (AiServiceRestTemplate) ReflectionTestUtils.getField(faceDataService, "restTemplate");
        originalFactory = (ClientHttpRequestFactory) ReflectionTestUtils.getField(aiRestTemplate, "requestFactory");
        aiServer = MockRestServiceServer.bindTo(aiRestTemplate).build();
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        admin = userRepository.save(User.builder()
                .userCode("FDUP-ADM-" + suffix).fullName("Face dup admin " + suffix)
                .email("fdup-adm-" + suffix.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                .role(Role.ADMIN).accessLevel(3).isActive(true).build());
    }

    @BeforeEach
    void stubMinio() {
        aiServer.reset();
        when(minioStorageService.uploadFaceImage(any(), anyString()))
                .thenAnswer(inv -> "face-profiles/" + inv.getArgument(1, String.class) + ".jpg");
    }

    @AfterAll
    void cleanUp() {
        aiRestTemplate.setRequestFactory(originalFactory);
        // audit_logs append-only giữ FK tới users -> không xoá user, chỉ khoá
        for (String code : codes) {
            faceDataRepository.findByCode(code).ifPresent(faceDataRepository::delete);
            jdbc.update("UPDATE users SET is_active = false WHERE UPPER(user_code) = UPPER(?)", code);
        }
        guestIds.forEach(id -> embeddingRepository.findById(id).ifPresent(embeddingRepository::delete));
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", admin.getId());
    }

    // ------------------------------------------------------------------ helpers

    private String newCode(String tag) {
        String code = "FDUP-" + tag + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        codes.add(code);
        return code;
    }

    private User userWithFace(String tag, List<Float> face, boolean active) {
        String code = newCode(tag);
        User u = userRepository.save(User.builder().userCode(code).fullName("Face dup " + tag)
                .email(code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn").role(Role.NORMAL_USER).accessLevel(1)
                .isActive(active).build());
        faceDataRepository.save(FaceData.builder().user(u).code(code).imageFrontUrl("face-profiles/" + code + ".jpg")
                .embeddingFront(TestFaces.text(face)).build());
        return u;
    }

    private void expectAi(String code, List<Float> face) {
        aiServer.expect(requestTo(endsWith(AI_PATH)))
                .andRespond(withSuccess(TestFaces.registrationBody(code, 1, face), MediaType.APPLICATION_JSON));
    }

    private MvcResult createStaff(String code) throws Exception {
        return mockMvc.perform(multipart("/api/users/staff-accounts")
                        .file(new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, 1}))
                        .param("fullName", "Face dup " + code)
                        .param("userCode", code)
                        .param("email", code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                        .param("role", Role.NORMAL_USER.name())
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin)))
                .andReturn();
    }

    private String message(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("message").asText("");
    }

    private String errorCode(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("errorCode").asText("");
    }

    private boolean isActive(UUID userId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT is_active FROM users WHERE id = ?", Boolean.class, userId));
    }

    private String bearer() {
        return "Bearer " + jwtTokenProvider.generateToken(admin);
    }

    private int userCount(String code) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users WHERE UPPER(user_code) = UPPER(?)", Integer.class, code);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ tạo tài khoản

    @Test
    @DisplayName("D1/D2, face-gate 5: tạo tài khoản với khuôn mặt của người dùng đang hoạt động -> 409 ERR_FACE_001 \"Khuôn mặt này đã được đăng ký cho tài khoản <mã>\", không tạo tài khoản / face_data, ảnh không lên kho")
    void createStaff_faceOfActiveUser_blocked() throws Exception {
        List<Float> face = TestFaces.random();
        User owner = userWithFace("OWN", face, true);
        String code = newCode("NEW");
        expectAi(code, TestFaces.sameAs(face));

        MvcResult r = createStaff(code);

        aiServer.verify();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo(FaceDuplicateException.CODE);
        assertThat(message(r)).isEqualTo("Khuôn mặt này đã được đăng ký cho tài khoản " + owner.getUserCode());
        assertThat(message(r)).doesNotContain("face-profiles").doesNotContain("http");
        assertThat(userCount(code)).isZero();
        assertThat(faceDataRepository.findByCode(code)).isEmpty();
        verify(minioStorageService, never()).uploadFaceImage(any(), anyString());
    }

    @Test
    @DisplayName("face-gate 2 (1A): khuôn mặt của người dùng đang bị vô hiệu hoá vẫn tính -> 409 ERR_FACE_001 nêu mã + \"(đang bị vô hiệu hoá)\", không tạo tài khoản")
    void createStaff_faceOfInactiveUser_blocked() throws Exception {
        List<Float> face = TestFaces.random();
        User locked = userWithFace("OFF", face, false);
        String code = newCode("NEW2");
        expectAi(code, TestFaces.sameAs(face));

        MvcResult r = createStaff(code);

        aiServer.verify();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo(FaceDuplicateException.CODE);
        assertThat(message(r)).isEqualTo("Khuôn mặt này đã được đăng ký cho tài khoản " + locked.getUserCode()
                + " (đang bị vô hiệu hoá)");
        assertThat(userCount(code)).isZero();
        assertThat(faceDataRepository.findByCode(code)).isEmpty();
    }

    @Test
    @DisplayName("face-gate 1: dịch vụ AI trả thiếu face_count -> 503, không tạo tài khoản / face_data, ảnh không lên kho")
    void createStaff_aiMissingFaceCount_blocked() throws Exception {
        String code = newCode("NOCNT");
        aiServer.expect(requestTo(endsWith(AI_PATH))).andRespond(withSuccess(
                "{\"success\":true,\"code\":\"" + code + "\",\"embedding_front\":[" + TestFaces.csv(TestFaces.random()) + "]}",
                MediaType.APPLICATION_JSON));

        MvcResult r = createStaff(code);

        aiServer.verify();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(503);
        assertThat(userCount(code)).isZero();
        assertThat(faceDataRepository.findByCode(code)).isEmpty();
        verify(minioStorageService, never()).uploadFaceImage(any(), anyString());
    }

    @Test
    @DisplayName("face-gate 1: dịch vụ AI trả vector không đủ 512 chiều (rỗng / 3 chiều) -> 503, không tạo tài khoản / face_data")
    void createStaff_aiShortVector_blocked() throws Exception {
        for (String vector : List.of("", "0.12,-0.34,0.56")) {
            aiServer.reset();
            String code = newCode("SHORT");
            aiServer.expect(requestTo(endsWith(AI_PATH))).andRespond(withSuccess(
                    "{\"success\":true,\"code\":\"" + code + "\",\"face_count\":1,\"embedding_front\":[" + vector + "]}",
                    MediaType.APPLICATION_JSON));

            MvcResult r = createStaff(code);

            aiServer.verify();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(503);
            assertThat(userCount(code)).isZero();
            assertThat(faceDataRepository.findByCode(code)).isEmpty();
        }
        verify(minioStorageService, never()).uploadFaceImage(any(), anyString());
    }

    @Test
    @DisplayName("D1/D2: tạo tài khoản với khuôn mặt của khách thuộc lượt đã duyệt chưa kết thúc -> 409 nêu \"khách <tên> của lượt <mục đích> (<thời gian>)\"")
    void createStaff_faceOfGuestInLiveVisit_blocked() throws Exception {
        List<Float> face = TestFaces.random();
        User host = userWithFace("HOST", TestFaces.random(), true);
        OffsetDateTime start = OffsetDateTime.now().plusDays(1);
        UUID guestId = transactionTemplate.execute(tx -> {
            // đủ cột theo CHECK: lượt APPROVED có người duyệt; khách PHOTO_READY có ảnh + người xác nhận đồng ý
            GuestVisit v = GuestVisit.builder().host(host).purpose("Họp đối tác kiểm trùng khuôn mặt")
                    .startTime(start).endTime(start.plusHours(2)).status(GuestVisitStatus.APPROVED)
                    .reviewedBy(admin).reviewedAt(OffsetDateTime.now()).build();
            v.getGuests().add(Guest.builder().visit(v).fullName("Phạm Khách Trùng")
                    .biometricStatus(GuestBiometricStatus.PHOTO_READY).photoObjectKey("visits/test/khach.jpg")
                    .consentConfirmedBy(host).consentConfirmedAt(OffsetDateTime.now()).consentNoticeVersion("v1")
                    .photoAttachedAt(OffsetDateTime.now()).build());
            GuestVisit saved = guestVisitRepository.save(v);
            UUID gid = saved.getGuests().get(0).getId();
            embeddingRepository.save(GuestFaceEmbedding.builder().guestId(gid).embedding(TestFaces.text(face))
                    .expiresAt(start.plusHours(80)).build());
            return gid;
        });
        guestIds.add(guestId);
        String code = newCode("NEW3");
        expectAi(code, TestFaces.sameAs(face));

        MvcResult r = createStaff(code);

        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo(FaceDuplicateException.CODE);
        assertThat(message(r)).startsWith("Khuôn mặt này đã được đăng ký cho khách Phạm Khách Trùng của lượt \"Họp đối tác kiểm trùng khuôn mặt\" (");
        assertThat(userCount(code)).isZero();
    }

    @Test
    @DisplayName("D1: đăng ký lại khuôn mặt cho chính mã đó (thay ảnh) -> không bị coi là trùng với chính mình")
    void registerFace_sameCode_notDuplicateOfItself() {
        List<Float> face = TestFaces.random();
        User u = userWithFace("SELF", face, true);
        expectAi(u.getUserCode(), TestFaces.sameAs(face));

        faceDataService.registerFace(u.getUserCode(), new MockMultipartFile("frontImage", "front.jpg", "image/jpeg", new byte[]{1, 2, 3}));

        aiServer.verify();
        assertThat(faceDataRepository.findByCode(u.getUserCode())).isPresent();
    }

    // ------------------------------------------------------------------ import ZIP

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    @Test
    @DisplayName("D2: import ZIP 4 dòng — dòng 2 trùng khuôn mặt dòng 1 (cùng file), dòng 4 trùng người dùng có sẵn -> 2 dòng FAILED nêu mã tài khoản, dòng 1 và 3 vẫn tạo")
    void bulkImport_duplicateFaces_failOnlyThoseRows() throws Exception {
        List<Float> existingFace = TestFaces.random();
        User existing = userWithFace("EXIST", existingFace, true);
        List<String> rows = List.of(newCode("IMP1"), newCode("IMP2"), newCode("IMP3"), newCode("IMP4"));
        List<Float> faceA = TestFaces.random();
        List<List<Float>> faces = List.of(faceA, TestFaces.sameAs(faceA), TestFaces.random(), TestFaces.sameAs(existingFace));

        StringBuilder csv = new StringBuilder("user_code,full_name,email\n");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            csv.append(rows.get(i)).append(",Face dup import ").append(i + 1).append(',')
                    .append(rows.get(i).toLowerCase(Locale.ROOT)).append("@fpt.edu.vn\n");
        }
        entries.put("metadata.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        for (String code : rows) {
            entries.put("images/" + code + ".jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, 2});
        }
        for (int i = 0; i < rows.size(); i++) {
            expectAi(rows.get(i), faces.get(i));
        }

        MvcResult r = mockMvc.perform(multipart("/api/users/normal/bulk-import")
                        .file(new MockMultipartFile("file", "face-dup.zip", "application/zip", zip(entries)))
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin)))
                .andReturn();

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        aiServer.verify();
        JsonNode data = objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(data.path("successCount").asInt()).isEqualTo(2);
        assertThat(data.path("failureCount").asInt()).isEqualTo(2);
        JsonNode results = data.path("results");
        assertThat(results.get(0).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(results.get(1).path("status").asText()).isEqualTo("FAILED");
        assertThat(results.get(1).path("errorMessage").asText())
                .isEqualTo("Khuôn mặt này đã được đăng ký cho tài khoản " + rows.get(0));
        assertThat(results.get(2).path("status").asText()).isEqualTo("SUCCESS");
        assertThat(results.get(3).path("status").asText()).isEqualTo("FAILED");
        assertThat(results.get(3).path("errorMessage").asText())
                .isEqualTo("Khuôn mặt này đã được đăng ký cho tài khoản " + existing.getUserCode());
        assertThat(userCount(rows.get(1))).isZero();
        assertThat(userCount(rows.get(3))).isZero();
        assertThat(faceDataRepository.findByCode(rows.get(0))).isPresent();
        assertThat(faceDataRepository.findByCode(rows.get(2))).isPresent();
    }

    // ------------------------------------------------------------------ face-gate 3: kích hoạt lại / khôi phục

    @Test
    @DisplayName("face-gate 3: kích hoạt lại tài khoản mà khuôn mặt trùng tài khoản đang hoạt động khác -> 409 ERR_FACE_001, vẫn bị khoá; không trùng -> kích hoạt được")
    void reactivate_faceOfActiveUser_blocked() throws Exception {
        List<Float> face = TestFaces.random();
        // dữ liệu cũ (trước face-gate): một tài khoản khoá và một tài khoản đang hoạt động cùng khuôn mặt
        User locked = userWithFace("RLOCK", face, false);
        User active = userWithFace("RACT", TestFaces.sameAs(face), true);

        MvcResult r = mockMvc.perform(patch("/api/users/" + locked.getId() + "/toggle-active")
                .header("Authorization", bearer())).andReturn();

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(409);
        assertThat(errorCode(r)).isEqualTo(FaceDuplicateException.CODE);
        assertThat(message(r)).isEqualTo("Không thể kích hoạt lại tài khoản " + locked.getUserCode()
                + ". Khuôn mặt này đã được đăng ký cho tài khoản " + active.getUserCode());
        assertThat(isActive(locked.getId())).isFalse();

        User other = userWithFace("RFREE", TestFaces.random(), false);
        MvcResult ok = mockMvc.perform(patch("/api/users/" + other.getId() + "/toggle-active")
                .header("Authorization", bearer())).andReturn();
        assertThat(ok.getResponse().getStatus()).as(ok.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(isActive(other.getId())).isTrue();
    }

    @Test
    @DisplayName("face-gate 3: khôi phục lô 4 tài khoản — 1 trùng tài khoản đang hoạt động, 1 trùng tài khoản vừa khôi phục trong cùng lô -> 2 khôi phục, 2 bỏ qua kèm lý do")
    void restoreBatch_duplicateFaces_skippedWithReason() throws Exception {
        UUID batchId = UUID.randomUUID();
        List<Float> faceR1 = TestFaces.random();
        List<Float> activeFace = TestFaces.random();
        User existing = userWithFace("ZACT", activeFace, true);
        // userCode tăng dần theo thứ tự RS1..RS4 (restoreBatch duyệt theo user_code)
        User r1 = deletedBatchUser("RS1", batchId, faceR1);
        User r2 = deletedBatchUser("RS2", batchId, TestFaces.sameAs(activeFace));
        User r3 = deletedBatchUser("RS3", batchId, TestFaces.random());
        User r4 = deletedBatchUser("RS4", batchId, TestFaces.sameAs(faceR1));

        MvcResult r = mockMvc.perform(post("/api/users/import-batches/" + batchId + "/restore")
                .header("Authorization", bearer())).andReturn();

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        JsonNode data = objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(data.path("restoredCount").asInt()).isEqualTo(2);
        assertThat(data.path("skippedCount").asInt()).isEqualTo(2);
        Map<String, String> reasons = new HashMap<>();
        data.path("skippedUsers").forEach(n -> reasons.put(n.path("userCode").asText(), n.path("reason").asText()));
        assertThat(reasons).containsOnlyKeys(r2.getUserCode(), r4.getUserCode());
        assertThat(reasons.get(r2.getUserCode()))
                .isEqualTo("Khuôn mặt này đã được đăng ký cho tài khoản " + existing.getUserCode() + ".");
        assertThat(reasons.get(r4.getUserCode()))
                .isEqualTo("Khuôn mặt này đã được đăng ký cho tài khoản " + r1.getUserCode() + ".");
        assertThat(isActive(r1.getId())).isTrue();
        assertThat(isActive(r3.getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, r2.getId())).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, r4.getId())).isTrue();
    }

    private User deletedBatchUser(String tag, UUID batchId, List<Float> face) {
        String code = newCode(tag);
        User u = userRepository.save(User.builder().userCode(code).fullName("Face dup " + tag)
                .email(code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn").role(Role.NORMAL_USER).accessLevel(1)
                .isActive(false).importBatchId(batchId).deletedAt(OffsetDateTime.now()).build());
        faceDataRepository.save(FaceData.builder().user(u).code(code).imageFrontUrl("face-profiles/" + code + ".jpg")
                .embeddingFront(TestFaces.text(face)).build());
        return u;
    }

    // ------------------------------------------------------------------ face-gate 4: hai yêu cầu cùng lúc

    @Test
    @DisplayName("face-gate 4: 2 luồng đăng ký cùng khuôn mặt cho 2 mã khác nhau, xuất phát cùng lúc -> đúng 1 thành công, 1 nhận FaceDuplicateException")
    void registerFace_concurrentSameFace_onlyOneWins() throws Exception {
        List<Float> face = TestFaces.random();
        User a = userWithoutFace("CC1");
        User b = userWithoutFace("CC2");
        aiServer.reset();
        aiServer = MockRestServiceServer.bindTo(aiRestTemplate).ignoreExpectOrder(true).build();
        aiServer.expect(ExpectedCount.times(2), requestTo(endsWith(AI_PATH)))
                .andRespond(withSuccess(TestFaces.registrationBody("CC", 1, face), MediaType.APPLICATION_JSON));
        // giữ transaction đầu tiên mở sau bước kiểm trùng: không có khoá thì luồng kia kiểm xong trước khi luồng này commit
        reset(minioStorageService);
        when(minioStorageService.uploadFaceImage(any(), anyString())).thenAnswer(inv -> {
            Thread.sleep(700);
            return "face-profiles/" + inv.getArgument(1, String.class) + ".jpg";
        });

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Throwable>> results = new ArrayList<>();
            for (User u : List.of(a, b)) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        faceDataService.registerFace(u.getUserCode(),
                                new MockMultipartFile("frontImage", "front.jpg", "image/jpeg", new byte[]{1, 2, 3}));
                        return null;
                    } catch (Throwable t) {
                        return t;
                    }
                }));
            }
            start.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<Throwable> f : results) {
                outcomes.add(f.get(30, TimeUnit.SECONDS));
            }

            assertThat(outcomes.stream().filter(Objects::isNull).count()).as(outcomes.toString()).isEqualTo(1);
            Throwable failure = outcomes.stream().filter(Objects::nonNull).findFirst().orElseThrow();
            assertThat(failure).isInstanceOf(FaceDuplicateException.class);
            String winner = faceDataRepository.findByCode(a.getUserCode()).isPresent() ? a.getUserCode() : b.getUserCode();
            String loser = winner.equals(a.getUserCode()) ? b.getUserCode() : a.getUserCode();
            assertThat(faceDataRepository.findByCode(loser)).isEmpty();
            assertThat(failure.getMessage()).isEqualTo("Khuôn mặt này đã được đăng ký cho tài khoản " + winner);
        } finally {
            pool.shutdownNow();
            aiServer = MockRestServiceServer.bindTo(aiRestTemplate).build();
        }
    }

    private User userWithoutFace(String tag) {
        String code = newCode(tag);
        return userRepository.save(User.builder().userCode(code).fullName("Face dup " + tag)
                .email(code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn").role(Role.NORMAL_USER).accessLevel(1)
                .isActive(true).build());
    }

    // ------------------------------------------------------------------ ai-service trả 400 "không phát hiện khuôn mặt"

    /** Nguyên văn ai-service/src/main.py khi ảnh không có khuôn mặt (HTTP 400). */
    private static final String AI_NO_FACE_BODY = "{\"detail\":\"Không phát hiện thấy khuôn mặt rõ nét trong ảnh chính diện. "
            + "Vui lòng chụp lại ảnh chính diện rõ mặt.\"}";
    /** 400 khác của cùng endpoint (ảnh hỏng): không phải "0 khuôn mặt". */
    private static final String AI_BAD_IMAGE_BODY = "{\"detail\":\"Không thể đọc file ảnh chính diện: cannot identify image file\"}";

    @Autowired private GuestPhotoService guestPhotoService;
    @Autowired private GuestFaceEmbeddingClient guestFaceEmbeddingClient;

    private void expectAiError(HttpStatus status, String body) {
        aiServer.expect(requestTo(endsWith(AI_PATH)))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private void expectAiTimeout() {
        aiServer.expect(requestTo(endsWith(AI_PATH))).andRespond(withException(new SocketTimeoutException("Read timed out")));
    }

    private GuestErrorCode guestPhotoError() {
        assertThat(ReflectionTestUtils.getField(guestFaceEmbeddingClient, "restTemplate"))
                .as("client khách phải gọi qua cùng AiServiceRestTemplate đã gắn mock server").isSameAs(aiRestTemplate);
        GuestPhotoService.ImageFile image = new GuestPhotoService.ImageFile(
                new byte[]{(byte) 0xFF, (byte) 0xD8, 1}, "image/jpeg", "guest.jpg");
        try {
            guestPhotoService.extractAndCheck(image, "guest-log", new GuestVisit(), null);
        } catch (GuestException ex) {
            lastGuestMessage = ex.getMessage();
            return ex.getErrorCode();
        }
        throw new AssertionError("extractAndCheck phải ném GuestException");
    }

    private String lastGuestMessage;

    @Test
    @DisplayName("face-gate 6: ai-service trả 400 không phát hiện khuôn mặt -> tạo tài khoản báo 422 \"Không phát hiện thấy khuôn mặt trong ảnh.\", không tạo tài khoản, ảnh không lên kho")
    void createStaff_aiNoFace400_noFaceError() throws Exception {
        String code = newCode("NOFACE");
        expectAiError(HttpStatus.BAD_REQUEST, AI_NO_FACE_BODY);

        MvcResult r = createStaff(code);

        aiServer.verify();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(422);
        assertThat(message(r)).isEqualTo("Không phát hiện thấy khuôn mặt trong ảnh.");
        assertThat(userCount(code)).isZero();
        assertThat(faceDataRepository.findByCode(code)).isEmpty();
        verify(minioStorageService, never()).uploadFaceImage(any(), anyString());
    }

    @Test
    @DisplayName("face-gate 6: ai-service 500 / 503 / timeout -> tạo tài khoản vẫn 503; 400 ảnh hỏng vẫn 422 câu cũ (không bị coi là 0 khuôn mặt)")
    void createStaff_aiDownOrOther400_unchanged() throws Exception {
        List<Runnable> failures = List.of(
                () -> expectAiError(HttpStatus.INTERNAL_SERVER_ERROR, "{\"detail\":\"Registration error: RuntimeError\"}"),
                () -> expectAiError(HttpStatus.SERVICE_UNAVAILABLE, "{\"detail\":\"ArcFace chưa được nạp\"}"),
                this::expectAiTimeout);
        for (Runnable failure : failures) {
            aiServer.reset();
            String code = newCode("AIDOWN");
            failure.run();
            MvcResult r = createStaff(code);
            aiServer.verify();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(503);
            assertThat(userCount(code)).isZero();
        }

        aiServer.reset();
        String code = newCode("BADIMG");
        expectAiError(HttpStatus.BAD_REQUEST, AI_BAD_IMAGE_BODY);
        MvcResult r = createStaff(code);
        aiServer.verify();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(422);
        assertThat(message(r)).startsWith("Không thể xử lý khuôn mặt trong ảnh: ").doesNotContain("Không phát hiện thấy khuôn mặt trong ảnh.");
        assertThat(userCount(code)).isZero();
        verify(minioStorageService, never()).uploadFaceImage(any(), anyString());
    }

    @Test
    @DisplayName("face-gate 6: ai-service trả 400 không phát hiện khuôn mặt -> ảnh khách báo ERR_GUEST_034 \"(phát hiện 0)\", không phải ERR_GUEST_035")
    void guestPhoto_aiNoFace400_err034ZeroFaces() {
        expectAiError(HttpStatus.BAD_REQUEST, AI_NO_FACE_BODY);

        GuestErrorCode code = guestPhotoError();

        aiServer.verify();
        assertThat(code).isEqualTo(GuestErrorCode.ERR_GUEST_034);
        assertThat(lastGuestMessage).isEqualTo("Ảnh phải có đúng 1 khuôn mặt (phát hiện 0)");
    }

    @Test
    @DisplayName("face-gate 6: ai-service 500 / 503 / timeout / 400 ảnh hỏng -> ảnh khách vẫn ERR_GUEST_035")
    void guestPhoto_aiDownOrOther400_err035() {
        List<Runnable> failures = List.of(
                () -> expectAiError(HttpStatus.INTERNAL_SERVER_ERROR, "{\"detail\":\"Registration error: RuntimeError\"}"),
                () -> expectAiError(HttpStatus.SERVICE_UNAVAILABLE, "{\"detail\":\"ArcFace chưa được nạp\"}"),
                this::expectAiTimeout,
                () -> expectAiError(HttpStatus.BAD_REQUEST, AI_BAD_IMAGE_BODY));
        for (Runnable failure : failures) {
            aiServer.reset();
            failure.run();
            GuestErrorCode code = guestPhotoError();
            aiServer.verify();
            assertThat(code).as(lastGuestMessage).isEqualTo(GuestErrorCode.ERR_GUEST_035);
        }
    }
}
