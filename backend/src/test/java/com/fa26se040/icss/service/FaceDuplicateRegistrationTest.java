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
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * D1, D2 phía người dùng: đăng ký khuôn mặt khi tạo tài khoản / import ZIP bị chặn nếu trùng khuôn mặt của người dùng
 * đang hoạt động hoặc khách của lượt còn hiệu lực; dòng import trùng lỗi, dòng khác vẫn chạy (kể cả trùng nhau trong
 * cùng file); thay ảnh của chính mình không bị coi là trùng.
 * Dịch vụ trích khuôn mặt giả bằng MockRestServiceServer; MinIO và mail là mock. Vector khuôn mặt ngẫu nhiên (TestFaces).
 */
class FaceDuplicateRegistrationTest extends AbstractIntegrationTest {

    private static final String AI_PATH = "/api/v1/faces/process-registration";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FaceDataService faceDataService;
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

    private int userCount(String code) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users WHERE UPPER(user_code) = UPPER(?)", Integer.class, code);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ tạo tài khoản

    @Test
    @DisplayName("D1/D2: tạo tài khoản với khuôn mặt của người dùng đang hoạt động -> 422 \"Khuôn mặt này đã được đăng ký cho tài khoản <mã>\", không tạo tài khoản / face_data, ảnh không lên kho")
    void createStaff_faceOfActiveUser_blocked() throws Exception {
        List<Float> face = TestFaces.random();
        User owner = userWithFace("OWN", face, true);
        String code = newCode("NEW");
        expectAi(code, TestFaces.sameAs(face));

        MvcResult r = createStaff(code);

        aiServer.verify();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(422);
        assertThat(message(r)).isEqualTo("Khuôn mặt này đã được đăng ký cho tài khoản " + owner.getUserCode());
        assertThat(message(r)).doesNotContain("face-profiles").doesNotContain("http");
        assertThat(userCount(code)).isZero();
        assertThat(faceDataRepository.findByCode(code)).isEmpty();
        verify(minioStorageService, never()).uploadFaceImage(any(), anyString());
    }

    @Test
    @DisplayName("D1: khuôn mặt của người dùng đã khoá không tính -> tạo tài khoản thành công")
    void createStaff_faceOfInactiveUser_allowed() throws Exception {
        List<Float> face = TestFaces.random();
        userWithFace("OFF", face, false);
        String code = newCode("NEW2");
        expectAi(code, TestFaces.sameAs(face));

        MvcResult r = createStaff(code);

        aiServer.verify();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isIn(200, 201);
        assertThat(faceDataRepository.findByCode(code)).isPresent();
    }

    @Test
    @DisplayName("D1/D2: tạo tài khoản với khuôn mặt của khách thuộc lượt đã duyệt chưa kết thúc -> 422 nêu \"khách <tên> của lượt <mục đích> (<thời gian>)\"")
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

        assertThat(r.getResponse().getStatus()).isEqualTo(422);
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
}
