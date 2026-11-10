package com.fa26se040.icss.e2e;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.consumer.SecurityIncidentMqttConsumer;
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
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.InternalServiceKeyFilter;
import com.fa26se040.icss.service.AccessRequestService;
import com.fa26se040.icss.service.SecurityIncidentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * TC-WF3-E2E (DS-03a / DS-03b) — WF-03 Zone Access Granting, end-to-end mức backend.
 *
 * <p>Một luồng liên tục, các bước chạy theo {@link Order}; bước trước hỏng thì bước sau bị bỏ qua (assumption).
 * Tạo đơn, duyệt đơn, check-entry đi qua HTTP thật (MockMvc: JWT thật lấy từ /api/auth/login, X-Service-Key,
 * {@code @PreAuthorize}); DB test thật; job kết thúc đơn gọi thẳng bean như AccessRequestFinishElapsedJobTest.</p>
 *
 * <p>Giả lập AI: (1) check-entry là lời gọi AI -&gt; backend, test đóng vai AI gửi request HTTP với X-Service-Key;
 * (2) sự cố từ AI đi qua MQTT topic {@code campus/alerts/incidents}: test không dựng broker (app.mqtt.enabled=false
 * trong profile test) mà giao tin thẳng cho listener {@link SecurityIncidentMqttConsumer#messageArrived} với payload
 * đúng định dạng {@code SecurityAlertEvent.to_dict()} của ai-service; (3) gọi bật ghi hình sang AI bị tắt bằng
 * {@code aiServiceUrl = ""} như SecurityIncidentDedupeIntegrationTest.</p>
 *
 * <p>Điều khiển thời gian: khung đơn = ngày mai 10:00–11:00 giờ VN (ban ngày, ngoài khung "ngoài giờ" 22:00–06:00
 * nên phân loại nhật ký không phụ thuộc giờ chạy); thời điểm check-entry truyền qua trường {@code timestamp}.
 * Bước "hết giờ" dời khung đơn về quá khứ (UPDATE start_time / end_time) rồi chạy job, toàn bộ trong một transaction
 * rollback ở cuối — cùng cách AccessRequestFinishElapsedJobTest để job không đổi dữ liệu khác trên DB test dùng chung.
 * Không sleep.</p>
 */
@DisplayName("TC-WF3-E2E – Zone Access Granting (backend end-to-end, AI service mocked)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowWf03ZoneAccessGrantingE2ETest extends AbstractIntegrationTest {

    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String PASSWORD = "E2E-Wf3-Pass-01";
    private static final String MQTT_TOPIC = "campus/alerts/incidents";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private CameraRepository cameraRepository;
    @Autowired private AccessRequestService accessRequestService;
    @Autowired private SecurityIncidentService securityIncidentService;
    @Autowired private SecurityIncidentMqttConsumer securityIncidentMqttConsumer;
    @Value("${icss.internal.service-key}") private String serviceKey;

    private final List<UUID> createdUsers = new ArrayList<>();
    private Object originalIncidentAiUrl;
    private String suffix;
    private User userA;
    private User userB;
    private User fm;
    private Area area;
    private Camera camera;
    private String incidentEventId;

    private OffsetDateTime windowStart;
    private OffsetDateTime windowEnd;
    private String tokenA;
    private String tokenFm;
    private UUID requestId;

    private int completedStep;

    @BeforeAll
    void setUpFlow() {
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);

        Building building = buildingRepository.findByNameIgnoreCase("Tòa E2E WF")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa E2E WF").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa E2E WF", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        area = areaRepository.save(Area.builder()
                .name("E2E WF3 phòng bảo mật " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .build());
        camera = cameraRepository.save(Camera.builder()
                .cameraCode("E2E3-CAM-" + suffix)
                .name("E2E WF3 camera " + suffix)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build());

        userA = user("A", Role.NORMAL_USER, 1);
        userB = user("B", Role.NORMAL_USER, 1);
        fm = user("FM", Role.FACILITY_MANAGER, 3);

        LocalDate tomorrow = LocalDate.now(VN).plusDays(1);
        windowStart = tomorrow.atTime(LocalTime.of(10, 0)).atZone(VN).toOffsetDateTime();
        windowEnd = tomorrow.atTime(LocalTime.of(11, 0)).atZone(VN).toOffsetDateTime();

        Object target = AopTestUtils.getUltimateTargetObject(securityIncidentService);
        originalIncidentAiUrl = ReflectionTestUtils.getField(target, "aiServiceUrl");
        ReflectionTestUtils.setField(target, "aiServiceUrl", "");
    }

    @AfterAll
    void cleanUp() {
        Object target = AopTestUtils.getUltimateTargetObject(securityIncidentService);
        ReflectionTestUtils.setField(target, "aiServiceUrl", originalIncidentAiUrl);

        if (incidentEventId != null) {
            jdbc.update("DELETE FROM security_incidents WHERE event_id = ?", incidentEventId);
        }
        if (camera != null) {
            jdbc.update("DELETE FROM camera_access_logs WHERE camera_code = ?", camera.getCameraCode());
            cameraRepository.deleteById(camera.getId());
        }
        if (requestId != null) {
            jdbc.update("DELETE FROM access_requests WHERE id = ?", requestId);
        }
        // audit_logs (append-only) giữ FK tới khu và user -> không xoá được: ẩn khu, khoá user (như các test MF3 khác)
        if (area != null) {
            jdbc.update("UPDATE areas SET is_active = false, deleted_at = now() WHERE id = ?", area.getId());
        }
        for (User u : userRepository.findAllById(createdUsers)) {
            u.setIsActive(false);
            userRepository.save(u);
        }
    }

    // ------------------------------------------------------------------ helpers

    private User user(String tag, Role role, int level) {
        User u = userRepository.save(User.builder()
                .userCode("E2E3-" + tag + "-" + suffix)
                .fullName("E2E WF3 " + tag + " " + suffix)
                .email("e2e3-" + tag.toLowerCase(Locale.ROOT) + "-" + suffix.toLowerCase(Locale.ROOT) + "@fpt.edu.vn")
                .password(passwordEncoder.encode(PASSWORD))
                .role(role)
                .accessLevel(level)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private JsonNode body(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static String text(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String login(User u) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", u.getEmail(), "password", PASSWORD))))
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(text(r)).isEqualTo(200);
        String token = body(r).path("data").path("accessToken").asText();
        assertThat(token).isNotBlank();
        return token;
    }

    private static String iso(OffsetDateTime t) {
        return t.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    private MvcResult checkEntry(User u, OffsetDateTime at) throws Exception {
        return mockMvc.perform(post("/api/access-control/check-entry")
                        .header(InternalServiceKeyFilter.HEADER, serviceKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cameraCode", camera.getCameraCode(),
                                "userCode", u.getUserCode(),
                                "timestamp", iso(at)))))
                .andReturn();
    }

    private Map<String, Object> accessLogAt(User u, OffsetDateTime at) {
        return jdbc.queryForMap("SELECT user_id, user_code, access_result, access_source, area_id FROM camera_access_logs "
                        + "WHERE camera_code = ? AND user_code = ? AND logged_at = CAST(? AS timestamptz)",
                camera.getCameraCode(), u.getUserCode(), iso(at));
    }

    private Map<String, Object> requestRow() {
        return jdbc.queryForMap("SELECT status, reviewer_id, finish_reason, finished_by FROM access_requests WHERE id = ?", requestId);
    }

    private List<Map<String, Object>> audits(String action) {
        return jdbc.queryForList("SELECT actor_type, changed_by, actor_source, reason, new_value ->> 'status' AS new_status "
                + "FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = ? AND target_id = ?", action, requestId.toString());
    }

    private int notifications(User recipient, String type) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = ? AND reference_id = ?",
                Integer.class, recipient.getId(), type, requestId);
    }

    private void requireStep(int previous) {
        assumeTrue(completedStep == previous, "Bước " + previous + " chưa qua, bỏ qua bước sau trong cùng luồng");
    }

    // ------------------------------------------------------------------ bước 1

    @Test
    @Order(1)
    @DisplayName("Bước 1: user A (cấp 1) tạo đơn cá nhân vào khu Bảo mật cho khung giờ sắp tới qua HTTP -> 201 PENDING, audit CREATE, FM được báo")
    void step1_userCreatesIndividualRequest() throws Exception {
        requireStep(0);
        tokenA = login(userA);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("areaId", area.getId());
        payload.put("startTime", iso(windowStart));
        payload.put("endTime", iso(windowEnd));
        payload.put("purpose", "Kiểm thử E2E WF-03 mượn phòng " + suffix);
        MvcResult r = mockMvc.perform(post("/api/access-requests/individual")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andReturn();

        assertThat(r.getResponse().getStatus()).as(text(r)).isEqualTo(201);
        JsonNode data = body(r).path("data");
        requestId = UUID.fromString(data.path("id").asText());
        assertThat(data.path("status").asText()).isEqualTo("PENDING");
        assertThat(data.path("requestType").asText()).isEqualTo("INDIVIDUAL");
        assertThat(data.path("requesterCode").asText()).isEqualTo(userA.getUserCode());
        assertThat(data.path("areaId").asText()).isEqualTo(area.getId().toString());
        assertThat(OffsetDateTime.parse(data.path("startTime").asText()).toInstant()).isEqualTo(windowStart.toInstant());
        assertThat(OffsetDateTime.parse(data.path("endTime").asText()).toInstant()).isEqualTo(windowEnd.toInstant());

        assertThat(requestRow().get("status")).isEqualTo("PENDING");
        List<Map<String, Object>> created = audits("CREATE");
        assertThat(created).hasSize(1);
        assertThat(created.get(0).get("actor_type")).isEqualTo("USER");
        assertThat(created.get(0).get("changed_by")).isEqualTo(userA.getId());
        assertThat(created.get(0).get("new_status")).isEqualTo("PENDING");
        assertThat(notifications(fm, "NEW_REQUEST_PENDING")).isEqualTo(1);
        completedStep = 1;
    }

    // ------------------------------------------------------------------ bước 2

    @Test
    @Order(2)
    @DisplayName("Bước 2: FM (khác người tạo) duyệt qua HTTP trước giờ bắt đầu -> APPROVED, audit APPROVE, thông báo REQUEST_APPROVED cho A")
    void step2_facilityManagerApproves() throws Exception {
        requireStep(1);
        tokenFm = login(fm);
        assertThat(OffsetDateTime.now()).isBefore(windowStart);

        MvcResult r = mockMvc.perform(patch("/api/access-requests/{id}/review", requestId)
                        .header("Authorization", "Bearer " + tokenFm)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPROVED\"}"))
                .andReturn();

        assertThat(r.getResponse().getStatus()).as(text(r)).isEqualTo(200);
        JsonNode data = body(r).path("data");
        assertThat(data.path("status").asText()).isEqualTo("APPROVED");
        assertThat(data.path("reviewerId").asText()).isEqualTo(fm.getId().toString());
        assertThat(data.path("reviewedAt").asText()).isNotBlank();

        Map<String, Object> row = requestRow();
        assertThat(row.get("status")).isEqualTo("APPROVED");
        assertThat(row.get("reviewer_id")).isEqualTo(fm.getId());
        List<Map<String, Object>> approved = audits("APPROVE");
        assertThat(approved).hasSize(1);
        assertThat(approved.get(0).get("actor_type")).isEqualTo("USER");
        assertThat(approved.get(0).get("changed_by")).isEqualTo(fm.getId());
        assertThat(approved.get(0).get("new_status")).isEqualTo("APPROVED");
        assertThat(notifications(userA, "REQUEST_APPROVED")).isEqualTo(1);

        // Người tạo đơn thấy trạng thái Đã duyệt ở danh sách đơn của mình
        MvcResult mine = mockMvc.perform(get("/api/access-requests/my").header("Authorization", "Bearer " + tokenA)).andReturn();
        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        JsonNode content = body(mine).path("data").path("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("id").asText()).isEqualTo(requestId.toString());
        assertThat(content.get(0).path("status").asText()).isEqualTo("APPROVED");
        completedStep = 2;
    }

    // ------------------------------------------------------------------ bước 3

    @Test
    @Order(3)
    @DisplayName("Bước 3: check-entry của A: trước khung -> allowed=false; trong khung -> allowed=true nguồn ACCESS_REQUEST, nhật ký AUTHORIZED_PERSON")
    void step3_checkEntryBeforeAndInsideWindow() throws Exception {
        requireStep(2);
        OffsetDateTime beforeWindow = windowStart.minusMinutes(30);
        OffsetDateTime insideWindow = windowStart.plusMinutes(30);

        MvcResult before = checkEntry(userA, beforeWindow);
        assertThat(before.getResponse().getStatus()).isEqualTo(200);
        JsonNode b = body(before).path("data");
        assertThat(b.path("allowed").asBoolean(true)).isFalse();
        assertThat(b.path("source").asText()).isEqualTo("NONE");
        assertThat(b.path("reason").asText()).startsWith("Chưa tới khung giờ được cấp phép theo đơn");
        Map<String, Object> beforeLog = accessLogAt(userA, beforeWindow);
        assertThat(beforeLog.get("access_result")).isEqualTo("UNAUTHORIZED_PERSON");
        assertThat(beforeLog.get("user_id")).isEqualTo(userA.getId());

        MvcResult inside = checkEntry(userA, insideWindow);
        assertThat(inside.getResponse().getStatus()).isEqualTo(200);
        JsonNode in = body(inside).path("data");
        assertThat(in.path("allowed").asBoolean(false)).isTrue();
        assertThat(in.path("source").asText()).isEqualTo("ACCESS_REQUEST");
        // userId / fullName có @JsonIgnore trong CheckCameraEntryResponse: AI chỉ nhận mã người
        assertThat(in.path("userCode").asText()).isEqualTo(userA.getUserCode());
        assertThat(in.has("userId")).isFalse();
        assertThat(in.path("areaId").asText()).isEqualTo(area.getId().toString());

        Map<String, Object> log = accessLogAt(userA, insideWindow);
        assertThat(log.get("access_result")).isEqualTo("AUTHORIZED_PERSON");
        assertThat(log.get("access_source")).isEqualTo("ACCESS_REQUEST");
        assertThat(log.get("user_id")).isEqualTo(userA.getId());
        assertThat(log.get("area_id")).isEqualTo(area.getId());
        completedStep = 3;
    }

    // ------------------------------------------------------------------ bước 4

    /** Payload đúng định dạng SecurityAlertEvent.to_dict() (ai-service/src/core/entity.py) và câu details của frame_analysis_engine.py. */
    private byte[] aiUnauthorizedAlert(User subject, OffsetDateTime detectedAt) throws Exception {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("event_id", incidentEventId);
        event.put("camera_code", camera.getCameraCode());
        event.put("event_type", "UNAUTHORIZED_PERSON");
        event.put("track_id", 7);
        event.put("duration_seconds", 2.5);
        event.put("confidence", 0.912);
        event.put("image_url", null);
        event.put("detected_at", detectedAt.withOffsetSameInstant(java.time.ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        event.put("details", "[UNAUTHORIZED_PERSON] Tên: " + subject.getFullName() + " (" + subject.getUserCode() + ")\n"
                + "Lý do: Cấp độ không đủ\nVị trí: " + area.getName());
        event.put("location", Map.of());
        return objectMapper.writeValueAsBytes(event);
    }

    @Test
    @Order(4)
    @DisplayName("Bước 4: check-entry của B (không có nguồn quyền) cùng camera, cùng lúc -> allowed=false, nhật ký UNAUTHORIZED_PERSON; AI gửi sự cố qua MQTT -> sự cố NEW")
    void step4_unauthorizedUserDeniedAndIncidentRaised() throws Exception {
        requireStep(3);
        OffsetDateTime insideWindow = windowStart.plusMinutes(30);

        MvcResult r = checkEntry(userB, insideWindow);
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode d = body(r).path("data");
        assertThat(d.path("allowed").asBoolean(true)).isFalse();
        assertThat(d.path("source").asText()).isEqualTo("NONE");
        assertThat(d.path("reason").asText()).isEqualTo("Cấp độ truy cập không đủ (Khu vực Cấp 2, người dùng Cấp 1)");
        Map<String, Object> log = accessLogAt(userB, insideWindow);
        assertThat(log.get("access_result")).isEqualTo("UNAUTHORIZED_PERSON");
        assertThat(log.get("access_source")).isEqualTo("NONE");
        assertThat(log.get("user_id")).isEqualTo(userB.getId());

        // AI nhận allowed=false -> phát cảnh báo UNAUTHORIZED_PERSON lên MQTT; giao thẳng cho listener của backend
        incidentEventId = "E2E3-" + UUID.randomUUID();
        securityIncidentMqttConsumer.messageArrived(MQTT_TOPIC, new MqttMessage(aiUnauthorizedAlert(userB, insideWindow)));

        Map<String, Object> incident = jdbc.queryForMap(
                "SELECT status, event_type, area_id, camera_code, subject_user_code, resolution_notes "
                        + "FROM security_incidents WHERE event_id = ?", incidentEventId);
        assertThat(incident.get("status")).isEqualTo("NEW");
        assertThat(incident.get("event_type")).isEqualTo("UNAUTHORIZED_PERSON");
        assertThat(incident.get("area_id")).isEqualTo(area.getId());
        assertThat(incident.get("camera_code")).isEqualTo(camera.getCameraCode());
        assertThat(incident.get("subject_user_code")).isEqualTo(userB.getUserCode());
        assertThat((String) incident.get("resolution_notes")).contains(userB.getUserCode()).contains("Cấp độ không đủ");
        completedStep = 4;
    }

    // ------------------------------------------------------------------ bước 5 + 6

    @Test
    @Order(5)
    @DisplayName("Bước 5–6: hết giờ -> job finish-elapsed chuyển FINISHED (finished_by NULL, lý do hệ thống, 1 audit); check-entry của A sau đó -> allowed=false")
    void step5and6_finishElapsedJobThenEntryDenied() throws Exception {
        requireStep(4);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            try {
                // Mô phỏng thời gian trôi qua: khung đơn đã kết thúc trước now (job đọc OffsetDateTime.now())
                OffsetDateTime now = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS);
                OffsetDateTime shiftedStart = now.minusHours(2);
                OffsetDateTime shiftedEnd = now.minusMinutes(1);
                OffsetDateTime insideShifted = now.minusHours(1);
                assertThat(jdbc.update("UPDATE access_requests SET start_time = ?, end_time = ? WHERE id = ?",
                        shiftedStart, shiftedEnd, requestId)).isEqualTo(1);

                // Mốc đối chứng: còn APPROVED thì cùng thời điểm này được vào
                JsonNode control = body(checkEntry(userA, insideShifted)).path("data");
                assertThat(control.path("allowed").asBoolean(false)).isTrue();
                assertThat(control.path("source").asText()).isEqualTo("ACCESS_REQUEST");

                // Bước 5: job hệ thống
                int finished = accessRequestService.finishElapsedApprovedRequests();
                assertThat(finished).isGreaterThanOrEqualTo(1);
                Map<String, Object> row = requestRow();
                assertThat(row.get("status")).isEqualTo("FINISHED");
                assertThat(row.get("finished_by")).isNull();
                assertThat(row.get("finish_reason")).isEqualTo(AccessRequestService.FINISH_REASON_ELAPSED);
                List<Map<String, Object>> finishAudits = audits("FINISH");
                assertThat(finishAudits).hasSize(1);
                assertThat(finishAudits.get(0).get("actor_type")).isEqualTo("SYSTEM");
                assertThat(finishAudits.get(0).get("changed_by")).isNull();
                assertThat(finishAudits.get(0).get("actor_source")).isEqualTo("FINISH_ELAPSED_REQUESTS_JOB");
                assertThat(finishAudits.get(0).get("reason")).isEqualTo(AccessRequestService.FINISH_REASON_ELAPSED);
                assertThat(finishAudits.get(0).get("new_status")).isEqualTo("FINISHED");

                // Bước 6: cùng thời điểm, đơn đã FINISHED -> không còn nguồn quyền
                MvcResult after = checkEntry(userA, insideShifted);
                assertThat(after.getResponse().getStatus()).isEqualTo(200);
                JsonNode a = body(after).path("data");
                assertThat(a.path("allowed").asBoolean(true)).isFalse();
                assertThat(a.path("source").asText()).isEqualTo("NONE");
                assertThat(a.path("reason").asText()).isEqualTo("Cấp độ truy cập không đủ (Khu vực Cấp 2, người dùng Cấp 1)");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                tx.setRollbackOnly();
            }
        });
        completedStep = 6;
    }
}
