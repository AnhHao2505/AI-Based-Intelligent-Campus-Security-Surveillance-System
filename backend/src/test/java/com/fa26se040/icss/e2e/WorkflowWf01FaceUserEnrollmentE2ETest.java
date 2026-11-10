package com.fa26se040.icss.e2e;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.FaceDataRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.InternalServiceKeyFilter;
import com.fa26se040.icss.service.AiFaceCacheRefreshNotifier;
import com.fa26se040.icss.service.FaceDataService;
import com.fa26se040.icss.service.MinioStorageService;
import com.fa26se040.icss.service.NotificationService;
import com.fa26se040.icss.service.UserAccessLevelHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * TC-WF1-E2E (DS-01a / DS-01b) — WF-01 Face &amp; User Enrollment, end-to-end mức backend.
 *
 * <p>Một luồng liên tục, các bước chạy theo {@link Order}; bước trước hỏng thì bước sau bị bỏ qua (assumption).
 * Đi qua HTTP thật (MockMvc, filter JWT / X-Service-Key, {@code @PreAuthorize}), DB test thật, transaction thật.</p>
 *
 * <p>Giả lập: AI Service bằng {@link MockRestServiceServer} gắn vào {@link AiServiceRestTemplate} (RestTemplate của
 * {@link FaceDataService}, cùng mẫu StaffAccountFaceLinkIntegrationTest); MinIO và mail là {@code @MockBean};
 * lời gọi làm mới cache khuôn mặt sang AI ({@link AiFaceCacheRefreshNotifier}) được thay bằng bộ ghi URL.
 * Phản hồi AI giả theo đúng định dạng {@code ai-service/src/main.py} (POST /api/v1/faces/process-registration):
 * ảnh hợp lệ -&gt; 200 {success, code, face_count: 1, embedding_front[512]}; ảnh 2 khuôn mặt -&gt; AI thật KHÔNG báo lỗi,
 * vẫn trả 200 với face_count: 2, backend tự từ chối (FaceDataService "Ảnh chứa nhiều hơn 1 khuôn mặt").</p>
 *
 * <p>Dữ liệu dùng mã duy nhất theo lần chạy (hậu tố UUID); khu vực, camera, nhật ký ra vào, face_data và user được
 * xoá trong {@link #cleanUp()}.</p>
 */
@DisplayName("TC-WF1-E2E – Face & User Enrollment (backend end-to-end, AI service mocked)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowWf01FaceUserEnrollmentE2ETest extends AbstractIntegrationTest {

    private static final String AI_REGISTRATION_PATH = "/api/v1/faces/process-registration";
    private static final String AI_REFRESH_PATH = "/api/v1/faces/refresh-cache";
    private static final String TWO_FACES_MESSAGE = "Ảnh chứa nhiều hơn 1 khuôn mặt";
    private static final String DEACTIVATED_REASON = "Tài khoản đã bị vô hiệu hoá";
    private static final String ADMIN_PASSWORD = "E2E-Admin-Pass-01";

    /**
     * Thời điểm gửi trong trường timestamp của check-entry: 10:00 / 11:00 giờ VN, ngoài khung "ngoài giờ" 22:00–06:00
     * (CameraAccessLogService.isAfterHours) để phân loại nhật ký không phụ thuộc giờ chạy test; cách nhau 1 giờ để
     * không bị debounce ENTRY_LOG_DEBOUNCE_SECONDS.
     */
    private static final String ENTRY_TIME_ACTIVE = "2026-11-10T10:00:00+07:00";
    private static final String ENTRY_TIME_DEACTIVATED = "2026-11-10T11:00:00+07:00";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserRepository userRepository;
    @Autowired private FaceDataRepository faceDataRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private CameraRepository cameraRepository;
    @Autowired private FaceDataService faceDataService;
    @Autowired private AiFaceCacheRefreshNotifier aiFaceCacheRefreshNotifier;
    @Autowired private UserAccessLevelHelper userAccessLevelHelper;
    @Value("${icss.internal.service-key}") private String serviceKey;

    @MockBean private MinioStorageService minioStorageService;
    @MockBean private NotificationService notificationService;

    private final List<String> refreshCalls = new CopyOnWriteArrayList<>();
    private RestTemplateState aiRestTemplateState;
    private Object originalRefreshCaller;
    private MockRestServiceServer aiServer;

    private String suffix;
    private User admin;
    private String adminToken;
    private Area area;
    private Camera camera;

    // Tài khoản tạo ở bước 1
    private String enrolledCode;
    private String enrolledEmail;
    private UUID enrolledId;
    private String enrolledTempPassword;
    private String enrolledToken;

    // Các dòng import ở bước 2 (dòng 3 là ảnh 2 khuôn mặt)
    private final List<String> importCodes = new ArrayList<>();
    private String twoFacesCode;

    private int completedStep;

    private record RestTemplateState(AiServiceRestTemplate template, ClientHttpRequestFactory requestFactory) {}

    @BeforeAll
    void setUpFlow() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

        AiServiceRestTemplate restTemplate = (AiServiceRestTemplate) ReflectionTestUtils.getField(faceDataService, "restTemplate");
        aiRestTemplateState = new RestTemplateState(restTemplate,
                (ClientHttpRequestFactory) ReflectionTestUtils.getField(restTemplate, "requestFactory"));
        aiServer = MockRestServiceServer.bindTo(restTemplate).build();

        originalRefreshCaller = ReflectionTestUtils.getField(aiFaceCacheRefreshNotifier, "refreshHttpCaller");
        Consumer<String> recorder = refreshCalls::add;
        ReflectionTestUtils.setField(aiFaceCacheRefreshNotifier, "refreshHttpCaller", recorder);

        admin = userRepository.save(User.builder()
                .userCode("E2E1-ADM-" + suffix)
                .fullName("E2E WF1 Admin " + suffix)
                .email("e2e1-adm-" + suffix.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                .password(passwordEncoder.encode(ADMIN_PASSWORD))
                .role(Role.ADMIN)
                .accessLevel(3)
                .isActive(true)
                .build());

        Building building = buildingRepository.findByNameIgnoreCase("Tòa E2E WF")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa E2E WF").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa E2E WF", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        area = areaRepository.save(Area.builder()
                .name("E2E WF1 phòng bảo mật " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .build());
        camera = cameraRepository.save(Camera.builder()
                .cameraCode("E2E1-CAM-" + suffix)
                .name("E2E WF1 camera " + suffix)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build());
    }

    /** {@code @MockBean} được reset sau mỗi phương thức test nên stub lại trước mỗi bước. */
    @BeforeEach
    void stubMinio() {
        when(minioStorageService.uploadFaceImage(any(), anyString()))
                .thenAnswer(inv -> "face-profiles/" + inv.getArgument(1, String.class) + ".jpg");
    }

    @AfterAll
    void cleanUp() {
        aiRestTemplateState.template().setRequestFactory(aiRestTemplateState.requestFactory());
        ReflectionTestUtils.setField(aiFaceCacheRefreshNotifier, "refreshHttpCaller", originalRefreshCaller);

        if (camera != null) {
            jdbc.update("DELETE FROM camera_access_logs WHERE camera_code = ?", camera.getCameraCode());
            cameraRepository.deleteById(camera.getId());
        }
        if (area != null) {
            areaRepository.deleteById(area.getId());
        }
        List<String> codes = new ArrayList<>(importCodes);
        if (enrolledCode != null) {
            codes.add(enrolledCode);
        }
        // PR-AUDIT: các thao tác của luồng ghi audit (append-only, FK tới users) -> không xoá được user, chỉ khoá
        for (String code : codes) {
            faceDataRepository.findByCode(code).ifPresent(faceDataRepository::delete);
            jdbc.update("UPDATE users SET is_active = false WHERE user_code = ?", code);
        }
        if (admin != null) {
            jdbc.update("UPDATE users SET is_active = false WHERE id = ?", admin.getId());
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String vector512() {
        return IntStream.range(0, 512).mapToObj(i -> String.valueOf((i % 7) / 100.0)).collect(Collectors.joining(","));
    }

    /** Phản hồi AI thật khi đăng ký thành công (ai-service/src/main.py, process_face_registration). */
    private static String aiRegistrationBody(String code, int faceCount) {
        return "{\"success\":true,\"code\":\"" + code + "\",\"full_name\":null,\"face_count\":" + faceCount
                + ",\"embedding_front\":[" + vector512() + "]}";
    }

    private void expectAiRegistration(String code, int faceCount) {
        aiServer.expect(requestTo(endsWith(AI_REGISTRATION_PATH)))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(AiServiceRestTemplate.SERVICE_KEY_HEADER, serviceKey))
                .andExpect(content().string(containsString(code)))
                .andRespond(withSuccess(aiRegistrationBody(code, faceCount), MediaType.APPLICATION_JSON));
    }

    /** Ảnh giả (AI đã được giả lập nên backend không giải mã ảnh); nhỏ hơn giới hạn 350 KB. */
    private static byte[] fakeJpeg(String code) {
        byte[] header = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0};
        byte[] tag = code.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[header.length + tag.length];
        System.arraycopy(header, 0, out, 0, header.length);
        System.arraycopy(tag, 0, out, header.length, tag.length);
        return out;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", email, "password", password))))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        String token = body(r).path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private String adminBearer() throws Exception {
        if (adminToken == null) {
            adminToken = login(admin.getEmail(), ADMIN_PASSWORD);
        }
        return "Bearer " + adminToken;
    }

    private MvcResult checkEntry(String userCode, String timestamp) throws Exception {
        return mockMvc.perform(post("/api/access-control/check-entry")
                        .header(InternalServiceKeyFilter.HEADER, serviceKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cameraCode", camera.getCameraCode(),
                                "userCode", userCode,
                                "timestamp", timestamp))))
                .andReturn();
    }

    private Map<String, Object> userRow(String code) {
        return jdbc.queryForMap("SELECT id, role, is_active, access_level, password, import_batch_id, deleted_at "
                + "FROM users WHERE user_code = ?", code);
    }

    private int userCount(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE user_code = ?", Integer.class, code);
    }

    private Map<String, Object> faceRow(String code) {
        return jdbc.queryForMap("SELECT user_id, image_front_url, vector_dims(embedding_front) AS dims "
                + "FROM face_data WHERE code = ?", code);
    }

    private int faceCount(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM face_data WHERE code = ?", Integer.class, code);
    }

    /** PR-AUDIT: các dòng audit (target_type/action) về một tài khoản: subject_user_id, target_id = id hoặc = mã. */
    private List<Map<String, Object>> auditRowsAbout(UUID userId, String userCode) {
        return jdbc.queryForList("SELECT target_type, action, changed_by, correlation_id, ip_address FROM audit_logs "
                + "WHERE subject_user_id = ? OR target_id = ? OR target_id = ? ORDER BY changed_at",
                userId, userId.toString(), userCode);
    }

    private static List<String> actionsOf(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> r.get("target_type") + "/" + r.get("action")).toList();
    }

    private void requireStep(int previous) {
        assumeTrue(completedStep == previous, "Bước " + previous + " chưa qua, bỏ qua bước sau trong cùng luồng");
    }

    // ------------------------------------------------------------------ bước 1

    @Test
    @Order(1)
    @DisplayName("Bước 1: ADMIN tạo tài khoản NORMAL_USER kèm ảnh 1 khuôn mặt qua HTTP -> 201, ACTIVE, face_data 512 chiều trỏ đúng user")
    void step1_adminCreatesAccountWithFace() throws Exception {
        requireStep(0);
        enrolledCode = "E2E1-SV-" + suffix;
        enrolledEmail = "e2e1-sv-" + suffix.toLowerCase(Locale.ROOT) + "@fpt.edu.vn";
        expectAiRegistration(enrolledCode, 1);

        MvcResult r = mockMvc.perform(multipart("/api/users/staff-accounts")
                        .file(new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", fakeJpeg(enrolledCode)))
                        .param("fullName", "E2E Enrollment " + suffix)
                        .param("userCode", enrolledCode)
                        .param("email", enrolledEmail)
                        .param("role", Role.NORMAL_USER.name())
                        .header("Authorization", adminBearer()))
                .andReturn();

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(201);
        aiServer.verify();
        JsonNode data = body(r).path("data");
        assertThat(data.path("userCode").asText()).isEqualTo(enrolledCode);
        assertThat(data.path("role").asText()).isEqualTo("NORMAL_USER");
        assertThat(data.path("isActive").asBoolean(false)).isTrue();
        assertThat(data.path("faceData").path("id").asText()).isNotBlank();
        enrolledId = UUID.fromString(data.path("id").asText());

        // DB: tài khoản ACTIVE, cấp mặc định của NORMAL_USER (BR-AU-07), mật khẩu đã băm
        Map<String, Object> user = userRow(enrolledCode);
        assertThat(user.get("id")).isEqualTo(enrolledId);
        assertThat(user.get("role")).isEqualTo("NORMAL_USER");
        assertThat(user.get("is_active")).isEqualTo(true);
        assertThat(user.get("access_level")).isEqualTo(userAccessLevelHelper.resolveDefaultAccessLevel(Role.NORMAL_USER));
        assertThat((String) user.get("password")).isNotBlank().doesNotContain(" ");

        // DB: face_data trỏ đúng user, embedding 512 chiều, ảnh đã lên MinIO (giả)
        Map<String, Object> face = faceRow(enrolledCode);
        assertThat(face.get("user_id")).isEqualTo(enrolledId);
        assertThat(face.get("dims")).isEqualTo(512);
        assertThat(face.get("image_front_url")).isEqualTo("face-profiles/" + enrolledCode + ".jpg");
        verify(minioStorageService).uploadFaceImage(any(), eq(enrolledCode));

        // Mail mật khẩu tạm gửi sau commit (BR-AU-06): lấy mật khẩu để bước 3 đăng nhập như người dùng thật
        ArgumentCaptor<String> password = ArgumentCaptor.forClass(String.class);
        verify(notificationService).sendStaffAccountSetupEmail(eq(enrolledEmail), anyString(), eq(enrolledCode), password.capture());
        enrolledTempPassword = password.getValue();
        assertThat(enrolledTempPassword).hasSize(12);
        assertThat(passwordEncoder.matches(enrolledTempPassword, (String) user.get("password"))).isTrue();

        // PR-AUDIT (V112): tạo tài khoản lẻ ghi đúng CREATE / USER_ACCOUNT + REGISTER_FACE / FACE_REGISTRATION,
        // người thực hiện là ADMIN, chung correlation (cùng request)
        List<Map<String, Object>> audits = auditRowsAbout(enrolledId, enrolledCode);
        assertThat(actionsOf(audits)).containsExactlyInAnyOrder("FACE_REGISTRATION/REGISTER_FACE", "USER_ACCOUNT/CREATE");
        assertThat(audits).allSatisfy(a -> assertThat(a.get("changed_by")).isEqualTo(admin.getId()));
        assertThat(audits.get(0).get("correlation_id")).isEqualTo(audits.get(1).get("correlation_id"));
        // audit-login-privacy (phương án b): chỉ module AUTH lưu IP, tạo tài khoản để NULL
        assertThat(audits).allSatisfy(a -> assertThat(a.get("ip_address")).isNull());
        completedStep = 1;
    }

    // ------------------------------------------------------------------ bước 2

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
    @Order(2)
    @DisplayName("Bước 2: ADMIN import ZIP (metadata.csv + images/) 4 dòng -> 3 dòng tạo kèm face_data, dòng ảnh 2 khuôn mặt FAILED kèm lý do")
    void step2_adminBulkImportsZip() throws Exception {
        requireStep(1);
        for (int i = 1; i <= 4; i++) {
            importCodes.add("E2E1-IMP" + i + "-" + suffix);
        }
        twoFacesCode = importCodes.get(2);

        StringBuilder csv = new StringBuilder("user_code,full_name,email\n");
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        for (int i = 0; i < importCodes.size(); i++) {
            String code = importCodes.get(i);
            csv.append(code).append(",E2E Import ").append(i + 1).append(' ').append(suffix).append(',')
                    .append(code.toLowerCase(Locale.ROOT)).append("@fpt.edu.vn\n");
        }
        entries.put("metadata.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        for (String code : importCodes) {
            entries.put("images/" + code + ".jpg", fakeJpeg(code));
        }
        // Thứ tự xử lý = thứ tự dòng; dòng 3 (twoFacesCode) AI trả face_count = 2
        aiServer.reset();
        for (String code : importCodes) {
            expectAiRegistration(code, code.equals(twoFacesCode) ? 2 : 1);
        }

        MvcResult r = mockMvc.perform(multipart("/api/users/normal/bulk-import")
                        .file(new MockMultipartFile("file", "wf1-import.zip", "application/zip", zip(entries)))
                        .header("Authorization", adminBearer()))
                .andReturn();

        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        aiServer.verify();
        JsonNode data = body(r).path("data");
        assertThat(data.path("totalRows").asInt()).isEqualTo(4);
        assertThat(data.path("successCount").asInt()).isEqualTo(3);
        assertThat(data.path("failureCount").asInt()).isEqualTo(1);
        UUID batchId = UUID.fromString(data.path("importBatchId").asText());

        JsonNode results = data.path("results");
        assertThat(results).hasSize(4);
        for (int i = 0; i < importCodes.size(); i++) {
            JsonNode row = results.get(i);
            String code = importCodes.get(i);
            assertThat(row.path("rowIndex").asInt()).isEqualTo(i + 2); // dòng 1 là tiêu đề
            assertThat(row.path("userCode").asText()).isEqualTo(code);
            if (code.equals(twoFacesCode)) {
                assertThat(row.path("status").asText()).isEqualTo("FAILED");
                assertThat(row.path("errorMessage").asText()).isEqualTo(TWO_FACES_MESSAGE);
            } else {
                assertThat(row.path("status").asText()).isEqualTo("SUCCESS");
                assertThat(row.path("errorMessage").isNull() || row.path("errorMessage").isMissingNode()).isTrue();
            }
        }

        int defaultLevel = userAccessLevelHelper.resolveDefaultAccessLevel(Role.NORMAL_USER);
        for (String code : importCodes) {
            String email = code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn";
            if (code.equals(twoFacesCode)) {
                // Dòng lỗi: transaction riêng của dòng rollback -> không có user, không có face_data, ảnh không lên MinIO
                assertThat(userCount(code)).isZero();
                assertThat(faceCount(code)).isZero();
                verify(minioStorageService, never()).uploadFaceImage(any(), eq(code));
                verify(minioStorageService).deleteFaceImage(code);
                verify(notificationService, never()).sendStaffAccountSetupEmail(eq(email), anyString(), anyString(), anyString());
                continue;
            }
            Map<String, Object> user = userRow(code);
            assertThat(user.get("role")).isEqualTo("NORMAL_USER");
            assertThat(user.get("is_active")).isEqualTo(true);
            assertThat(user.get("access_level")).isEqualTo(defaultLevel);
            assertThat(user.get("import_batch_id")).isEqualTo(batchId);
            Map<String, Object> face = faceRow(code);
            assertThat(face.get("user_id")).isEqualTo(user.get("id"));
            assertThat(face.get("dims")).isEqualTo(512);
            verify(notificationService, times(1)).sendStaffAccountSetupEmail(eq(email), anyString(), eq(code), anyString());
            // PR-AUDIT (V112): mỗi tài khoản tạo ra có CREATE + REGISTER_FACE, correlation = batchId
            List<Map<String, Object>> audits = auditRowsAbout((UUID) user.get("id"), code);
            assertThat(actionsOf(audits)).containsExactlyInAnyOrder("FACE_REGISTRATION/REGISTER_FACE", "USER_ACCOUNT/CREATE");
            assertThat(audits).allSatisfy(a -> assertThat(a.get("correlation_id")).isEqualTo(batchId));
        }
        // 1 dòng IMPORT / USER_IMPORT_BATCH cho cả lô (dòng ảnh 2 khuôn mặt không có audit tài khoản)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type = 'USER_IMPORT_BATCH' "
                + "AND action = 'IMPORT' AND target_id = ? AND correlation_id = ?", Integer.class, batchId.toString(), batchId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_id = ?", Integer.class, twoFacesCode))
                .isZero();
        completedStep = 2;
    }

    // ------------------------------------------------------------------ bước 3

    @Test
    @Order(3)
    @DisplayName("Bước 3: người dùng vừa tạo đăng nhập bằng mật khẩu tạm -> JWT; gọi API cần đăng nhập -> 200 đúng danh tính")
    void step3_enrolledUserLogsInAndCallsApi() throws Exception {
        requireStep(2);
        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", enrolledEmail, "password", enrolledTempPassword))))
                .andReturn();
        assertThat(loginResult.getResponse().getStatus()).isEqualTo(200);
        JsonNode auth = body(loginResult).path("data");
        enrolledToken = auth.path("accessToken").asText();
        assertThat(enrolledToken).isNotBlank();
        assertThat(auth.path("user").path("userCode").asText()).isEqualTo(enrolledCode);
        assertThat(auth.path("user").path("role").asText()).isEqualTo("NORMAL_USER");

        MvcResult me = mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + enrolledToken)).andReturn();
        assertThat(me.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(me).path("data").path("id").asText()).isEqualTo(enrolledId.toString());
        assertThat(body(me).path("data").path("userCode").asText()).isEqualTo(enrolledCode);

        // API chỉ NORMAL_USER gọi được (@PreAuthorize hasRole('NORMAL_USER')): tài khoản mới chưa có đơn nào
        MvcResult my = mockMvc.perform(get("/api/access-requests/my").header("Authorization", "Bearer " + enrolledToken)).andReturn();
        assertThat(my.getResponse().getStatus()).as(my.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(body(my).path("data").path("content").isArray()).isTrue();
        assertThat(body(my).path("data").path("totalElements").asLong(-1)).isZero();
        completedStep = 3;
    }

    // ------------------------------------------------------------------ bước 4

    @Test
    @Order(4)
    @DisplayName("Bước 4: ADMIN vô hiệu hoá tài khoản -> JWT cũ bị 401; check-entry tại camera khu Bảo mật -> allowed=false (tài khoản vô hiệu hoá)")
    void step4_deactivationRevokesTokenAndEntry() throws Exception {
        requireStep(3);
        // Mốc trước khi khoá: tài khoản cấp 1 bị từ chối ở khu cấp 2 vì CẤP, không phải vì khoá
        MvcResult before = checkEntry(enrolledCode, ENTRY_TIME_ACTIVE);
        assertThat(before.getResponse().getStatus()).isEqualTo(200);
        JsonNode beforeData = body(before).path("data");
        assertThat(beforeData.path("allowed").asBoolean(true)).isFalse();
        assertThat(beforeData.path("reason").asText()).startsWith("Cấp độ truy cập không đủ");

        MvcResult toggle = mockMvc.perform(patch("/api/users/{id}/toggle-active", enrolledId)
                        .header("Authorization", adminBearer()))
                .andReturn();
        assertThat(toggle.getResponse().getStatus()).as(toggle.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(body(toggle).path("data").path("isActive").asBoolean(true)).isFalse();
        assertThat(userRow(enrolledCode).get("is_active")).isEqualTo(false);

        // AI được báo làm mới cache khuôn mặt sau commit (chạy nền -> chờ tối đa 5 giây)
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (refreshCalls.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(refreshCalls).anyMatch(url -> url.endsWith(AI_REFRESH_PATH));

        // JWT cũ (chưa hết hạn) bị từ chối: JwtAuthenticationFilter tra user mỗi request (BR-AU-34)
        MvcResult oldToken = mockMvc.perform(get("/api/access-requests/my").header("Authorization", "Bearer " + enrolledToken)).andReturn();
        assertThat(oldToken.getResponse().getStatus()).isEqualTo(401);
        // Đăng nhập lại bằng đúng mật khẩu cũng bị từ chối (chỉ kiểm mã HTTP, câu thông báo xem báo cáo mục 5)
        MvcResult relogin = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", enrolledEmail, "password", enrolledTempPassword))))
                .andReturn();
        assertThat(relogin.getResponse().getStatus()).isEqualTo(401);

        // AI hỏi quyền vào cho người đã bị khoá -> allowed=false, lý do là khoá tài khoản
        MvcResult after = checkEntry(enrolledCode, ENTRY_TIME_DEACTIVATED);
        assertThat(after.getResponse().getStatus()).isEqualTo(200);
        JsonNode afterData = body(after).path("data");
        assertThat(afterData.path("allowed").asBoolean(true)).isFalse();
        assertThat(afterData.path("source").asText()).isEqualTo("NONE");
        assertThat(afterData.path("reason").asText()).isEqualTo(DEACTIVATED_REASON);
        assertThat(afterData.path("userCode").asText()).isEqualTo(enrolledCode);
        assertThat(afterData.path("areaId").asText()).isEqualTo(area.getId().toString());

        Map<String, Object> log = jdbc.queryForMap(
                "SELECT user_id, user_code, access_result, access_source, reason_details, logged_at FROM camera_access_logs "
                        + "WHERE camera_code = ? AND logged_at = CAST(? AS timestamptz)",
                camera.getCameraCode(), ENTRY_TIME_DEACTIVATED);
        assertThat(log.get("user_id")).isEqualTo(enrolledId);
        assertThat(log.get("user_code")).isEqualTo(enrolledCode);
        assertThat(log.get("access_result")).isEqualTo("UNAUTHORIZED_PERSON");
        assertThat(log.get("access_source")).isEqualTo("NONE");
        assertThat(log.get("reason_details")).isEqualTo(DEACTIVATED_REASON);
        completedStep = 4;
    }
}
