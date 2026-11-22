package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.TestFaces;
import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.exception.GuestErrorCode;
import com.fa26se040.icss.exception.GuestException;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Kết quả đăng ký khuôn mặt thống nhất giữa nhánh HTTP và chế độ Cloud-Edge (ai.service.url trống, MQTT đã kết nối: ảnh đi
 * qua RPC EXTRACT_FACE_EMBEDDING, thiết bị biên trả errorCode kèm câu riêng của nó). Mỗi test chạy nhánh HTTP
 * (MockRestServiceServer) rồi chế độ edge (MQTT giả) với cùng tình huống và so kết quả.
 * - Tài khoản (FaceDataService.edgeExtractionFailure): ERR_NO_FACE_DETECTED -> 422 NO_FACE_MESSAGE; nhiều hơn 1 mặt -> 422 như
 *   HTTP; mọi mã khác, mã lạ, thiếu mã -> 503 AI_SERVICE_ERROR_MESSAGE (như ai-service 5xx); quá hạn RPC -> 503
 *   AI_SERVICE_UNAVAILABLE_MESSAGE (như lỗi kết nối HTTP). Câu của thiết bị biên không bao giờ ra response, chỉ ở log WARN.
 * - Khách (GuestFaceEmbeddingClient -> GuestPhotoService): ERR_NO_FACE_DETECTED -> ERR_GUEST_034 "(phát hiện 0)"; mã lạ / thiếu
 *   mã -> ERR_GUEST_035 như ai-service 5xx.
 * Trường edgeMqttControlService / aiServiceUrl của bean thật được đổi trong test và trả lại ở @AfterEach / @AfterAll.
 */
@ExtendWith(OutputCaptureExtension.class)
class FaceNoFaceEdgeModeTest extends AbstractIntegrationTest {

    private static final String AI_PATH = "/api/v1/faces/process-registration";
    /** ai-service HTTP 400 khi không có mặt (src/main.py): errorCode cố định + detail. */
    private static final String AI_NO_FACE_BODY = "{\"detail\":\"Không phát hiện thấy khuôn mặt rõ nét trong ảnh chính diện.\","
            + "\"errorCode\":\"" + AiFaceRegistrationErrors.EDGE_NO_FACE_ERROR_CODE + "\"}";
    /** Câu thiết bị biên tự gửi kèm ERR_NO_FACE_DETECTED (src/integration/edge_agent.py), khác câu chuẩn của Backend. */
    private static final String EDGE_NO_FACE_MESSAGE = "Không phát hiện thấy khuôn mặt rõ nét trong ảnh";
    private static final String FACES_BUCKET = "face-profiles";
    /** ai-service 5xx (src/main.py: lỗi trích xuất bất ngờ -> 500 "Registration error: ..."). */
    private static final String AI_500_BODY = "{\"detail\":\"Registration error: RuntimeError: boom\"}";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FaceDataService faceDataService;
    @Autowired private GuestFaceEmbeddingClient guestFaceEmbeddingClient;
    @Autowired private GuestPhotoService guestPhotoService;

    @MockBean private MinioStorageService minioStorageService;
    @MockBean private NotificationService notificationService;

    private final EdgeMqttControlService edge = mock(EdgeMqttControlService.class);

    private AiServiceRestTemplate aiRestTemplate;
    private ClientHttpRequestFactory originalFactory;
    private MockRestServiceServer aiServer;

    private Object originalFaceEdge;
    private Object originalFaceAiUrl;
    private Object originalGuestEdge;
    private Object originalGuestAiUrl;
    private Object originalGuestMinio;

    private User admin;
    private final List<String> codes = new ArrayList<>();

    @BeforeAll
    void setUpAll() {
        aiRestTemplate = (AiServiceRestTemplate) ReflectionTestUtils.getField(faceDataService, "restTemplate");
        assertThat(ReflectionTestUtils.getField(guestFaceEmbeddingClient, "restTemplate")).isSameAs(aiRestTemplate);
        originalFactory = (ClientHttpRequestFactory) ReflectionTestUtils.getField(aiRestTemplate, "requestFactory");
        aiServer = MockRestServiceServer.bindTo(aiRestTemplate).build();

        originalFaceEdge = ReflectionTestUtils.getField(faceDataService, "edgeMqttControlService");
        originalFaceAiUrl = ReflectionTestUtils.getField(faceDataService, "aiServiceUrl");
        originalGuestEdge = ReflectionTestUtils.getField(guestFaceEmbeddingClient, "edgeMqttControlService");
        originalGuestAiUrl = ReflectionTestUtils.getField(guestFaceEmbeddingClient, "aiServiceUrl");
        originalGuestMinio = ReflectionTestUtils.getField(guestFaceEmbeddingClient, "minioStorageService");

        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        admin = userRepository.save(User.builder()
                .userCode("NFE-ADM-" + suffix).fullName("No face edge admin " + suffix)
                .email("nfe-adm-" + suffix.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                .role(Role.ADMIN).accessLevel(3).isActive(true).build());
    }

    @BeforeEach
    void setUp() {
        aiServer.reset();
        reset(edge);
        when(edge.isConnected()).thenReturn(true);
        when(minioStorageService.getFacesBucket()).thenReturn(FACES_BUCKET);
        // edge thật sự được gắn vào cả hai bean; chế độ chọn bằng aiServiceUrl (trống = Cloud-Edge)
        ReflectionTestUtils.setField(faceDataService, "edgeMqttControlService", edge);
        ReflectionTestUtils.setField(guestFaceEmbeddingClient, "edgeMqttControlService", edge);
        ReflectionTestUtils.setField(guestFaceEmbeddingClient, "minioStorageService", minioStorageService);
    }

    @AfterEach
    void restoreBeans() {
        ReflectionTestUtils.setField(faceDataService, "edgeMqttControlService", originalFaceEdge);
        ReflectionTestUtils.setField(faceDataService, "aiServiceUrl", originalFaceAiUrl);
        ReflectionTestUtils.setField(guestFaceEmbeddingClient, "edgeMqttControlService", originalGuestEdge);
        ReflectionTestUtils.setField(guestFaceEmbeddingClient, "aiServiceUrl", originalGuestAiUrl);
        ReflectionTestUtils.setField(guestFaceEmbeddingClient, "minioStorageService", originalGuestMinio);
    }

    @AfterAll
    void cleanUp() {
        aiRestTemplate.setRequestFactory(originalFactory);
        for (String code : codes) {
            jdbc.update("UPDATE users SET is_active = false WHERE UPPER(user_code) = UPPER(?)", code);
        }
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", admin.getId());
    }

    // ------------------------------------------------------------------ helpers

    private void httpMode() {
        ReflectionTestUtils.setField(faceDataService, "aiServiceUrl", originalFaceAiUrl);
        ReflectionTestUtils.setField(guestFaceEmbeddingClient, "aiServiceUrl", originalGuestAiUrl);
        assertThat((String) originalFaceAiUrl).as("ai.service.url của profile test phải khác rỗng").isNotBlank();
    }

    private void edgeMode() {
        ReflectionTestUtils.setField(faceDataService, "aiServiceUrl", "");
        ReflectionTestUtils.setField(guestFaceEmbeddingClient, "aiServiceUrl", "");
    }

    private void expectAiNoFace400() {
        aiServer.expect(requestTo(endsWith(AI_PATH))).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON).body(AI_NO_FACE_BODY));
    }

    private void edgeReplies(String errorCode, String errorMessage) {
        edgeReplies(EdgeRpcReplyDto.builder().correlationId("corr-" + UUID.randomUUID())
                .success(false).errorCode(errorCode).errorMessage(errorMessage).build());
    }

    private void edgeReplies(EdgeRpcReplyDto reply) {
        when(edge.sendRpcRequest(eq("EXTRACT_FACE_EMBEDDING"), isNull(), anyMap(), anyLong())).thenReturn(reply);
    }

    private void expectAi500() {
        aiServer.expect(requestTo(endsWith(AI_PATH))).andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).contentType(MediaType.APPLICATION_JSON).body(AI_500_BODY));
    }

    private void expectAiTimeout() {
        aiServer.expect(requestTo(endsWith(AI_PATH)))
                .andRespond(withException(new SocketTimeoutException("Read timed out")));
    }

    /**
     * Các trường hợp edge trả lỗi KHÔNG do ảnh (mã edge_agent.py đang dùng + mã lạ + thiếu mã + thiếu dữ liệu). Mỗi câu
     * errorMessage có dấu riêng để kiểm: không lọt ra response, có trong log WARN. ERR_EDGE_INTERNAL dùng đúng câu edge gửi
     * khi ảnh hỏng (decode_image_safely ném ValueError): edge không có mã riêng cho ảnh hỏng nên vẫn là lỗi dịch vụ.
     */
    static Stream<Arguments> edgeServiceErrors() {
        return Stream.of(
                Arguments.of("ERR_MODEL_NOT_READY", failureReply("ERR_MODEL_NOT_READY",
                        "Mô hình nhận diện khuôn mặt (ArcFace) chưa được nạp trên thiết bị biên")),
                Arguments.of("ERR_FACE_STAGING_NOT_FOUND", failureReply("ERR_FACE_STAGING_NOT_FOUND",
                        "Không tải được ảnh staging từ MinIO (face-profiles/staging/x.jpg)")),
                Arguments.of("ERR_EDGE_INTERNAL (ảnh hỏng)", failureReply("ERR_EDGE_INTERNAL",
                        "Không thể giải mã định dạng ảnh hoặc file bị hỏng: cannot identify image file")),
                Arguments.of("ERR_RPC_UNKNOWN_ACTION", failureReply("ERR_RPC_UNKNOWN_ACTION",
                        "Không hỗ trợ lệnh RPC: EXTRACT_FACE_EMBEDDING")),
                Arguments.of("mã lạ", failureReply("ERR_SOMETHING_NEW", "edge bản mới báo lỗi chưa biết")),
                Arguments.of("thiếu mã", failureReply(null, "edge không gửi errorCode")),
                Arguments.of("success nhưng thiếu data", EdgeRpcReplyDto.builder()
                        .correlationId("corr-" + UUID.randomUUID()).success(true).data(null).build()));
    }

    private static EdgeRpcReplyDto failureReply(String errorCode, String errorMessage) {
        String marker = "edge-msg-" + UUID.randomUUID();
        return EdgeRpcReplyDto.builder().correlationId("corr-" + UUID.randomUUID()).success(false)
                .errorCode(errorCode).errorMessage(errorMessage + " [" + marker + "]").build();
    }

    /** Nạp theo lô (luồng người dùng thường) một dòng; trả kết quả của dòng đó. */
    private JsonNode bulkImportOneRow(String code) throws Exception {
        String csv = "user_code,full_name,email\n" + code + ",No face edge import," + code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn\n";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("metadata.csv", csv.getBytes(StandardCharsets.UTF_8));
        entries.put("images/" + code + ".jpg", new byte[]{(byte) 0xFF, (byte) 0xD8, 3});
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        MvcResult r = mockMvc.perform(multipart("/api/users/normal/bulk-import")
                        .file(new MockMultipartFile("file", "edge-errors.zip", "application/zip", bos.toByteArray()))
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin)))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
        JsonNode results = objectMapper.readTree(body(r)).path("data").path("results");
        assertThat(results.size()).isEqualTo(1);
        return results.get(0);
    }

    /** Dòng log chứa marker phải ở mức WARN. */
    private static String warnLineWith(CapturedOutput output, String marker) {
        List<String> lines = output.getOut().lines().filter(l -> l.contains(marker)).toList();
        assertThat(lines).as("dòng log chứa " + marker).hasSize(1);
        assertThat(lines.get(0)).contains("WARN");
        return lines.get(0);
    }

    /**
     * Lỗi ai-service do ẢNH ở nhánh HTTP (400 / 422 không phải "không có mặt"); %s là dấu riêng của từng lần chạy để kiểm
     * body không lọt ra response và có trong log WARN. Body theo src/main.py (ảnh rỗng, ảnh không đọc được), errorCode khác
     * ERR_NO_FACE_DETECTED, và 422 kiểu FastAPI.
     */
    static Stream<Arguments> httpImageErrors() {
        return Stream.of(
                Arguments.of("400 ảnh không đọc được", HttpStatus.BAD_REQUEST,
                        "{\"detail\":\"Không thể đọc file ảnh chính diện: cannot identify image file %s\"}"),
                Arguments.of("400 ảnh rỗng", HttpStatus.BAD_REQUEST, "{\"detail\":\"Ảnh chính diện bị rỗng. %s\"}"),
                Arguments.of("400 errorCode khác", HttpStatus.BAD_REQUEST,
                        "{\"detail\":\"Ảnh hỏng %s\",\"errorCode\":\"ERR_IMAGE_DECODE\"}"),
                Arguments.of("422 FastAPI", HttpStatus.UNPROCESSABLE_ENTITY,
                        "{\"detail\":[{\"type\":\"missing\",\"loc\":[\"body\",\"code\"],\"msg\":\"Field required %s\",\"input\":null}]}"));
    }

    /** Dòng log chứa correlationId của reply phải ở mức WARN, có errorCode và errorMessage của edge. */
    private static void assertEdgeFailureLoggedAsWarn(CapturedOutput output, EdgeRpcReplyDto reply) {
        List<String> lines = output.getOut().lines().filter(l -> l.contains(reply.getCorrelationId())).toList();
        assertThat(lines).as("log của correlationId " + reply.getCorrelationId()).hasSize(1);
        assertThat(lines.get(0)).contains("WARN").contains("errorCode=" + reply.getErrorCode())
                .contains("errorMessage=" + reply.getErrorMessage());
    }

    private String newCode(String tag) {
        String code = "NFE-" + tag + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase(Locale.ROOT);
        codes.add(code);
        return code;
    }

    private MvcResult createAccount(String code) throws Exception {
        return mockMvc.perform(multipart("/api/users/staff-accounts")
                        .file(new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, 1}))
                        .param("fullName", "No face edge " + code)
                        .param("userCode", code)
                        .param("email", code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                        .param("role", Role.NORMAL_USER.name())
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(admin)))
                .andReturn();
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String message(MvcResult r) throws Exception {
        return objectMapper.readTree(body(r)).path("message").asText("");
    }

    private int userCount(String code) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM users WHERE UPPER(user_code) = UPPER(?)", Integer.class, code);
        return n == null ? 0 : n;
    }

    private GuestException guestPhotoFailure() {
        GuestPhotoService.ImageFile image = new GuestPhotoService.ImageFile(
                new byte[]{(byte) 0xFF, (byte) 0xD8, 1}, "image/jpeg", "guest.jpg");
        try {
            guestPhotoService.extractAndCheck(image, "guest-log", new GuestVisit(), null);
        } catch (GuestException ex) {
            return ex;
        }
        throw new AssertionError("extractAndCheck phải ném GuestException");
    }

    // ------------------------------------------------------------------ tài khoản

    @Test
    @DisplayName("Tài khoản, Cloud-Edge: edge trả ERR_NO_FACE_DETECTED -> 422 đúng câu của nhánh HTTP (không phải câu của thiết bị biên), không tạo tài khoản, ảnh không lên kho, staging được gỡ")
    void account_edgeNoFace_sameAsHttp() throws Exception {
        // Nhánh HTTP: ai-service 400 errorCode ERR_NO_FACE_DETECTED
        httpMode();
        String httpCode = newCode("HTTP");
        expectAiNoFace400();
        MvcResult http = createAccount(httpCode);
        aiServer.verify();
        // nhánh HTTP chỉ hỏi isConnected() để chọn đường, không gửi RPC
        verify(edge, never()).sendRpcRequest(anyString(), any(), anyMap(), anyLong());

        // Chế độ Cloud-Edge: cùng ảnh, thiết bị biên báo không thấy mặt bằng câu của nó
        edgeMode();
        aiServer.reset(); // không kỳ vọng lời gọi HTTP nào: gọi tới ai-service sẽ làm test hỏng
        edgeReplies(AiFaceRegistrationErrors.EDGE_NO_FACE_ERROR_CODE, EDGE_NO_FACE_MESSAGE);
        String edgeCode = newCode("EDGE");
        MvcResult edgeResult = createAccount(edgeCode);
        aiServer.verify();

        assertThat(http.getResponse().getStatus()).as(body(http)).isEqualTo(422);
        assertThat(edgeResult.getResponse().getStatus()).as(body(edgeResult)).isEqualTo(422);
        assertThat(message(http)).isEqualTo(FaceDataService.NO_FACE_MESSAGE);
        assertThat(message(edgeResult)).isEqualTo(message(http)).isNotEqualTo(EDGE_NO_FACE_MESSAGE);

        verify(edge, times(1)).sendRpcRequest(eq("EXTRACT_FACE_EMBEDDING"), isNull(), anyMap(), anyLong());
        verify(minioStorageService, times(1)).uploadStagingObject(eq(FACES_BUCKET), startsWith("staging/"), any(), anyString());
        verify(minioStorageService, times(1)).removeObjectSafely(eq(FACES_BUCKET), startsWith("staging/"));
        verify(minioStorageService, never()).uploadFaceImage(any(), anyString());
        assertThat(userCount(httpCode)).isZero();
        assertThat(userCount(edgeCode)).isZero();
    }

    @ParameterizedTest(name = "Tài khoản, Cloud-Edge: {0} -> 503 giống ai-service 5xx")
    @MethodSource("edgeServiceErrors")
    @DisplayName("Tài khoản, Cloud-Edge: lỗi edge không do ảnh (mã đang dùng, mã lạ, thiếu mã, thiếu data) -> 503 đúng câu nhánh HTTP trả cho 5xx; câu của edge chỉ ở log WARN; không tạo tài khoản, staging được gỡ")
    void account_edgeServiceError_sameAsHttp5xx(String label, EdgeRpcReplyDto reply, CapturedOutput output) throws Exception {
        httpMode();
        String httpCode = newCode("H5XX");
        expectAi500();
        MvcResult http = createAccount(httpCode);
        aiServer.verify();

        edgeMode();
        aiServer.reset();
        edgeReplies(reply);
        String edgeCode = newCode("ESVC");
        MvcResult edgeResult = createAccount(edgeCode);
        aiServer.verify();

        assertThat(http.getResponse().getStatus()).as(body(http)).isEqualTo(503);
        assertThat(edgeResult.getResponse().getStatus()).as(label + ": " + body(edgeResult)).isEqualTo(503);
        assertThat(message(http)).isEqualTo(FaceDataService.AI_SERVICE_ERROR_MESSAGE);
        assertThat(message(edgeResult)).as(label).isEqualTo(message(http));
        if (reply.getErrorMessage() != null) {
            assertThat(body(edgeResult)).as("câu của edge không được lọt ra response").doesNotContain(reply.getErrorMessage());
        }
        assertEdgeFailureLoggedAsWarn(output, reply);

        verify(edge, times(1)).sendRpcRequest(eq("EXTRACT_FACE_EMBEDDING"), isNull(), anyMap(), anyLong());
        verify(minioStorageService, times(1)).removeObjectSafely(eq(FACES_BUCKET), startsWith("staging/"));
        verify(minioStorageService, never()).uploadFaceImage(any(), anyString());
        assertThat(userCount(httpCode)).isZero();
        assertThat(userCount(edgeCode)).isZero();
    }

    @Test
    @DisplayName("Tài khoản, Cloud-Edge: ảnh có 2 khuôn mặt (edge trả success, face_count = 2) -> 422 giống hệt nhánh HTTP")
    void account_edgeMultipleFaces_sameAsHttp() throws Exception {
        List<Float> face = TestFaces.random();
        httpMode();
        String httpCode = newCode("H2F");
        aiServer.expect(requestTo(endsWith(AI_PATH)))
                .andRespond(withSuccess(TestFaces.registrationBody(httpCode, 2, face), MediaType.APPLICATION_JSON));
        MvcResult http = createAccount(httpCode);
        aiServer.verify();

        edgeMode();
        aiServer.reset();
        edgeReplies(EdgeRpcReplyDto.builder().correlationId("corr-" + UUID.randomUUID()).success(true)
                .data(Map.of("face_count", 2, "embedding_front", face)).build());
        String edgeCode = newCode("E2F");
        MvcResult edgeResult = createAccount(edgeCode);
        aiServer.verify();

        assertThat(http.getResponse().getStatus()).as(body(http)).isEqualTo(422);
        assertThat(edgeResult.getResponse().getStatus()).as(body(edgeResult)).isEqualTo(422);
        assertThat(message(edgeResult)).isEqualTo(message(http)).isEqualTo("Ảnh chứa nhiều hơn 1 khuôn mặt");
        verify(minioStorageService, never()).uploadFaceImage(any(), anyString());
        assertThat(userCount(httpCode)).isZero();
        assertThat(userCount(edgeCode)).isZero();
    }

    @Test
    @DisplayName("Tài khoản, Cloud-Edge: RPC quá hạn (CameraException ERR_EDGE_002) -> 503 đúng câu nhánh HTTP trả khi ai-service timeout; staging được gỡ")
    void account_edgeRpcTimeout_sameAsHttpConnectionError() throws Exception {
        httpMode();
        String httpCode = newCode("HTO");
        expectAiTimeout();
        MvcResult http = createAccount(httpCode);
        aiServer.verify();

        edgeMode();
        aiServer.reset();
        when(edge.sendRpcRequest(eq("EXTRACT_FACE_EMBEDDING"), isNull(), anyMap(), anyLong()))
                .thenThrow(new CameraException(CameraErrorCode.ERR_EDGE_002));
        String edgeCode = newCode("ETO");
        MvcResult edgeResult = createAccount(edgeCode);
        aiServer.verify();

        assertThat(http.getResponse().getStatus()).as(body(http)).isEqualTo(503);
        assertThat(edgeResult.getResponse().getStatus()).as(body(edgeResult)).isEqualTo(503);
        assertThat(message(http)).isEqualTo(FaceDataService.AI_SERVICE_UNAVAILABLE_MESSAGE);
        assertThat(message(edgeResult)).isEqualTo(message(http));
        verify(minioStorageService, times(1)).removeObjectSafely(eq(FACES_BUCKET), startsWith("staging/"));
        assertThat(userCount(httpCode)).isZero();
        assertThat(userCount(edgeCode)).isZero();
    }

    // ------------------------------------------------------------------ nhánh HTTP: lỗi do ảnh không kèm body ai-service

    @ParameterizedTest(name = "Tài khoản, HTTP: {0} -> 422 câu chuẩn, body chỉ ở log WARN")
    @MethodSource("httpImageErrors")
    @DisplayName("Tài khoản, nhánh HTTP: ai-service từ chối ảnh (400 / 422 không phải \"không có mặt\") -> 422 đúng câu chuẩn, không kèm body / detail của ai-service; chi tiết chỉ ở log WARN")
    void account_httpImageError_standardMessageWithoutAiBody(String label, HttpStatus status, String bodyTemplate,
                                                             CapturedOutput output) throws Exception {
        String marker = "ai-detail-" + UUID.randomUUID();
        httpMode();
        aiServer.expect(requestTo(endsWith(AI_PATH)))
                .andRespond(withStatus(status).contentType(MediaType.APPLICATION_JSON).body(String.format(bodyTemplate, marker)));
        String code = newCode("HIMG");

        MvcResult r = createAccount(code);

        aiServer.verify();
        assertThat(r.getResponse().getStatus()).as(label + ": " + body(r)).isEqualTo(422);
        assertThat(message(r)).isEqualTo(FaceDataService.IMAGE_UNPROCESSABLE_MESSAGE).isNotEqualTo(FaceDataService.NO_FACE_MESSAGE);
        assertThat(body(r)).as("body / detail của ai-service không được lọt ra response").doesNotContain(marker)
                .doesNotContain("detail");
        assertThat(warnLineWith(output, marker)).contains(code);
        verify(minioStorageService, never()).uploadFaceImage(any(), anyString());
        assertThat(userCount(code)).isZero();
    }

    @Test
    @DisplayName("Nạp theo lô, nhánh HTTP: ai-service trả 400 ảnh không đọc được -> dòng FAILED với đúng câu chuẩn, không kèm body ai-service; chi tiết chỉ ở log WARN")
    void bulkImport_httpImageError_rowShowsStandardMessage(CapturedOutput output) throws Exception {
        String marker = "ai-detail-" + UUID.randomUUID();
        httpMode();
        aiServer.expect(requestTo(endsWith(AI_PATH))).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"detail\":\"Không thể đọc file ảnh chính diện: cannot identify image file " + marker + "\"}"));
        String code = newCode("BIMG");

        JsonNode row = bulkImportOneRow(code);

        aiServer.verify();
        assertThat(row.path("status").asText()).isEqualTo("FAILED");
        assertThat(row.path("errorMessage").asText()).isEqualTo(FaceDataService.IMAGE_UNPROCESSABLE_MESSAGE).doesNotContain(marker);
        warnLineWith(output, marker);
        assertThat(userCount(code)).isZero();
    }

    // ------------------------------------------------------------------ khách

    @Test
    @DisplayName("Khách, Cloud-Edge: edge trả ERR_NO_FACE_DETECTED -> ERR_GUEST_034 \"(phát hiện 0)\", giống hệt nhánh HTTP; staging được gỡ, không gọi HTTP")
    void guest_edgeNoFace_sameAsHttp() {
        httpMode();
        expectAiNoFace400();
        GuestException http = guestPhotoFailure();
        aiServer.verify();
        // nhánh HTTP chỉ hỏi isConnected() để chọn đường, không gửi RPC
        verify(edge, never()).sendRpcRequest(anyString(), any(), anyMap(), anyLong());

        edgeMode();
        aiServer.reset();
        clearInvocations(minioStorageService);
        edgeReplies(AiFaceRegistrationErrors.EDGE_NO_FACE_ERROR_CODE, EDGE_NO_FACE_MESSAGE);
        GuestException edgeResult = guestPhotoFailure();
        aiServer.verify();

        GuestException expected = new GuestException(GuestErrorCode.ERR_GUEST_034, 0);
        assertThat(http.getErrorCode()).isEqualTo(GuestErrorCode.ERR_GUEST_034);
        assertThat(edgeResult.getErrorCode()).isEqualTo(GuestErrorCode.ERR_GUEST_034);
        assertThat(http.getMessage()).isEqualTo(expected.getMessage()).endsWith("(phát hiện 0)");
        assertThat(edgeResult.getMessage()).isEqualTo(http.getMessage());

        verify(edge, times(1)).sendRpcRequest(eq("EXTRACT_FACE_EMBEDDING"), isNull(), anyMap(), anyLong());
        verify(minioStorageService, times(1)).uploadStagingObject(eq(FACES_BUCKET), startsWith("staging/guest_"), any(), anyString());
        verify(minioStorageService, times(1)).removeObjectSafely(eq(FACES_BUCKET), startsWith("staging/guest_"));
    }

    static Stream<Arguments> guestUnknownOrMissingCode() {
        return Stream.of(
                Arguments.of("mã lạ", "ERR_SOMETHING_NEW"),
                Arguments.of("thiếu mã", null));
    }

    @ParameterizedTest(name = "Khách, Cloud-Edge: {0} -> ERR_GUEST_035 giống ai-service 5xx")
    @MethodSource("guestUnknownOrMissingCode")
    @DisplayName("Khách, Cloud-Edge: mã lạ / thiếu mã -> ERR_GUEST_035 như nhánh HTTP 5xx (luồng khách không đổi), câu của edge không lọt ra")
    void guest_edgeUnknownOrMissingCode_err035SameAsHttp5xx(String label, String errorCode) {
        httpMode();
        expectAi500();
        GuestException http = guestPhotoFailure();
        aiServer.verify();

        edgeMode();
        aiServer.reset();
        String edgeMessage = "edge báo lỗi " + label + " " + UUID.randomUUID();
        edgeReplies(errorCode, edgeMessage);
        GuestException edgeResult = guestPhotoFailure();
        aiServer.verify();

        assertThat(http.getErrorCode()).isEqualTo(GuestErrorCode.ERR_GUEST_035);
        assertThat(edgeResult.getErrorCode()).as(label).isEqualTo(GuestErrorCode.ERR_GUEST_035);
        assertThat(edgeResult.getMessage()).isEqualTo(http.getMessage()).doesNotContain(edgeMessage);
        verify(minioStorageService, times(1)).removeObjectSafely(eq(FACES_BUCKET), startsWith("staging/guest_"));
    }
}
