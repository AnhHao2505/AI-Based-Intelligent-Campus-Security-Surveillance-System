package com.fa26se040.icss.audit;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.config.AiServiceRestTemplate;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.FaceDataRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fa26se040.icss.service.FaceDataService;
import com.fa26se040.icss.service.MinioStorageService;
import com.fa26se040.icss.service.NotificationService;
import com.fa26se040.icss.service.SystemConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * PR-AUDIT (B3–B6): audit module ACCOUNT và SYSTEM_CONFIG ghi qua HTTP thật (MockMvc), DB test thật.
 * Mỗi thao tác: đúng số dòng audit, đúng target / action / actor / correlation_id, có IP + user agent của request,
 * snapshot không chứa dữ liệu nhạy cảm. AI Service giả bằng MockRestServiceServer (mẫu StaffAccountFaceLinkIntegrationTest),
 * MinIO và mail là mock. audit_logs append-only giữ FK tới users -> user test không xoá được, chỉ khoá ở @AfterAll.
 */
class AccountAuditIntegrationTest extends AbstractIntegrationTest {

    private static final String UA = "PR-AUDIT-Test/1.0";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private UserRepository userRepository;
    @Autowired private FaceDataRepository faceDataRepository;
    @Autowired private FaceDataService faceDataService;
    @Autowired private SystemConfigService systemConfigService;
    @Value("${icss.internal.service-key}") private String serviceKey;

    @MockBean private MinioStorageService minioStorageService;
    @MockBean private NotificationService notificationService;

    private AiServiceRestTemplate aiRestTemplate;
    private ClientHttpRequestFactory originalFactory;
    private MockRestServiceServer aiServer;
    private final List<UUID> createdUserIds = new ArrayList<>();
    private final List<String> createdCodes = new ArrayList<>();
    private User admin;

    @BeforeAll
    void setUpAll() {
        aiRestTemplate = (AiServiceRestTemplate) ReflectionTestUtils.getField(faceDataService, "restTemplate");
        originalFactory = (ClientHttpRequestFactory) ReflectionTestUtils.getField(aiRestTemplate, "requestFactory");
        aiServer = MockRestServiceServer.bindTo(aiRestTemplate).build();
        admin = fixtureUser("adm", Role.ADMIN);
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
        for (String code : createdCodes) {
            faceDataRepository.findByCode(code).ifPresent(faceDataRepository::delete);
        }
        for (UUID id : createdUserIds) {
            jdbc.update("UPDATE users SET is_active = false WHERE id = ?", id);
        }
        for (String code : createdCodes) {
            jdbc.update("UPDATE users SET is_active = false WHERE user_code = ?", code);
        }
    }

    // ------------------------------------------------------------------ helpers

    private User fixtureUser(String tag, Role role) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User u = userRepository.save(User.builder()
                .userCode("AUD-" + tag.toUpperCase(Locale.ROOT) + "-" + suffix)
                .fullName("Audit " + tag + " " + suffix)
                .email("aud-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
        createdUserIds.add(u.getId());
        return u;
    }

    private static String newCode(String tag) {
        return "AUD-" + tag + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder builder, String ip) {
        return builder.header("Authorization", "Bearer " + jwtTokenProvider.generateToken(user))
                .header("User-Agent", UA)
                .with(r -> {
                    r.setRemoteAddr(ip);
                    return r;
                });
    }

    private void expectAi(String code, int faceCount) {
        String vector = IntStream.range(0, 512).mapToObj(i -> "0.02").collect(Collectors.joining(","));
        aiServer.expect(requestTo(endsWith("/api/v1/faces/process-registration")))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString(code)))
                .andRespond(withSuccess("{\"success\":true,\"code\":\"" + code + "\",\"face_count\":" + faceCount
                        + ",\"embedding_front\":[" + vector + "]}", MediaType.APPLICATION_JSON));
    }

    private static byte[] fakeJpeg(String code) {
        byte[] tag = code.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[tag.length + 2];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xD8;
        System.arraycopy(tag, 0, out, 2, tag.length);
        return out;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private List<Map<String, Object>> audits(String targetType, String action, String targetId) {
        return jdbc.queryForList("SELECT target_id, subject_user_id, changed_by, actor_type, actor_source, correlation_id, "
                + "ip_address, user_agent, reason, old_value::text AS old_json, new_value::text AS new_json "
                + "FROM audit_logs WHERE target_type = ? AND action = ? AND target_id = ?", targetType, action, targetId);
    }

    private Map<String, Object> single(String targetType, String action, String targetId) {
        List<Map<String, Object>> rows = audits(targetType, action, targetId);
        assertThat(rows).as(targetType + "/" + action + " " + targetId).hasSize(1);
        return rows.get(0);
    }

    /** B4: snapshot không có mật khẩu, token, embedding, URL. */
    private static void assertNoSensitive(Map<String, Object> row) {
        for (String col : new String[]{"old_json", "new_json"}) {
            String json = (String) row.get(col);
            if (json == null) {
                continue;
            }
            assertThat(json).as(col).doesNotContain("password").doesNotContain("token")
                    .doesNotContain("embedding").doesNotContain("http").doesNotContain("face-profiles");
        }
    }

    private static void assertHttpOrigin(Map<String, Object> row, String ip) {
        assertThat(row.get("ip_address")).isEqualTo(ip);
        assertThat(row.get("user_agent")).isEqualTo(UA);
    }

    private JsonNode json(Map<String, Object> row, String col) throws Exception {
        return objectMapper.readTree((String) row.get(col));
    }

    private MvcResult createAccount(String code, String ip) throws Exception {
        createdCodes.add(code);
        aiServer.reset();
        expectAi(code, 1);
        MvcResult r = mockMvc.perform(as(admin, multipart("/api/users/staff-accounts")
                        .file(new MockMultipartFile("faceImage", "front.jpg", "image/jpeg", fakeJpeg(code)))
                        .param("fullName", "Audit Account " + code)
                        .param("userCode", code)
                        .param("email", code.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                        .param("role", Role.NORMAL_USER.name()), ip))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(201);
        aiServer.verify();
        return r;
    }

    private UUID importBatch(List<String> codes, String twoFaceCode, String ip) throws Exception {
        aiServer.reset();
        StringBuilder csv = new StringBuilder("user_code,full_name,email\n");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (String code : codes) {
            createdCodes.add(code);
            csv.append(code).append(",Audit Import ").append(code).append(',')
                    .append(code.toLowerCase(Locale.ROOT)).append("@fpt.edu.vn\n");
            expectAi(code, code.equals(twoFaceCode) ? 2 : 1);
        }
        entries.put("metadata.csv", csv.toString().getBytes(StandardCharsets.UTF_8));
        for (String code : codes) {
            entries.put("images/" + code + ".jpg", fakeJpeg(code));
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        MvcResult r = mockMvc.perform(as(admin, multipart("/api/users/normal/bulk-import")
                        .file(new MockMultipartFile("file", "audit-batch.zip", "application/zip", bos.toByteArray())), ip))
                .andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        aiServer.verify();
        return UUID.fromString(body(r).path("data").path("importBatchId").asText());
    }

    private UUID userIdOf(String code) {
        return jdbc.queryForObject("SELECT id FROM users WHERE user_code = ? AND deleted_at IS NULL", UUID.class, code);
    }

    // ------------------------------------------------------------------ tạo tài khoản lẻ

    @Test
    @DisplayName("B3 tạo tài khoản lẻ: 1 CREATE / USER_ACCOUNT + 1 REGISTER_FACE, cùng correlation, actor ADMIN, có IP + user agent, không dữ liệu nhạy cảm")
    void createAccount_recordsCreateAndFaceRegistration() throws Exception {
        String code = newCode("ONE");
        String ip = "10.40.0.11";
        UUID userId = UUID.fromString(body(createAccount(code, ip)).path("data").path("id").asText());

        Map<String, Object> create = single("USER_ACCOUNT", "CREATE", userId.toString());
        assertThat(create.get("subject_user_id")).isEqualTo(userId);
        assertThat(create.get("actor_type")).isEqualTo("USER");
        assertThat(create.get("changed_by")).isEqualTo(admin.getId());
        assertHttpOrigin(create, ip);
        assertNoSensitive(create);
        JsonNode snap = json(create, "new_json");
        assertThat(snap.path("userCode").asText()).isEqualTo(code);
        assertThat(snap.path("role").asText()).isEqualTo("NORMAL_USER");
        assertThat(snap.path("accessLevel").asInt()).isEqualTo(1);
        assertThat(snap.path("isActive").asBoolean()).isTrue();
        assertThat(snap.path("hasFaceData").asBoolean()).isTrue();

        Map<String, Object> face = single("FACE_REGISTRATION", "REGISTER_FACE", code);
        assertThat(face.get("changed_by")).isEqualTo(admin.getId());
        assertThat(face.get("correlation_id")).isEqualTo(create.get("correlation_id"));
        assertThat(json(face, "new_json").path("replaced").asBoolean(true)).isFalse();
        assertHttpOrigin(face, ip);
        assertNoSensitive(face);
    }

    // ------------------------------------------------------------------ import lô

    @Test
    @DisplayName("B3 import: 1 IMPORT / USER_IMPORT_BATCH (luồng, file, tổng / thành công / lỗi) + CREATE cho mỗi tài khoản tạo ra, correlation = batchId; dòng lỗi không có audit tài khoản")
    void bulkImport_recordsBatchAndAccountsWithBatchCorrelation() throws Exception {
        String ok1 = newCode("IMP");
        String ok2 = newCode("IMP");
        String twoFaces = newCode("IMP");
        String ip = "10.40.0.12";
        UUID batchId = importBatch(List.of(ok1, twoFaces, ok2), twoFaces, ip);

        Map<String, Object> batch = single("USER_IMPORT_BATCH", "IMPORT", batchId.toString());
        assertThat(batch.get("correlation_id")).isEqualTo(batchId);
        assertThat(batch.get("changed_by")).isEqualTo(admin.getId());
        assertHttpOrigin(batch, ip);
        assertNoSensitive(batch);
        JsonNode snap = json(batch, "new_json");
        assertThat(snap.path("flow").asText()).isEqualTo("NORMAL");
        assertThat(snap.path("fileName").asText()).isEqualTo("audit-batch.zip");
        assertThat(snap.path("totalRows").asInt()).isEqualTo(3);
        assertThat(snap.path("successCount").asInt()).isEqualTo(2);
        assertThat(snap.path("failureCount").asInt()).isEqualTo(1);

        for (String code : List.of(ok1, ok2)) {
            UUID uid = userIdOf(code);
            Map<String, Object> create = single("USER_ACCOUNT", "CREATE", uid.toString());
            assertThat(create.get("correlation_id")).isEqualTo(batchId);
            assertThat(create.get("changed_by")).isEqualTo(admin.getId());
            assertThat(create.get("subject_user_id")).isEqualTo(uid);
            assertHttpOrigin(create, ip);
            assertNoSensitive(create);
            Map<String, Object> face = single("FACE_REGISTRATION", "REGISTER_FACE", code);
            assertThat(face.get("correlation_id")).isEqualTo(batchId);
            assertThat(face.get("subject_user_id")).isEqualTo(uid);
        }
        assertThat(audits("FACE_REGISTRATION", "REGISTER_FACE", twoFaces)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE correlation_id = ?", Integer.class, batchId))
                .as("IMPORT + 2 CREATE + 2 REGISTER_FACE").isEqualTo(5);
    }

    // ------------------------------------------------------------------ vô hiệu hoá / kích hoạt lại / xoá

    @Test
    @DisplayName("B3 toggleActive: DEACTIVATE (cũ → mới + số đơn huỷ / AP thu hồi / phiên đóng) rồi REACTIVATE, actor ADMIN, có IP")
    void deactivateAndReactivate_recordStatusChangeWithEffects() throws Exception {
        User target = fixtureUser("tgl", Role.NORMAL_USER);
        String ip = "10.40.0.13";
        assertThat(mockMvc.perform(as(admin, patch("/api/users/{id}/toggle-active", target.getId()), ip))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        Map<String, Object> off = single("USER_ACCOUNT", "DEACTIVATE", target.getId().toString());
        assertThat(off.get("changed_by")).isEqualTo(admin.getId());
        assertHttpOrigin(off, ip);
        assertNoSensitive(off);
        assertThat(json(off, "old_json").path("isActive").asBoolean()).isTrue();
        JsonNode after = json(off, "new_json");
        assertThat(after.path("isActive").asBoolean(true)).isFalse();
        assertThat(after.path("cancelledRequests").asInt(-1)).isZero();
        assertThat(after.path("revokedAssignments").asInt(-1)).isZero();
        assertThat(after.path("websocketSessionsToClose").asInt(-1)).isZero();

        assertThat(mockMvc.perform(as(admin, patch("/api/users/{id}/toggle-active", target.getId()), ip))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> on = single("USER_ACCOUNT", "REACTIVATE", target.getId().toString());
        assertThat(on.get("changed_by")).isEqualTo(admin.getId());
        assertThat(json(on, "new_json").path("isActive").asBoolean()).isTrue();
        assertHttpOrigin(on, ip);
    }

    @Test
    @DisplayName("B3 softDelete: DELETE / USER_ACCOUNT (deleted = true), actor ADMIN, có IP")
    void softDelete_recordsDelete() throws Exception {
        User target = fixtureUser("del", Role.NORMAL_USER);
        String ip = "10.40.0.14";
        assertThat(mockMvc.perform(as(admin, delete("/api/users/{id}", target.getId()), ip))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> row = single("USER_ACCOUNT", "DELETE", target.getId().toString());
        assertThat(row.get("changed_by")).isEqualTo(admin.getId());
        assertThat(json(row, "new_json").path("deleted").asBoolean()).isTrue();
        assertHttpOrigin(row, ip);
        assertNoSensitive(row);
    }

    // ------------------------------------------------------------------ gỡ / khôi phục lô

    @Test
    @DisplayName("B3 deleteBatch / restoreBatch: DELETE / USER_IMPORT_BATCH (+ danh sách mã), RESTORE (+ số khôi phục / bỏ qua)")
    void deleteAndRestoreBatch_recordBatchAudits() throws Exception {
        String a = newCode("BAT");
        String b = newCode("BAT");
        String ip = "10.40.0.15";
        UUID batchId = importBatch(List.of(a, b), null, ip);

        assertThat(mockMvc.perform(as(admin, delete("/api/users/import-batches/{id}", batchId), ip))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> del = single("USER_IMPORT_BATCH", "DELETE", batchId.toString());
        assertThat(del.get("changed_by")).isEqualTo(admin.getId());
        JsonNode delSnap = json(del, "new_json");
        assertThat(delSnap.path("affectedCount").asInt()).isEqualTo(2);
        assertThat(objectMapper.convertValue(delSnap.path("userCodes"), List.class)).containsExactlyInAnyOrder(a, b);
        assertHttpOrigin(del, ip);

        assertThat(mockMvc.perform(as(admin, post("/api/users/import-batches/{id}/restore", batchId), ip))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> res = single("USER_IMPORT_BATCH", "RESTORE", batchId.toString());
        assertThat(res.get("changed_by")).isEqualTo(admin.getId());
        JsonNode resSnap = json(res, "new_json");
        assertThat(resSnap.path("affectedCount").asInt()).isEqualTo(2);
        assertThat(resSnap.path("skippedCount").asInt()).isZero();
        assertHttpOrigin(res, ip);
        assertNoSensitive(res);
    }

    // ------------------------------------------------------------------ cấu hình hệ thống

    @Test
    @DisplayName("B3 SystemConfigService.update: UPDATE / SYSTEM_CONFIG (khoá, cũ → mới, lý do), có IP; change log cũ vẫn ghi")
    void systemConfigUpdate_recordsOldNewReasonAndIp() throws Exception {
        String key = ConfigKey.ENTRY_LOG_RETENTION_DAYS.name();
        String previous = systemConfigService.getString(ConfigKey.ENTRY_LOG_RETENTION_DAYS);
        String next = String.valueOf(Integer.parseInt(previous) + 1);
        String reason = "Kiểm thử audit cấu hình " + UUID.randomUUID().toString().substring(0, 6);
        String ip = "10.40.0.16";
        int changeLogsBefore = jdbc.queryForObject("SELECT count(*) FROM system_configuration_change_logs WHERE config_key = ?",
                Integer.class, key);
        try {
            MvcResult r = mockMvc.perform(as(admin, patch("/api/system-configurations/{key}", key), ip)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("configValue", next, "reason", reason))))
                    .andReturn();
            assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(200);

            List<Map<String, Object>> rows = jdbc.queryForList("SELECT changed_by, reason, ip_address, user_agent, "
                    + "old_value::text AS old_json, new_value::text AS new_json FROM audit_logs "
                    + "WHERE target_type = 'SYSTEM_CONFIG' AND action = 'UPDATE' AND target_id = ? AND reason = ?", key, reason);
            assertThat(rows).hasSize(1);
            Map<String, Object> row = rows.get(0);
            assertThat(row.get("changed_by")).isEqualTo(admin.getId());
            assertThat(json(row, "old_json").path("value").asText()).isEqualTo(previous);
            assertThat(json(row, "new_json").path("value").asText()).isEqualTo(next);
            assertHttpOrigin(row, ip);
            assertNoSensitive(row);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM system_configuration_change_logs WHERE config_key = ?",
                    Integer.class, key)).isEqualTo(changeLogsBefore + 1);
        } finally {
            systemConfigService.update(key, previous, admin.getEmail());
        }
    }

    // ------------------------------------------------------------------ đọc nhật ký: quyền + bộ lọc

    @Test
    @DisplayName("B6 quyền: ADMIN đọc ACCOUNT / AUTH / SYSTEM_CONFIG; FM, NORMAL_USER, GUARD -> 403; FM không thấy module ACCOUNT / AUTH ở API chung")
    void readPermissions_adminOnly() throws Exception {
        User fm = fixtureUser("fm", Role.FACILITY_MANAGER);
        User normal = fixtureUser("nu", Role.NORMAL_USER);
        User guard = fixtureUser("gd", Role.GUARD);
        String ip = "10.40.0.17";
        createAccount(newCode("PRM"), ip);

        for (String module : List.of("ACCOUNT", "AUTH", "SYSTEM_CONFIG")) {
            MvcResult r = mockMvc.perform(as(admin, get("/api/users/audit-logs").param("modules", module), ip)).andReturn();
            assertThat(r.getResponse().getStatus()).as(module).isEqualTo(200);
            for (JsonNode row : body(r).path("data").path("content")) {
                assertThat(row.path("module").asText()).isEqualTo(module);
            }
        }
        for (User u : List.of(fm, normal, guard)) {
            assertThat(mockMvc.perform(as(u, get("/api/users/audit-logs"), ip)).andReturn().getResponse().getStatus())
                    .as(u.getRole().name()).isEqualTo(403);
        }
        // API chung: FM xin module ACCOUNT / AUTH -> 403; không chỉ định module -> không có dòng nào của 3 module mới
        for (String module : List.of("ACCOUNT", "AUTH", "SYSTEM_CONFIG")) {
            assertThat(mockMvc.perform(as(fm, get("/api/access-control/audit-logs").param("module", module), ip))
                    .andReturn().getResponse().getStatus()).as(module).isEqualTo(403);
        }
        MvcResult fmAll = mockMvc.perform(as(fm, get("/api/access-control/audit-logs").param("size", "100"), ip)).andReturn();
        assertThat(fmAll.getResponse().getStatus()).isEqualTo(200);
        for (JsonNode row : body(fmAll).path("data").path("content")) {
            assertThat(row.path("module").asText()).isNotIn("ACCOUNT", "AUTH", "SYSTEM_CONFIG");
        }
    }

    @Test
    @DisplayName("B6 bộ lọc: subjectUserId (gồm REGISTER_FACE ghi theo mã), correlationId của lô, actions nhiều giá trị; sắp thời gian giảm dần")
    void accountLogFilters() throws Exception {
        String ip = "10.40.0.18";
        String code = newCode("FLT");
        UUID userId = UUID.fromString(body(createAccount(code, ip)).path("data").path("id").asText());

        MvcResult byUser = mockMvc.perform(as(admin, get("/api/users/audit-logs")
                .param("subjectUserId", userId.toString()), ip)).andReturn();
        JsonNode content = body(byUser).path("data").path("content");
        List<String> actions = new ArrayList<>();
        content.forEach(n -> actions.add(n.path("action").asText()));
        assertThat(actions).containsExactlyInAnyOrder("CREATE", "REGISTER_FACE");
        assertThat(content.get(0).path("ipAddress").asText()).isEqualTo(ip);
        assertThat(content.get(0).path("userAgent").asText()).isEqualTo(UA);
        assertThat(java.time.OffsetDateTime.parse(content.get(0).path("changedAt").asText()))
                .isAfterOrEqualTo(java.time.OffsetDateTime.parse(content.get(1).path("changedAt").asText()));

        String a = newCode("FLB");
        UUID batchId = importBatch(List.of(a), null, ip);
        MvcResult byBatch = mockMvc.perform(as(admin, get("/api/users/audit-logs")
                .param("correlationId", batchId.toString())
                .param("actions", "CREATE", "IMPORT"), ip)).andReturn();
        List<String> batchActions = new ArrayList<>();
        body(byBatch).path("data").path("content").forEach(n -> batchActions.add(n.path("action").asText()));
        assertThat(batchActions).containsExactlyInAnyOrder("IMPORT", "CREATE");
    }

    // ------------------------------------------------------------------ append-only

    @Test
    @DisplayName("Append-only: UPDATE (kể cả cột mới ip_address / user_agent) và DELETE audit_logs vẫn bị trigger chặn")
    void auditLogs_appendOnly_includingNewColumns() throws Exception {
        String code = newCode("APP");
        UUID userId = UUID.fromString(body(createAccount(code, "10.40.0.19")).path("data").path("id").asText());
        UUID auditId = jdbc.queryForObject("SELECT id FROM audit_logs WHERE target_type = 'USER_ACCOUNT' AND action = 'CREATE' "
                + "AND target_id = ?", UUID.class, userId.toString());

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET ip_address = '1.1.1.1' WHERE id = ?", auditId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET user_agent = 'x' WHERE id = ?", auditId))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs WHERE id = ?", auditId))
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("SELECT ip_address FROM audit_logs WHERE id = ?", String.class, auditId))
                .isEqualTo("10.40.0.19");
    }
}
