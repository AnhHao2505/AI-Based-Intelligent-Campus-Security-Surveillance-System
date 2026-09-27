package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.area.AreaGeometry;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.repository.*;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Step 5b — dữ liệu và tiện ích dùng chung cho các test viết trước (phải ĐỎ trước khi hiện thực).
 *
 * Hợp đồng API mà các test giả định (chờ Lucas xác nhận, xem báo cáo phần A):
 * - PUT /api/areas/{id}: body AreaUpdateRequest + "reason" (bắt buộc khi đổi loại) + "version".
 * - PATCH /api/areas/{id}/access-rules: body + "version".
 * - PATCH /api/areas/{id}/event-mode: body {action, openUntil, reasonCode, note, version}; KHÔNG có "enabled".
 * - PATCH / DELETE /api/areas/{id}/geometry: version gửi qua query param ?version= (AreaGeometry.version đã mang
 *   nghĩa "phiên bản định dạng hình học").
 * - GET /api/areas/{id}/type-change-preview?newAreaLevel=...
 * - Mã lỗi: ERR_AREA_044 thiếu version (400), ERR_AREA_045 lệch version (409),
 *   ERR_AREA_046 body có "enabled" (400), ERR_AREA_047 thiếu action (400).
 * - Cột huỷ đơn: access_requests.cancel_source / cancelled_by / cancel_reason; cột areas.version.
 * Các cột chưa có (V56) được đọc bằng JdbcTemplate, nên test sẽ lỗi SQL cho tới khi migration được thêm.
 */
public abstract class Step5bTestSupport extends AbstractIntegrationTest {

    protected static final String AREA_TYPE_CHANGE_SOURCE = "AREA_TYPE_CHANGE";
    protected static final String PUBLIC_CANCEL_REASON = "Khu vực đã chuyển sang công khai, không cần đơn";
    protected static final String TYPE_CHANGE_REASON = "Điều chỉnh loại khu vực theo quyết định an ninh";
    protected static final String ACCESS_RULES_REASON = "Điều chỉnh quy tắc truy cập theo yêu cầu khoa";
    protected static final String EVENT_NOTE = "Hội thảo khoa CNTT tổ chức tại khu vực";
    protected static final DateTimeFormatter VN_TIME =
            DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JwtTokenProvider jwtTokenProvider;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected AreaRepository areaRepository;

    @Autowired
    protected BuildingRepository buildingRepository;

    @Autowired
    protected FloorRepository floorRepository;

    @Autowired
    protected AuditLogRepository auditLogRepository;

    @Autowired
    protected AreaEventScheduleRepository eventScheduleRepository;

    @Autowired
    protected AreaEventSessionRepository sessionRepository;

    @Autowired
    protected ReasonCatalogRepository reasonCatalogRepository;

    @Autowired
    protected NotificationRepository notificationRepository;

    @Autowired
    protected AccessRequestRepository accessRequestRepository;

    @Autowired
    protected AreaAssignedPersonnelRepository assignedPersonnelRepository;

    @Autowired
    protected AreaLevelPresetRepository presetRepository;

    @Autowired
    protected AreaService areaService;

    @Autowired
    protected SystemConfigService systemConfigService;

    protected String suffix;
    protected Building building;
    protected Floor floor;

    protected User admin;
    protected User fm;
    protected User fm2;
    protected User fmInactive;
    protected User guard;
    protected User userL1;
    protected User userL2;
    protected User userL3;
    protected User userL3b;

    @BeforeEach
    void setUpStep5bBase() {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        building = buildingRepository.findByCodeIgnoreCase("TEST_BLD_5B")
                .orElseGet(() -> buildingRepository.save(Building.builder()
                        .name("Tòa nhà Test 5b")
                        .code("TEST_BLD_5B")
                        .isActive(true)
                        .build()));

        // Mỗi test một tầng riêng: tránh hình học chồng lấn và trùng tên giữa các lần chạy
        floor = floorRepository.save(Floor.builder()
                .name("Tầng test 5b " + suffix)
                .floorCode("F5B-" + suffix)
                .floorOrder(1)
                .building(building)
                .isActive(true)
                .build());

        admin = newUser("adm", Role.ADMIN, 3, true);
        fm = newUser("fma", Role.FACILITY_MANAGER, 3, true);
        fm2 = newUser("fmb", Role.FACILITY_MANAGER, 3, true);
        fmInactive = newUser("fmx", Role.FACILITY_MANAGER, 3, false);
        guard = newUser("grd", Role.GUARD, 2, true);
        userL1 = newUser("l1", Role.NORMAL_USER, 1, true);
        userL2 = newUser("l2", Role.NORMAL_USER, 2, true);
        userL3 = newUser("l3", Role.NORMAL_USER, 3, true);
        userL3b = newUser("l3b", Role.NORMAL_USER, 3, true);

        systemConfigService.update(ConfigKey.EVENT_MODE_MIN_MINUTES.name(), "30", admin.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_MAX_HOURS.name(), "12", admin.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_WINDOW_DAYS.name(), "7", admin.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_BUDGET_HOURS.name(), "48", admin.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_SCHEDULE_MAX_LEAD_DAYS.name(), "30", admin.getEmail());
        systemConfigService.update(ConfigKey.EVENT_MODE_MAX_SCHEDULES_PER_AREA.name(), "5", admin.getEmail());
        systemConfigService.update(ConfigKey.ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE.name(), "false", admin.getEmail());
    }

    // ------------------------------------------------------------------ dữ liệu

    protected User newUser(String tag, Role role, int accessLevel, boolean active) {
        return userRepository.save(User.builder()
                .email("t5b." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("T5B-" + tag.toUpperCase() + "-" + suffix)
                .fullName("Test 5b " + tag + " " + suffix)
                .role(role)
                .accessLevel(accessLevel)
                .isActive(active)
                .build());
    }

    protected Area newArea(AreaLevel level, int accessLevel, boolean explicit) {
        return areaRepository.save(Area.builder()
                .name("Khu 5b " + level.name().toLowerCase().substring(0, 6) + " " + UUID.randomUUID().toString().substring(0, 6))
                .areaLevel(level)
                .areaAccessLevel(accessLevel)
                .explicitAuthorizationRequired(explicit)
                .floorEntity(floor)
                .building(building.getCode())
                .floor(floor.getFloorCode())
                .isActive(true)
                .openToMembers(false)
                .build());
    }

    protected Area reload(Area area) {
        return areaRepository.findById(area.getId()).orElseThrow();
    }

    /** Mở sự kiện trực tiếp qua JPA: cờ trên areas + 1 phiên đang mở. */
    protected AreaEventSession openEvent(Area area, OffsetDateTime startedAt, OffsetDateTime until) {
        Area a = reload(area);
        a.setOpenToMembers(true);
        a.setOpenUntil(until);
        areaRepository.save(a);
        return sessionRepository.save(AreaEventSession.builder()
                .area(a)
                .startedAt(startedAt)
                .plannedEnd(until)
                .actualEnd(null)
                .startedBy(fm)
                .createdAt(startedAt)
                .build());
    }

    protected AreaEventSchedule newSchedule(Area area, OffsetDateTime start, OffsetDateTime end,
                                            String reasonCode, String reasonLabel) {
        return eventScheduleRepository.save(AreaEventSchedule.builder()
                .area(area)
                .startAt(start)
                .endAt(end)
                .status(AreaEventScheduleStatus.SCHEDULED)
                .reasonCode(reasonCode)
                .reasonLabel(reasonLabel)
                .note("Lịch sự kiện dữ liệu test 5b")
                .createdBy(fm)
                .build());
    }

    protected AreaAssignedPersonnel newActiveAp(Area area, User user) {
        return assignedPersonnelRepository.save(AreaAssignedPersonnel.builder()
                .area(area)
                .user(user)
                .validFrom(OffsetDateTime.now().minusDays(1))
                .validTo(OffsetDateTime.now().plusDays(10))
                .note("AP test 5b")
                .createdBy(fm)
                .build());
    }

    protected AccessRequest newRequest(Area area, User requester, RequestType type, RequestStatus status,
                                       OffsetDateTime start, OffsetDateTime end, User... members) {
        AccessRequest req = AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(type)
                .purpose("Đơn test 5b " + suffix)
                .startTime(start)
                .endTime(end)
                .status(status)
                .reviewer(status == RequestStatus.PENDING ? null : fm)
                .reviewedAt(status == RequestStatus.PENDING ? null : OffsetDateTime.now().minusHours(1))
                .build();
        for (User m : members) {
            req.getMembers().add(AccessRequestMember.builder().accessRequest(req).user(m).build());
        }
        return accessRequestRepository.save(req);
    }

    protected RequestStatus requestStatus(AccessRequest req) {
        return accessRequestRepository.findById(req.getId()).orElseThrow().getStatus();
    }

    /** Cột huỷ đơn mới của V56 (BR-TC-15) — đọc bằng SQL vì entity chưa có field. */
    protected Map<String, Object> cancelColumns(AccessRequest req) {
        return jdbcTemplate.queryForMap(
                "SELECT status, cancel_source, cancelled_by, cancel_reason FROM access_requests WHERE id = ?",
                req.getId());
    }

    /** Cột areas.version của V56 (BR-TC-13) — đọc bằng SQL vì entity chưa có field. */
    protected Long dbVersion(Area area) {
        return jdbcTemplate.queryForObject("SELECT version FROM areas WHERE id = ?", Long.class, area.getId());
    }

    protected ReasonCatalog newReason(String actionType, String code, String label) {
        return reasonCatalogRepository.save(ReasonCatalog.builder()
                .actionType(actionType)
                .code(code)
                .label(label)
                .sortOrder(500)
                .isActive(true)
                .isOther(false)
                .build());
    }

    protected AreaGeometry square(double x, double y, double size) {
        return AreaGeometry.builder()
                .type("polygon")
                .version(1)
                .vertices(List.of(
                        new AreaGeometry.Vertex(BigDecimal.valueOf(x), BigDecimal.valueOf(y)),
                        new AreaGeometry.Vertex(BigDecimal.valueOf(x + size), BigDecimal.valueOf(y)),
                        new AreaGeometry.Vertex(BigDecimal.valueOf(x + size), BigDecimal.valueOf(y + size)),
                        new AreaGeometry.Vertex(BigDecimal.valueOf(x), BigDecimal.valueOf(y + size))
                ))
                .build();
    }

    // ------------------------------------------------------------------ audit + thông báo

    protected List<AuditLog> auditsForTarget(String targetId) {
        return auditLogRepository.findAll((root, q, cb) -> cb.equal(root.get("targetId"), targetId));
    }

    protected List<AuditLog> auditsForArea(Area area) {
        return auditLogRepository.findAll((root, q, cb) -> cb.equal(root.get("area").get("id"), area.getId()));
    }

    protected long auditCountForArea(Area area) {
        return auditLogRepository.count((root, q, cb) -> cb.equal(root.get("area").get("id"), area.getId()));
    }

    protected List<AuditLog> auditsWithAction(Area area, String actionName) {
        return auditsForArea(area).stream()
                .filter(a -> a.getAction() != null && a.getAction().name().equals(actionName))
                .toList();
    }

    protected List<Notification> notificationsOf(User user) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(user.getId(), Pageable.unpaged()).getContent();
    }

    protected List<Notification> notificationsOf(User user, NotificationType type) {
        return notificationsOf(user).stream().filter(n -> n.getType() == type).toList();
    }

    protected long countNotifications(User... users) {
        long total = 0;
        for (User u : users) {
            total += notificationsOf(u).size();
        }
        return total;
    }

    // ------------------------------------------------------------------ HTTP

    protected String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateToken(user);
    }

    protected ResultActions send(MockHttpServletRequestBuilder builder, User actor, Object body) throws Exception {
        builder.header("Authorization", bearer(actor));
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(builder);
    }

    protected JsonNode json(MvcResult result) throws Exception {
        String content = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return content.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(content);
    }

    protected String errorCode(MvcResult result) throws Exception {
        return json(result).path("code").asText(null);
    }

    protected String message(MvcResult result) throws Exception {
        return json(result).path("message").asText("");
    }

    /** version theo góc nhìn client: GET /api/areas/{id} → data.version (null nếu chưa có). */
    protected Long apiVersion(Area area) throws Exception {
        MvcResult r = send(get("/api/areas/{id}", area.getId()), admin, null).andReturn();
        JsonNode v = json(r).path("data").path("version");
        return (v.isMissingNode() || v.isNull()) ? null : v.asLong();
    }

    protected Long responseVersion(MvcResult result) throws Exception {
        JsonNode v = json(result).path("data").path("version");
        return (v.isMissingNode() || v.isNull()) ? null : v.asLong();
    }

    protected Map<String, Object> putBody(Area area, String name, AreaLevel level, String reason, Long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("areaLevel", level.name());
        body.put("building", area.getBuilding());
        body.put("floor", area.getFloor());
        body.put("floorId", floor.getId());
        if (reason != null) body.put("reason", reason);
        if (version != null) body.put("version", version);
        return body;
    }

    protected MvcResult putArea(User actor, Area area, String name, AreaLevel level, String reason, Long version) throws Exception {
        return send(put("/api/areas/{id}", area.getId()), actor, putBody(area, name, level, reason, version)).andReturn();
    }

    protected MvcResult changeType(User actor, Area area, AreaLevel level, String reason, Long version) throws Exception {
        return putArea(actor, area, area.getName(), level, reason, version);
    }

    protected MvcResult patchAccessRules(User actor, Area area, int level, boolean explicit, Long version) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("areaAccessLevel", level);
        body.put("explicitAuthorizationRequired", explicit);
        body.put("reason", ACCESS_RULES_REASON);
        if (version != null) body.put("version", version);
        return send(patch("/api/areas/{id}/access-rules", area.getId()), actor, body).andReturn();
    }

    protected Map<String, Object> eventBody(String action, OffsetDateTime openUntil, String reasonCode, String note, Long version) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (action != null) body.put("action", action);
        if (openUntil != null) body.put("openUntil", openUntil.toString());
        body.put("reasonCode", reasonCode);
        body.put("note", note);
        if (version != null) body.put("version", version);
        return body;
    }

    protected MvcResult patchEventMode(User actor, Area area, Map<String, Object> body) throws Exception {
        return send(patch("/api/areas/{id}/event-mode", area.getId()), actor, body).andReturn();
    }

    protected MvcResult saveGeometry(User actor, Area area, AreaGeometry geometry, Long version) throws Exception {
        MockHttpServletRequestBuilder b = patch("/api/areas/{id}/geometry", area.getId());
        if (version != null) b.param("version", String.valueOf(version));
        return send(b, actor, geometry).andReturn();
    }

    protected MvcResult deleteGeometry(User actor, Area area, Long version) throws Exception {
        MockHttpServletRequestBuilder b = delete("/api/areas/{id}/geometry", area.getId());
        if (version != null) b.param("version", String.valueOf(version));
        return send(b, actor, null).andReturn();
    }

    protected int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    protected String describe(MvcResult result) throws Exception {
        return "HTTP " + status(result) + " " + result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }
}
