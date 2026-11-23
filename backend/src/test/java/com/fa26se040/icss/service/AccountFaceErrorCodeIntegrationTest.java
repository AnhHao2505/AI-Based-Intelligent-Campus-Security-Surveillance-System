package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.TestFaces;
import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.dto.BulkImportRowResult;
import com.fa26se040.icss.entity.FaceData;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.FaceErrorCode;
import com.fa26se040.icss.repository.FaceDataRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * B1 (system test 23/11, TC-F14-S06 / S07): ảnh khuôn mặt tài khoản bị từ chối trả mã riêng ERR_FACE_xxx ở cả errorCode
 * lẫn code, HTTP status giữ như trước (400 / 422 / 503, trùng mặt 409), câu báo giữ nguyên. Nạp theo lô: mã nằm ở
 * errorCode của kết quả từng dòng. Dịch vụ AI giả bằng MockRestServiceServer; MinIO, mail là mock (cùng bộ MockBean với
 * FaceDuplicateRegistrationTest để dùng lại Spring context).
 */
@ExtendWith(OutputCaptureExtension.class)
class AccountFaceErrorCodeIntegrationTest extends AbstractIntegrationTest {

    private static final String AI_PATH = "/api/v1/faces/process-registration";
    private static final String AI_NO_FACE_BODY = "{\"detail\":\"No face detected\",\"errorCode\":\"ERR_NO_FACE_DETECTED\"}";
    private static final String AI_BAD_IMAGE_BODY = "{\"detail\":\"Không thể đọc file ảnh chính diện: cannot identify image file\"}";
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, 1};

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private FaceDataService faceDataService;
    @Autowired private UserBulkImportHelper userBulkImportHelper;
    @Autowired private FaceDataRepository faceDataRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;

    @MockBean private MinioStorageService minioStorageService;
    @MockBean private NotificationService notificationService;

    private AiServiceRestTemplate aiRestTemplate;
    private ClientHttpRequestFactory originalFactory;
    private MockRestServiceServer aiServer;
    private User admin;
    private final List<String> codes = new ArrayList<>();

    @BeforeAll
    void bindAiServer() {
        aiRestTemplate = (AiServiceRestTemplate) ReflectionTestUtils.getField(faceDataService, "restTemplate");
        originalFactory = (ClientHttpRequestFactory) ReflectionTestUtils.getField(aiRestTemplate, "requestFactory");
        aiServer = MockRestServiceServer.bindTo(aiRestTemplate).build();
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        admin = userRepository.save(User.builder()
                .userCode("FERR-ADM-" + suffix).fullName("Face error admin " + suffix)
                .email("ferr-adm-" + suffix.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
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
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", admin.getId());
    }

    // ------------------------------------------------------------------ helpers

    private String newCode(String tag) {
        String code = "FERR-" + tag + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        codes.add(code);
        return code;
    }

    private MvcResult createStaff(String code, MockMultipartFile image) throws Exception {
        return mockMvc.perform(multipart("/api/users/staff-accounts")
                        .file(image)
                        .param("fullName", "Face error " + code)
                        .param("userCode", code)
                        .param("email", code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                        .param("role", Role.NORMAL_USER.name())
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin)))
                .andReturn();
    }

    private MvcResult createStaff(String code) throws Exception {
        return createStaff(code, new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", JPEG));
    }

    private void expectAiError(HttpStatus status, String body) {
        aiServer.expect(requestTo(endsWith(AI_PATH)))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private void expectAiFaces(String code, int faceCount) {
        aiServer.expect(requestTo(endsWith(AI_PATH)))
                .andRespond(withSuccess(TestFaces.registrationBody(code, faceCount), MediaType.APPLICATION_JSON));
    }

    /** HTTP status, errorCode, code, status (body) và câu báo; tài khoản không được tạo. */
    private void assertFaceError(MvcResult r, String code, FaceErrorCode expected, String expectedMessage) throws Exception {
        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode body = objectMapper.readTree(raw);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(expected.getHttpStatus().value());
        assertThat(body.path("errorCode").asText()).as(raw).isEqualTo(expected.getCode());
        assertThat(body.path("code").asText()).as(raw).isEqualTo(expected.getCode());
        assertThat(body.path("status").asInt()).as(raw).isEqualTo(expected.getHttpStatus().value());
        if (expectedMessage != null) {
            assertThat(body.path("message").asText()).isEqualTo(expectedMessage);
        }
        assertThat(userRepository.findByUserCode(code)).isEmpty();
    }

    private BulkImportRowResult importRow(String code, byte[] image, String fileName) {
        return userBulkImportHelper.processSingleRow(1, code, "Face error " + code, code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn",
                Role.NORMAL_USER, UUID.randomUUID(), image, fileName, "Temp@123456");
    }

    private void assertRowError(BulkImportRowResult row, String code, FaceErrorCode expected) {
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getErrorCode()).as(row.getErrorMessage()).isEqualTo(expected.getCode());
        assertThat(userRepository.findByUserCode(code)).isEmpty();
    }

    // ------------------------------------------------------------------ tạo tài khoản (POST /api/users/staff-accounts)

    @Test
    @DisplayName("ERR_FACE_002: file ảnh rỗng -> 400, câu cũ \"Ảnh khuôn mặt không được để trống.\"")
    void create_emptyImage_err002() throws Exception {
        String code = newCode("EMPTY");
        MvcResult r = createStaff(code, new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", new byte[0]));
        assertFaceError(r, code, FaceErrorCode.ERR_FACE_002, "Ảnh khuôn mặt không được để trống.");
    }

    @Test
    @DisplayName("ERR_FACE_003: ảnh vượt USER_FACE_IMAGE_MAX_KB -> 400, câu nêu giới hạn đang cấu hình")
    void create_oversizedImage_err003() throws Exception {
        String code = newCode("BIG");
        int maxKb = faceDataService.getFaceImageMaxKb();
        byte[] big = new byte[maxKb * 1024 + 1];
        MvcResult r = createStaff(code, new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", big));
        assertFaceError(r, code, FaceErrorCode.ERR_FACE_003, FaceDataService.faceImageTooLargeMessage(maxKb, big.length));
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains(maxKb + "KB");
    }

    @Test
    @DisplayName("ERR_FACE_004: không phải JPG / PNG -> 400, câu cũ")
    void create_wrongFormat_err004() throws Exception {
        String code = newCode("GIF");
        MvcResult r = createStaff(code, new MockMultipartFile("faceImage", "front.gif", "image/gif", JPEG));
        assertFaceError(r, code, FaceErrorCode.ERR_FACE_004,
                "Định dạng file không hợp lệ. Chỉ chấp nhận file ảnh định dạng JPG hoặc PNG.");
    }

    @Test
    @DisplayName("ERR_FACE_005: không có khuôn mặt (ai-service 400 ERR_NO_FACE_DETECTED, hoặc face_count = 0) -> 422")
    void create_noFace_err005() throws Exception {
        String code = newCode("NOFACE");
        expectAiError(HttpStatus.BAD_REQUEST, AI_NO_FACE_BODY);
        MvcResult r = createStaff(code);
        aiServer.verify();
        assertFaceError(r, code, FaceErrorCode.ERR_FACE_005, FaceDataService.NO_FACE_MESSAGE);

        aiServer.reset();
        String code0 = newCode("ZERO");
        expectAiFaces(code0, 0);
        MvcResult r0 = createStaff(code0);
        aiServer.verify();
        assertFaceError(r0, code0, FaceErrorCode.ERR_FACE_005, FaceDataService.NO_FACE_MESSAGE);
    }

    @Test
    @DisplayName("ERR_FACE_006: nhiều hơn 1 khuôn mặt -> 422 \"Ảnh chứa nhiều hơn 1 khuôn mặt\"")
    void create_multipleFaces_err006() throws Exception {
        String code = newCode("MULTI");
        expectAiFaces(code, 2);
        MvcResult r = createStaff(code);
        aiServer.verify();
        assertFaceError(r, code, FaceErrorCode.ERR_FACE_006, "Ảnh chứa nhiều hơn 1 khuôn mặt");
    }

    @Test
    @DisplayName("ERR_FACE_007: ai-service không đọc được ảnh (400 khác không có mặt) -> 422 câu chuẩn, không lộ detail")
    void create_unprocessableImage_err007() throws Exception {
        String code = newCode("BADIMG");
        expectAiError(HttpStatus.BAD_REQUEST, AI_BAD_IMAGE_BODY);
        MvcResult r = createStaff(code);
        aiServer.verify();
        assertFaceError(r, code, FaceErrorCode.ERR_FACE_007, FaceDataService.IMAGE_UNPROCESSABLE_MESSAGE);
    }

    @Test
    @DisplayName("ERR_FACE_008: ai-service 5xx / quá hạn -> 503 (câu cũ của từng nhánh)")
    void create_aiUnavailable_err008() throws Exception {
        String code = newCode("AI500");
        expectAiError(HttpStatus.INTERNAL_SERVER_ERROR, "{\"detail\":\"Registration error: RuntimeError\"}");
        MvcResult r = createStaff(code);
        aiServer.verify();
        assertFaceError(r, code, FaceErrorCode.ERR_FACE_008, FaceDataService.AI_SERVICE_ERROR_MESSAGE);

        aiServer.reset();
        String codeT = newCode("AITMO");
        aiServer.expect(requestTo(endsWith(AI_PATH))).andRespond(withException(new SocketTimeoutException("Read timed out")));
        MvcResult rt = createStaff(codeT);
        aiServer.verify();
        assertFaceError(rt, codeT, FaceErrorCode.ERR_FACE_008, FaceDataService.AI_SERVICE_UNAVAILABLE_MESSAGE);
    }

    @Test
    @DisplayName("ERR_FACE_001 giữ nguyên: trùng khuôn mặt -> 409, code = errorCode = ERR_FACE_001")
    void create_duplicateFace_err001() throws Exception {
        List<Float> face = TestFaces.random();
        String ownerCode = newCode("OWN");
        User owner = userRepository.save(User.builder().userCode(ownerCode).fullName("Face error owner")
                .email(ownerCode.toLowerCase(Locale.ROOT) + "@fpt.edu.vn").role(Role.NORMAL_USER).accessLevel(1)
                .isActive(true).build());
        faceDataRepository.save(FaceData.builder().user(owner).code(ownerCode).imageFrontUrl("face-profiles/" + ownerCode + ".jpg")
                .embeddingFront(TestFaces.text(face)).build());

        String code = newCode("DUP");
        aiServer.expect(requestTo(endsWith(AI_PATH)))
                .andRespond(withSuccess(TestFaces.registrationBody(code, 1, TestFaces.sameAs(face)), MediaType.APPLICATION_JSON));
        MvcResult r = createStaff(code);
        aiServer.verify();
        assertFaceError(r, code, FaceErrorCode.ERR_FACE_001, null);
    }

    // ------------------------------------------------------------------ nạp theo lô (kết quả từng dòng)

    @Test
    @DisplayName("Nạp theo lô: dòng thiếu ảnh / quá dung lượng / sai định dạng mang ERR_FACE_002 / 003 / 004, câu cũ")
    void bulkRow_precheck_codes() {
        String c2 = newCode("BEMPTY");
        BulkImportRowResult r2 = importRow(c2, null, c2 + ".jpg");
        assertRowError(r2, c2, FaceErrorCode.ERR_FACE_002);
        assertThat(r2.getErrorMessage()).startsWith("Không tìm thấy file ảnh tương ứng trong thư mục images/");

        String c3 = newCode("BBIG");
        int maxKb = faceDataService.getFaceImageMaxKb();
        byte[] big = new byte[maxKb * 1024 + 1];
        BulkImportRowResult r3 = importRow(c3, big, c3 + ".jpg");
        assertRowError(r3, c3, FaceErrorCode.ERR_FACE_003);
        assertThat(r3.getErrorMessage()).isEqualTo(FaceDataService.faceImageTooLargeMessage(maxKb, big.length));

        String c4 = newCode("BGIF");
        BulkImportRowResult r4 = importRow(c4, JPEG, c4 + ".gif");
        assertRowError(r4, c4, FaceErrorCode.ERR_FACE_004);
        assertThat(r4.getErrorMessage()).isEqualTo("Định dạng file ảnh không hợp lệ. Chỉ chấp nhận file JPG hoặc PNG.");
    }

    @Test
    @DisplayName("Nạp theo lô: lỗi từ dịch vụ AI mang mã của registerFace (005 / 006 / 007 / 008), câu cũ; lỗi không phải ảnh không có mã")
    void bulkRow_aiSide_codes() {
        String c5 = newCode("BNOFACE");
        expectAiError(HttpStatus.BAD_REQUEST, AI_NO_FACE_BODY);
        BulkImportRowResult r5 = importRow(c5, JPEG, c5 + ".jpg");
        aiServer.verify();
        assertRowError(r5, c5, FaceErrorCode.ERR_FACE_005);
        assertThat(r5.getErrorMessage()).isEqualTo(FaceDataService.NO_FACE_MESSAGE);

        aiServer.reset();
        String c6 = newCode("BMULTI");
        expectAiFaces(c6, 3);
        BulkImportRowResult r6 = importRow(c6, JPEG, c6 + ".jpg");
        aiServer.verify();
        assertRowError(r6, c6, FaceErrorCode.ERR_FACE_006);

        aiServer.reset();
        String c7 = newCode("BBADIMG");
        expectAiError(HttpStatus.BAD_REQUEST, AI_BAD_IMAGE_BODY);
        BulkImportRowResult r7 = importRow(c7, JPEG, c7 + ".jpg");
        aiServer.verify();
        assertRowError(r7, c7, FaceErrorCode.ERR_FACE_007);

        aiServer.reset();
        String c8 = newCode("BAI503");
        expectAiError(HttpStatus.SERVICE_UNAVAILABLE, "{\"detail\":\"ArcFace chưa được nạp\"}");
        BulkImportRowResult r8 = importRow(c8, JPEG, c8 + ".jpg");
        aiServer.verify();
        assertRowError(r8, c8, FaceErrorCode.ERR_FACE_008);

        // lỗi trường dữ liệu (không phải ảnh) -> không có mã
        BulkImportRowResult rEmail = userBulkImportHelper.processSingleRow(1, newCode("BMAIL"), "Face error mail", "not-an-email",
                Role.NORMAL_USER, UUID.randomUUID(), JPEG, "x.jpg", "Temp@123456");
        assertThat(rEmail.getStatus()).isEqualTo("FAILED");
        assertThat(rEmail.getErrorCode()).isNull();
    }

    // ------------------------------------------------------------------ lỗi không lường trước trong registerFace

    /** ai-service trả 200 nhưng body không phải JSON -> RestTemplate không đọc được -> nhánh catch chung của registerFace. */
    private void expectAiGarbage() {
        aiServer.expect(requestTo(endsWith(AI_PATH)))
                .andRespond(withSuccess("not-json{", MediaType.APPLICATION_JSON));
    }

    private static final String LOOKUP_MESSAGE_REGEX = "^Lỗi hệ thống\\. Mã tra cứu: [0-9a-f]{8}$";

    @Test
    @DisplayName("Nạp theo lô: lỗi không lường trước trong registerFace -> cột lỗi chỉ có \"Lỗi hệ thống. Mã tra cứu: xxxxxxxx\", không lớp / message nội bộ / stack; chi tiết nằm ở log kèm cùng mã")
    void bulkRow_unexpectedError_onlyLookupReference(CapturedOutput output) {
        String code = newCode("BGARBAGE");
        expectAiGarbage();
        BulkImportRowResult row = importRow(code, JPEG, code + ".jpg");
        aiServer.verify();

        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getErrorCode()).isNull();
        assertThat(row.getErrorMessage())
                .matches(LOOKUP_MESSAGE_REGEX)
                .doesNotContain("Lỗi xử lý khuôn mặt")
                .doesNotContain("Exception")
                .doesNotContain("com.fa26se040")
                .doesNotContain("AiFaceRegistrationResponseDto")
                .doesNotContain("JSON")
                .doesNotContain("\tat ");
        assertThat(userRepository.findByUserCode(code)).isEmpty();

        String errorId = row.getErrorMessage().substring(row.getErrorMessage().length() - 8);
        assertThat(output.getAll()).as("chi tiết chỉ ở log server, cùng mã tra cứu")
                .contains("errorId=" + errorId)
                .contains("Lỗi xử lý khuôn mặt");
    }

    @Test
    @DisplayName("Tạo tài khoản: cùng lỗi không lường trước -> 500 \"Lỗi hệ thống. Mã tra cứu: xxxxxxxx\" (không đổi), không lộ chi tiết")
    void create_unexpectedError_lookupReference() throws Exception {
        String code = newCode("GARBAGE");
        expectAiGarbage();
        MvcResult r = createStaff(code);
        aiServer.verify();

        String raw = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as(raw).isEqualTo(500);
        assertThat(objectMapper.readTree(raw).path("message").asText()).matches(LOOKUP_MESSAGE_REGEX);
        assertThat(raw).doesNotContain("Lỗi xử lý khuôn mặt").doesNotContain("com.fa26se040");
        assertThat(userRepository.findByUserCode(code)).isEmpty();
    }
}
