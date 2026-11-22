package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.TestFaces;
import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.hamcrest.Matchers.endsWith;

/**
 * BR-AL-33: Admin đặt cấp ban đầu (chỉ khi tạo tay). Chạy qua HTTP thật (MockMvc) trên DB test.
 * Người dùng thường: 1, 2 hợp lệ; 0, 3, 99, -1 -> 400 ERR_AC_014, không tạo tài khoản; không gửi -> ACCESS_LEVEL_DEFAULT_NORMAL_USER.
 * Tài khoản hệ thống: giá trị client gửi bị bỏ qua, luôn ACCESS_LEVEL_DEFAULT_<ROLE>.
 * Audit USER_ACCESS_LEVEL / CREATE (V135) FM đọc được; Admin không đổi cấp sau khi tạo (403);
 * cấp 1 không mời khách, cấp 2 mời được (GUEST_HOST_MIN_LEVEL). AI Service giả bằng MockRestServiceServer, MinIO và mail mock.
 * V136: ACCESS_LEVEL_DEFAULT_NORMAL_USER chỉ nhận 1–2 ở màn Cấu hình hệ thống; giá trị cũ ngoài khoảng còn trong DB
 * -> tạo tài khoản dùng mặc định của ConfigKey (1) và ghi WARN.
 */
@ExtendWith(OutputCaptureExtension.class)
class UserInitialAccessLevelIntegrationTest extends AbstractIntegrationTest {

    private static final String NORMAL_DEFAULT_KEY = "ACCESS_LEVEL_DEFAULT_NORMAL_USER";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private FaceDataRepository faceDataRepository;
    @Autowired private FaceDataService faceDataService;
    @Autowired private SystemConfigService systemConfigService;

    @MockBean private MinioStorageService minioStorageService;
    @MockBean private NotificationService notificationService;

    private AiServiceRestTemplate aiRestTemplate;
    private ClientHttpRequestFactory originalFactory;
    private MockRestServiceServer aiServer;
    private final List<UUID> fixtureIds = new ArrayList<>();
    private final List<String> createdCodes = new ArrayList<>();
    private User admin;
    private User fm;

    @BeforeAll
    void setUpAll() {
        aiRestTemplate = (AiServiceRestTemplate) ReflectionTestUtils.getField(faceDataService, "restTemplate");
        originalFactory = (ClientHttpRequestFactory) ReflectionTestUtils.getField(aiRestTemplate, "requestFactory");
        aiServer = MockRestServiceServer.bindTo(aiRestTemplate).build();
        admin = fixtureUser("ADM", Role.ADMIN);
        fm = fixtureUser("FM", Role.FACILITY_MANAGER);
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
        // audit_logs append-only giữ FK tới users -> không xoá được tài khoản test, chỉ khoá
        for (String code : createdCodes) {
            faceDataRepository.findByCode(code).ifPresent(faceDataRepository::delete);
            jdbc.update("UPDATE users SET is_active = false WHERE user_code = ?", code);
        }
        for (UUID id : fixtureIds) {
            jdbc.update("UPDATE users SET is_active = false WHERE id = ?", id);
        }
    }

    // ------------------------------------------------------------------ helpers

    private User fixtureUser(String tag, Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder()
                .userCode("IAL-" + tag + "-" + suffix)
                .fullName("Initial Level " + tag + " " + suffix)
                .email("ial-" + tag.toLowerCase(Locale.ROOT) + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(3)
                .isActive(true)
                .build());
        fixtureIds.add(u.getId());
        return u;
    }

    private static String newCode(String tag) {
        return "IAL-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder builder) {
        return builder.header("Authorization", "Bearer " + jwtTokenProvider.generateToken(user));
    }

    private static byte[] fakeJpeg(String code) {
        byte[] tag = code.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[tag.length + 2];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xD8;
        System.arraycopy(tag, 0, out, 2, tag.length);
        return out;
    }

    private void expectAiRegistration(String code) {
        String vector = TestFaces.csv(TestFaces.random());
        aiServer.expect(requestTo(endsWith("/api/v1/faces/process-registration")))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"success\":true,\"code\":\"" + code + "\",\"face_count\":1"
                        + ",\"embedding_front\":[" + vector + "]}", MediaType.APPLICATION_JSON));
    }

    /** POST /api/users/staff-accounts; accessLevel null -> không gửi trường. */
    private MvcResult create(String code, Role role, String accessLevel) throws Exception {
        createdCodes.add(code);
        MockMultipartHttpServletRequestBuilder req = multipart("/api/users/staff-accounts");
        req.file(new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", fakeJpeg(code)))
                .param("fullName", "Initial Level " + code)
                .param("userCode", code)
                .param("email", code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                .param("role", role.name());
        if (accessLevel != null) {
            req.param("accessLevel", accessLevel);
        }
        return mockMvc.perform(as(admin, req)).andReturn();
    }

    private UUID createOk(String code, Role role, String accessLevel) throws Exception {
        aiServer.reset();
        expectAiRegistration(code);
        MvcResult r = create(code, role, accessLevel);
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(201);
        aiServer.verify();
        return UUID.fromString(body(r).path("data").path("id").asText());
    }

    private JsonNode body(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int levelOf(UUID id) {
        return userRepository.findById(id).orElseThrow().getAccessLevel();
    }

    private Integer userCount(String code) {
        return jdbc.queryForObject("SELECT count(*) FROM users WHERE user_code = ?", Integer.class, code);
    }

    // ------------------------------------------------------------------ người dùng thường

    @ParameterizedTest(name = "NORMAL_USER accessLevel = {0} -> 201, lưu đúng cấp")
    @ValueSource(ints = {1, 2})
    @DisplayName("BR-AL-33: người dùng thường nhận cấp Admin chọn (1, 2)")
    void normalUser_validLevel_saved(int level) throws Exception {
        UUID id = createOk(newCode("N" + level), Role.NORMAL_USER, String.valueOf(level));
        assertThat(levelOf(id)).isEqualTo(level);
    }

    @Test
    @DisplayName("BR-AL-33: người dùng thường không gửi cấp -> ACCESS_LEVEL_DEFAULT_NORMAL_USER")
    void normalUser_noLevel_usesDefault() throws Exception {
        UUID id = createOk(newCode("ND"), Role.NORMAL_USER, null);
        assertThat(levelOf(id)).isEqualTo(systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_NORMAL_USER));
    }

    @ParameterizedTest(name = "NORMAL_USER accessLevel = {0} -> 400 ERR_AC_014, không tạo tài khoản, không gọi AI")
    @ValueSource(strings = {"0", "3", "99", "-1"})
    @DisplayName("BR-AL-33: người dùng thường gửi cấp ngoài {1, 2} bị từ chối")
    void normalUser_invalidLevel_rejected(String level) throws Exception {
        String code = newCode("NX");
        MvcResult r = create(code, Role.NORMAL_USER, level);
        JsonNode b = body(r);
        assertThat(r.getResponse().getStatus()).as(b.toString()).isEqualTo(400);
        assertThat(b.path("errorCode").asText()).isEqualTo("ERR_AC_014");
        assertThat(b.path("message").asText()).contains("1 hoặc 2").contains(level);
        assertThat(userCount(code)).isZero();
        aiServer.verify(); // không có lời gọi đăng ký khuôn mặt nào được kỳ vọng
    }

    @Test
    @DisplayName("BR-AL-33: accessLevel không phải số -> 400, không tạo tài khoản")
    void normalUser_nonNumericLevel_rejected() throws Exception {
        String code = newCode("NS");
        MvcResult r = create(code, Role.NORMAL_USER, "abc");
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(400);
        assertThat(userCount(code)).isZero();
    }

    // ------------------------------------------------------------------ tài khoản hệ thống

    @ParameterizedTest(name = "{0} gửi accessLevel = {1} -> bỏ qua, nhận cấu hình")
    @org.junit.jupiter.params.provider.CsvSource({"GUARD,3", "GUARD,1", "GUARD,99", "FACILITY_MANAGER,1", "FACILITY_MANAGER,-1"})
    @DisplayName("BR-AL-33: tài khoản hệ thống luôn nhận ACCESS_LEVEL_DEFAULT_<ROLE>, bỏ qua giá trị client gửi")
    void systemAccount_ignoresClientLevel(Role role, String sent) throws Exception {
        UUID id = createOk(newCode("S" + role.name().charAt(0)), role, sent);
        ConfigKey key = role == Role.GUARD ? ConfigKey.ACCESS_LEVEL_DEFAULT_GUARD : ConfigKey.ACCESS_LEVEL_DEFAULT_FACILITY_MANAGER;
        assertThat(levelOf(id)).isEqualTo(systemConfigService.getInt(key));
    }

    // ------------------------------------------------------------------ audit, danh sách, chặn đổi cấp, mời khách

    @Test
    @DisplayName("BR-AL-33: audit USER_ACCESS_LEVEL / CREATE (Admin, tài khoản, cấp ban đầu), chung correlation với CREATE / USER_ACCOUNT; FM đọc được")
    void audit_initialLevel_recordedAndVisibleToFm() throws Exception {
        String code = newCode("AU");
        UUID id = createOk(code, Role.NORMAL_USER, "2");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT subject_user_id, changed_by, actor_type, reason, correlation_id, old_value::text AS old_json, "
                        + "new_value::text AS new_json FROM audit_logs "
                        + "WHERE target_type = 'USER_ACCESS_LEVEL' AND action = 'CREATE' AND target_id = ?", id.toString());
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("subject_user_id")).isEqualTo(id);
        assertThat(row.get("changed_by")).isEqualTo(admin.getId());
        assertThat(row.get("actor_type")).isEqualTo("USER");
        assertThat(row.get("old_json")).isNull();
        assertThat(objectMapper.readTree((String) row.get("new_json")).path("accessLevel").asInt()).isEqualTo(2);
        assertThat(row.get("reason")).isEqualTo(UserService.INITIAL_ACCESS_LEVEL_AUDIT_REASON);
        Object accountCorrelation = jdbc.queryForObject("SELECT correlation_id FROM audit_logs "
                + "WHERE target_type = 'USER_ACCOUNT' AND action = 'CREATE' AND target_id = ?", Object.class, id.toString());
        assertThat(row.get("correlation_id")).isEqualTo(accountCorrelation);

        MvcResult r = mockMvc.perform(as(fm, get("/api/access-control/audit-logs")
                .param("targetType", "USER_ACCESS_LEVEL")
                .param("subjectUserId", id.toString()))).andReturn();
        JsonNode b = body(r);
        assertThat(r.getResponse().getStatus()).as(b.toString()).isEqualTo(200);
        JsonNode content = b.path("data").path("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).path("action").asText()).isEqualTo("CREATE");
        assertThat(content.get(0).path("newValue").path("accessLevel").asInt()).isEqualTo(2);
        assertThat(content.get(0).path("changedById").asText()).isEqualTo(admin.getId().toString());
    }

    @Test
    @DisplayName("BR-AL-33: danh sách tài khoản của Admin trả accessLevel (chỉ đọc)")
    void adminList_includesAccessLevel() throws Exception {
        String code = newCode("LS");
        createOk(code, Role.NORMAL_USER, "2");
        MvcResult r = mockMvc.perform(as(admin, get("/api/users")
                .param("keyword", code).param("accountType", "NORMAL"))).andReturn();
        JsonNode b = body(r);
        assertThat(r.getResponse().getStatus()).as(b.toString()).isEqualTo(200);
        JsonNode content = b.path("data").path("users").path("content");
        assertThat(content.size()).isEqualTo(1);
        assertThat(content.get(0).path("accessLevel").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("BR-AL-33: Admin gọi đổi cấp lẻ / nhiều người sau khi tạo -> 403, cấp không đổi")
    void admin_cannotChangeLevelAfterCreation() throws Exception {
        UUID id = createOk(newCode("AD"), Role.NORMAL_USER, "1");

        MvcResult single = mockMvc.perform(as(admin, patch("/api/users/" + id + "/access-level")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"accessLevel\":2,\"reason\":\"Admin thử nâng cấp sau khi tạo\"}"))).andReturn();
        assertThat(single.getResponse().getStatus()).isEqualTo(403);

        MvcResult bulk = mockMvc.perform(as(admin, patch("/api/users/bulk-access-level")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userIds\":[\"" + id + "\"],\"accessLevel\":2,\"reason\":\"Admin thử nâng cấp sau khi tạo\"}")))
                .andReturn();
        assertThat(bulk.getResponse().getStatus()).isEqualTo(403);

        assertThat(levelOf(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type = 'USER_ACCESS_LEVEL' "
                + "AND action = 'UPDATE' AND target_id = ?", Integer.class, id.toString())).isZero();
    }

    @Test
    @DisplayName("g) GUEST_HOST_MIN_LEVEL: người dùng thường tạo cấp 1 -> canHost=false (không có menu Khách của tôi); cấp 2 -> canHost=true")
    void guestHostEligibility_followsInitialLevel() throws Exception {
        int hostMin = systemConfigService.getInt(ConfigKey.GUEST_HOST_MIN_LEVEL);
        assertThat(hostMin).as("GUEST_HOST_MIN_LEVEL mặc định").isEqualTo(2);

        UUID level1 = createOk(newCode("G1"), Role.NORMAL_USER, "1");
        UUID level2 = createOk(newCode("G2"), Role.NORMAL_USER, "2");

        for (Object[] c : new Object[][]{{level1, false, 1}, {level2, true, 2}}) {
            User u = userRepository.findById((UUID) c[0]).orElseThrow();
            MvcResult r = mockMvc.perform(as(u, get("/api/guest-visits/eligibility"))).andReturn();
            JsonNode d = body(r).path("data");
            assertThat(r.getResponse().getStatus()).isEqualTo(200);
            assertThat(d.path("canHost").asBoolean()).as("canHost cấp " + c[2]).isEqualTo(c[1]);
            assertThat(d.path("myLevel").asInt()).isEqualTo(c[2]);
            assertThat(d.path("minLevel").asInt()).isEqualTo(hostMin);
        }
    }

    // ------------------------------------------------------------------ V136: khoảng 1–2 của cấp mặc định người dùng thường

    private String configValue(String key) {
        return jdbc.queryForObject("SELECT config_value FROM system_configurations WHERE config_key = ?", String.class, key);
    }

    private MvcResult patchConfig(String key, String value) throws Exception {
        return mockMvc.perform(as(admin, patch("/api/system-configurations/" + key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("configValue", value,
                        "reason", "Kiểm thử khoảng cấp mặc định người dùng thường"))))).andReturn();
    }

    @Test
    @DisplayName("V136: metadata ACCESS_LEVEL_DEFAULT_NORMAL_USER là min 1, max 2")
    void normalDefault_boundsAreOneToTwo() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT min_value, max_value FROM system_configurations WHERE config_key = ?", NORMAL_DEFAULT_KEY);
        assertThat(((Number) row.get("min_value")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("max_value")).intValue()).isEqualTo(2);
    }

    @ParameterizedTest(name = "PATCH {0} = {1} -> 400, giá trị không đổi")
    @org.junit.jupiter.params.provider.CsvSource({"3,không được lớn hơn 2", "0,không được nhỏ hơn 1", "-1,không được nhỏ hơn 1"})
    @DisplayName("V136: màn Cấu hình hệ thống từ chối ACCESS_LEVEL_DEFAULT_NORMAL_USER ngoài 1–2")
    void patchNormalDefault_outOfRange_rejected(String value, String expectedMessage) throws Exception {
        String before = configValue(NORMAL_DEFAULT_KEY);
        Integer logsBefore = jdbc.queryForObject(
                "SELECT count(*) FROM system_configuration_change_logs WHERE config_key = ?", Integer.class, NORMAL_DEFAULT_KEY);

        MvcResult r = patchConfig(NORMAL_DEFAULT_KEY, value);
        JsonNode b = body(r);
        assertThat(r.getResponse().getStatus()).as(b.toString()).isEqualTo(400);
        assertThat(b.path("message").asText()).contains(expectedMessage);

        assertThat(configValue(NORMAL_DEFAULT_KEY)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM system_configuration_change_logs WHERE config_key = ?",
                Integer.class, NORMAL_DEFAULT_KEY)).isEqualTo(logsBefore);
    }

    @Test
    @DisplayName("V136: PATCH ACCESS_LEVEL_DEFAULT_NORMAL_USER = 2 hợp lệ, tạo tài khoản không gửi cấp nhận 2; trả lại giá trị cũ")
    void patchNormalDefault_inRange_accepted() throws Exception {
        String before = configValue(NORMAL_DEFAULT_KEY);
        String target = "2".equals(before) ? "1" : "2";
        try {
            MvcResult r = patchConfig(NORMAL_DEFAULT_KEY, target);
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);
            UUID id = createOk(newCode("PD"), Role.NORMAL_USER, null);
            assertThat(levelOf(id)).isEqualTo(Integer.parseInt(target));
        } finally {
            patchConfig(NORMAL_DEFAULT_KEY, before);
        }
        assertThat(configValue(NORMAL_DEFAULT_KEY)).isEqualTo(before);
    }

    @Test
    @DisplayName("V136: DB còn ACCESS_LEVEL_DEFAULT_NORMAL_USER = 3 (dữ liệu cũ) -> tạo tài khoản không gửi cấp nhận 1, có log WARN")
    void legacyOutOfRangeDefault_fallsBackToOneWithWarning(CapturedOutput output) throws Exception {
        String before = configValue(NORMAL_DEFAULT_KEY);
        try {
            // Giả lập dữ liệu cũ sửa thẳng trong DB test (PATCH đã chặn), rồi nạp lại cache như POST /reload
            jdbc.update("UPDATE system_configurations SET config_value = '3' WHERE config_key = ?", NORMAL_DEFAULT_KEY);
            systemConfigService.reloadCache();

            UUID id = createOk(newCode("LG"), Role.NORMAL_USER, null);
            assertThat(levelOf(id)).isEqualTo(Integer.parseInt(ConfigKey.ACCESS_LEVEL_DEFAULT_NORMAL_USER.getDefaultValue()))
                    .isEqualTo(1);
            assertThat(output.getOut()).contains("Config key [" + NORMAL_DEFAULT_KEY + "] value [3] out of range");

            // Admin chọn cấp tường minh vẫn được tôn trọng
            UUID chosen = createOk(newCode("LC"), Role.NORMAL_USER, "2");
            assertThat(levelOf(chosen)).isEqualTo(2);
        } finally {
            jdbc.update("UPDATE system_configurations SET config_value = ? WHERE config_key = ?", before, NORMAL_DEFAULT_KEY);
            systemConfigService.reloadCache();
        }
        assertThat(systemConfigService.getInt(ConfigKey.ACCESS_LEVEL_DEFAULT_NORMAL_USER)).isEqualTo(Integer.parseInt(before));
    }
}
