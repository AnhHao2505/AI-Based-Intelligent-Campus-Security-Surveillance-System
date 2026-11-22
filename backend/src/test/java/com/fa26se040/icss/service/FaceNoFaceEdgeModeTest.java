package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import com.fa26se040.icss.entity.GuestVisit;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.GuestErrorCode;
import com.fa26se040.icss.exception.GuestException;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

/**
 * Câu báo "không có mặt" thống nhất giữa nhánh HTTP và chế độ Cloud-Edge (ai.service.url trống, MQTT đã kết nối: ảnh đi qua
 * RPC EXTRACT_FACE_EMBEDDING, thiết bị biên trả errorCode ERR_NO_FACE_DETECTED kèm câu riêng của nó).
 * - Tài khoản (FaceDataService): cả hai nhánh -> 422 FaceDataService.NO_FACE_MESSAGE, không tạo tài khoản, ảnh không lên kho.
 * - Khách (GuestFaceEmbeddingClient -> GuestPhotoService): cả hai nhánh -> ERR_GUEST_034 "(phát hiện 0)".
 * Mỗi test chạy nhánh HTTP (MockRestServiceServer trả 400 errorCode) rồi nhánh edge (MQTT giả) và so kết quả.
 * Trường edgeMqttControlService / aiServiceUrl của bean thật được đổi trong test và trả lại ở @AfterEach / @AfterAll.
 */
class FaceNoFaceEdgeModeTest extends AbstractIntegrationTest {

    private static final String AI_PATH = "/api/v1/faces/process-registration";
    /** ai-service HTTP 400 khi không có mặt (src/main.py): errorCode cố định + detail. */
    private static final String AI_NO_FACE_BODY = "{\"detail\":\"Không phát hiện thấy khuôn mặt rõ nét trong ảnh chính diện.\","
            + "\"errorCode\":\"" + AiFaceRegistrationErrors.EDGE_NO_FACE_ERROR_CODE + "\"}";
    /** Câu thiết bị biên tự gửi kèm ERR_NO_FACE_DETECTED (src/integration/edge_agent.py), khác câu chuẩn của Backend. */
    private static final String EDGE_NO_FACE_MESSAGE = "Không phát hiện thấy khuôn mặt rõ nét trong ảnh";
    private static final String FACES_BUCKET = "face-profiles";

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
        when(edge.sendRpcRequest(eq("EXTRACT_FACE_EMBEDDING"), isNull(), anyMap(), anyLong()))
                .thenReturn(EdgeRpcReplyDto.builder().correlationId("corr-" + UUID.randomUUID())
                        .success(false).errorCode(errorCode).errorMessage(errorMessage).build());
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

    @Test
    @DisplayName("Tài khoản, Cloud-Edge: lỗi edge khác (model chưa nạp) giữ hành vi cũ — 422 kèm câu của thiết bị biên, không bị đổi thành câu \"không có mặt\"")
    void account_edgeOtherError_unchanged() throws Exception {
        edgeMode();
        String modelMessage = "Mô hình nhận diện khuôn mặt (ArcFace) chưa được nạp trên thiết bị biên";
        edgeReplies("ERR_MODEL_NOT_READY", modelMessage);
        String code = newCode("MODEL");

        MvcResult r = createAccount(code);

        aiServer.verify();
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(422);
        assertThat(message(r)).isEqualTo(modelMessage).isNotEqualTo(FaceDataService.NO_FACE_MESSAGE);
        assertThat(userCount(code)).isZero();
        verify(minioStorageService, times(1)).removeObjectSafely(eq(FACES_BUCKET), startsWith("staging/"));
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
}
