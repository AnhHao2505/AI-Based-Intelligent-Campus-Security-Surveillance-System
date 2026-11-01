package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.config.WebSocketSessionRegistry;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.IncidentDispatchOffer;
import com.fa26se040.icss.entity.IncidentDispatchPolicy;
import com.fa26se040.icss.entity.IncidentEscalation;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.DispatchOfferCancelReason;
import com.fa26se040.icss.enums.DispatchOfferResponse;
import com.fa26se040.icss.enums.IncidentDispatchStage;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.IncidentDispatchOfferRepository;
import com.fa26se040.icss.repository.IncidentDispatchPolicyRepository;
import com.fa26se040.icss.repository.IncidentEscalationRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.scheduler.IncidentDispatchJob;
import com.fa26se040.icss.scheduler.IncidentEscalationJob;
import com.fa26se040.icss.scheduler.IncidentEscalationJob.Outcome;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Phân phối sự cố theo đợt trên DB test thật (V93, K3–K6, BR-DSP-01..10), 11 case a–k.
 *
 * <p>Mỗi test dựng một toà riêng (tên có hậu tố ngẫu nhiên) nên ứng viên bước 1 chỉ là bảo vệ do test tạo; số bảo vệ đang
 * trực (broadcast) là toàn cục nên so với {@link IncidentRecipientResolver#findOnDutyGuards} thay vì một con số cố định.
 * Chính sách theo mức đọc từ bảng incident_dispatch_policies, không viết cứng. DISPATCH_ENABLED bật / tắt qua bảng
 * system_configurations + {@link SystemConfigService#reloadCache()}, khôi phục giá trị cũ sau mỗi test. Phiên mobile giả
 * bằng {@code WebSocketSessionRegistry.userConnected(sessionId, guardId, "mobile")} (package-private, gọi qua reflection).
 * Job leo thang gọi theo id ({@code processEscalation}) thay vì runOnce để không đụng bản ghi leo thang của dữ liệu khác.
 * Tắt gọi ghi hình sang AI service (aiServiceUrl rỗng).</p>
 */
class IncidentDispatchIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String EVENT_TYPE_HIGH = "UNAUTHORIZED_ACCESS"; // V90: UNAUTHORIZED_ACCESS + CONFIDENTIAL + ANY -> HIGH

    @Autowired private SecurityIncidentService incidentService;
    @Autowired private IncidentDispatchJob dispatchJob;
    @Autowired private IncidentEscalationJob escalationJob;
    @Autowired private IncidentRecipientResolver recipientResolver;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private WebSocketSessionRegistry sessionRegistry;
    @Autowired private SecurityIncidentRepository incidentRepository;
    @Autowired private IncidentEscalationRepository escalationRepository;
    @Autowired private IncidentDispatchOfferRepository offerRepository;
    @Autowired private IncidentDispatchPolicyRepository policyRepository;
    @Autowired private GuardShiftRepository guardShiftRepository;
    @Autowired private CameraRepository cameraRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String suffix;
    private Building building;
    private Building otherBuilding;
    private Floor floor;
    private Floor otherFloor;
    private Area area;
    private Camera camera;
    private String originalDispatchEnabled;
    private Object originalAiServiceUrl;
    private final List<UUID> userIds = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        building = buildingRepository.save(Building.builder().name("Tòa DSP " + suffix).build());
        otherBuilding = buildingRepository.save(Building.builder().name("Tòa DSP khác " + suffix).build());
        floor = floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build());
        otherFloor = floorRepository.save(Floor.builder().building(otherBuilding).name("Tầng 1").floorOrder(1).build());
        area = areaRepository.save(Area.builder()
                .name("Phòng phân phối " + suffix)
                .building("TOA_DSP_" + suffix.toUpperCase())
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .version(0L)
                .build());
        camera = cameraRepository.save(Camera.builder()
                .cameraCode("CAM-DSP-" + suffix)
                .name("Camera phân phối " + suffix)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build());

        originalDispatchEnabled = jdbc.queryForObject(
                "SELECT config_value FROM system_configurations WHERE config_key = ?", String.class,
                ConfigKey.DISPATCH_ENABLED.getKey());

        Object target = AopTestUtils.getUltimateTargetObject(incidentService);
        originalAiServiceUrl = ReflectionTestUtils.getField(target, "aiServiceUrl");
        ReflectionTestUtils.setField(target, "aiServiceUrl", "");
    }

    @AfterEach
    void cleanUp() {
        Object target = AopTestUtils.getUltimateTargetObject(incidentService);
        ReflectionTestUtils.setField(target, "aiServiceUrl", originalAiServiceUrl);
        setDispatchEnabled(originalDispatchEnabled);
        for (String sessionId : sessionIds) {
            ReflectionTestUtils.invokeMethod(sessionRegistry, "sessionClosed", sessionId);
        }
        sessionIds.clear();

        List<UUID> incidentIds = jdbc.queryForList(
                "SELECT id FROM security_incidents WHERE area_id = ?", UUID.class, area.getId());
        for (UUID incidentId : incidentIds) {
            jdbc.update("DELETE FROM notifications WHERE reference_id = ?", incidentId);
            jdbc.update("DELETE FROM incident_dispatch_offers WHERE incident_id = ?", incidentId);
        }
        for (UUID userId : userIds) {
            jdbc.update("DELETE FROM notifications WHERE recipient_id = ?", userId);
            jdbc.update("DELETE FROM incident_dispatch_offers WHERE guard_id = ?", userId);
            jdbc.update("DELETE FROM guard_shifts WHERE guard_id = ?", userId);
        }
        // Bản ghi leo thang xoá theo ON DELETE CASCADE (V80)
        jdbc.update("DELETE FROM security_incidents WHERE area_id = ?", area.getId());
        jdbc.update("DELETE FROM camera_access_logs WHERE camera_code = ?", camera.getCameraCode());
        cameraRepository.deleteById(camera.getId());
        areaRepository.deleteById(area.getId());
        floorRepository.deleteById(floor.getId());
        floorRepository.deleteById(otherFloor.getId());
        buildingRepository.deleteById(building.getId());
        buildingRepository.deleteById(otherBuilding.getId());
        for (UUID userId : userIds) {
            userRepository.deleteById(userId);
        }
        userIds.clear();
    }

    // ===== Dữ liệu =====

    private void setDispatchEnabled(String value) {
        jdbc.update("UPDATE system_configurations SET config_value = ? WHERE config_key = ?",
                value, ConfigKey.DISPATCH_ENABLED.getKey());
        systemConfigService.reloadCache();
    }

    private User saveUser(String prefix, Role role) {
        User user = userRepository.save(User.builder()
                .userCode(prefix + "-" + suffix)
                .fullName(prefix + " " + suffix)
                .email(prefix.toLowerCase() + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(2)
                .isActive(true)
                .build());
        userIds.add(user.getId());
        return user;
    }

    /** Bảo vệ có ca CHECKED_IN đang diễn ra (giờ Việt Nam) ở toà {@code shiftBuilding} (null = ca không có toà). */
    private User onDutyGuard(String prefix, Building shiftBuilding, String dutyPostCode, boolean mobile) {
        User guard = saveUser(prefix, Role.GUARD);
        LocalDateTime nowVn = LocalDateTime.now(VN_ZONE);
        LocalDateTime start = nowVn.minusHours(1);
        LocalDateTime end = nowVn.plusHours(2);
        guardShiftRepository.save(GuardShift.builder()
                .guard(guard)
                .shiftDate(start.toLocalDate())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(start.toLocalTime())
                .endTime(end.toLocalTime())
                .status(ShiftStatus.CHECKED_IN)
                .building(shiftBuilding)
                .dutyPostCode(dutyPostCode)
                .build());
        if (mobile) {
            String sessionId = "dsp-" + suffix + "-" + prefix;
            ReflectionTestUtils.invokeMethod(sessionRegistry, "userConnected", sessionId, guard.getId(), "mobile");
            sessionIds.add(sessionId);
        }
        return guard;
    }

    private User patrolGuard(String prefix) {
        return onDutyGuard(prefix, building, "PATROL", true);
    }

    private IncidentEventDto event(String eventType, String eventId) {
        return IncidentEventDto.builder()
                .eventId(eventId)
                .cameraCode(camera.getCameraCode())
                .eventType(eventType)
                .details("Kiểm thử phân phối")
                .build();
    }

    private IncidentDetailResponse ingestHigh() {
        return incidentService.ingestIncident(event(EVENT_TYPE_HIGH, "DSP-" + suffix + "-" + UUID.randomUUID()));
    }

    private IncidentDispatchPolicy policy(IncidentSeverity severity) {
        return policyRepository.findById(severity).orElseThrow();
    }

    private SecurityIncident reload(UUID incidentId) {
        return incidentRepository.findById(incidentId).orElseThrow();
    }

    private List<IncidentDispatchOffer> offersOf(UUID incidentId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            List<IncidentDispatchOffer> offers = offerRepository.findAll().stream()
                    .filter(o -> incidentId.equals(o.getIncident().getId()))
                    .collect(Collectors.toList());
            offers.forEach(o -> o.getGuard().getId());
            return offers;
        });
    }

    private Set<UUID> invitedGuardIds(UUID incidentId) {
        return offersOf(incidentId).stream().map(o -> o.getGuard().getId()).collect(Collectors.toSet());
    }

    private int guardRecipientCount(UUID incidentId) {
        return jdbc.queryForObject("SELECT guard_recipient_count FROM incident_escalations WHERE incident_id = ?",
                Integer.class, incidentId);
    }

    private UUID escalationIdOf(UUID incidentId) {
        return jdbc.queryForObject("SELECT id FROM incident_escalations WHERE incident_id = ?", UUID.class, incidentId);
    }

    private MvcResult postAs(User actor, String url) throws Exception {
        return mockMvc.perform(post(url).header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor)))
                .andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ===== a. Tắt dispatch =====

    @Test
    @DisplayName("a. DISPATCH_ENABLED=false -> BROADCAST, không có lời mời, guard_recipient_count = số bảo vệ đang trực như cũ")
    void a_dispatchDisabled_broadcastsAsBefore() {
        setDispatchEnabled("false");
        patrolGuard("GA1");
        patrolGuard("GA2");
        patrolGuard("GA3");

        int onDutyBefore = recipientResolver.findOnDutyGuards(OffsetDateTime.now()).size();
        IncidentDetailResponse response = ingestHigh();
        int onDutyAfter = recipientResolver.findOnDutyGuards(OffsetDateTime.now()).size();

        SecurityIncident incident = reload(response.getId());
        assertEquals(IncidentDispatchStage.BROADCAST, incident.getDispatchStage());
        assertEquals(IncidentDispatchStage.BROADCAST, response.getDispatchStage());
        assertEquals(IncidentSeverity.HIGH, incident.getSeverity());
        assertTrue(offersOf(response.getId()).isEmpty());
        int count = guardRecipientCount(response.getId());
        assertTrue(count >= Math.min(onDutyBefore, onDutyAfter) && count <= Math.max(onDutyBefore, onDutyAfter),
                "guard_recipient_count=" + count + " onDuty=" + onDutyBefore + "/" + onDutyAfter);
        assertTrue(count >= 3, "3 bảo vệ do test tạo phải nằm trong số được gửi, count=" + count);
    }

    // ===== b. Bước 1 =====

    @Test
    @DisplayName("b. Bật: sự cố HIGH -> STEP1, đúng step1_size lời mời cho người tải thấp, guard_recipient_count = số người được mời")
    void b_highIncident_step1InvitesLowestLoad() {
        setDispatchEnabled("true");
        IncidentDispatchPolicy high = policy(IncidentSeverity.HIGH);
        int step1Size = high.getStep1Size();
        assertTrue(step1Size >= 1, "chính sách HIGH phải có step1_size >= 1 để test bước 1, đang là " + step1Size);

        User low1 = patrolGuard("GB1");
        User low2 = patrolGuard("GB2");
        User busy = patrolGuard("GB3");
        // busy đã nhận 1 sự cố (đã xử lý xong) trong cửa sổ tải -> điểm tải cao nhất, xếp cuối
        incidentRepository.save(SecurityIncident.builder()
                .cameraCode(camera.getCameraCode())
                .area(area)
                .building(area.getBuilding())
                .eventType(EVENT_TYPE_HIGH)
                .detectedAt(OffsetDateTime.now().minusMinutes(10))
                .status(IncidentStatus.RESOLVED_VERIFIED)
                .claimedBy(busy)
                .claimedAt(OffsetDateTime.now().minusMinutes(10))
                .version(0)
                .build());

        IncidentDetailResponse response = ingestHigh();

        SecurityIncident incident = reload(response.getId());
        assertEquals(IncidentDispatchStage.STEP1, incident.getDispatchStage());
        assertEquals(IncidentDispatchStage.STEP1, response.getDispatchStage());
        assertNull(incident.getBroadcastAt());

        List<IncidentDispatchOffer> offers = offersOf(response.getId());
        int expected = Math.min(step1Size, 3);
        assertEquals(expected, offers.size());
        Set<UUID> invited = invitedGuardIds(response.getId());
        if (step1Size < 3) {
            assertFalse(invited.contains(busy.getId()), "người tải cao không được mời khi còn người tải thấp");
        }
        if (step1Size >= 2) {
            assertTrue(invited.containsAll(List.of(low1.getId(), low2.getId())));
        }
        for (IncidentDispatchOffer offer : offers) {
            assertNull(offer.getResponse());
            assertEquals(high.getStep1TimeoutSeconds().longValue(),
                    java.time.Duration.between(offer.getOfferedAt(), offer.getExpiresAt()).getSeconds());
        }
        assertEquals(offers.size(), guardRecipientCount(response.getId()));
    }

    // ===== c. Hết hạn =====

    @Test
    @DisplayName("c. STEP1 hết hạn -> job: lời mời EXPIRED, sự cố BROADCAST, broadcast_at có, guard_recipient_count = số bảo vệ đang trực")
    void c_step1Expires_jobPromotesToBroadcast() {
        setDispatchEnabled("true");
        patrolGuard("GC1");
        patrolGuard("GC2");
        patrolGuard("GC3");
        IncidentDetailResponse response = ingestHigh();
        assertEquals(IncidentDispatchStage.STEP1, reload(response.getId()).getDispatchStage());

        OffsetDateTime expiresAt = offersOf(response.getId()).stream()
                .map(IncidentDispatchOffer::getExpiresAt).max(Comparator.naturalOrder()).orElseThrow();
        OffsetDateTime runAt = expiresAt.plusSeconds(1);
        int promoted = dispatchJob.runOnce(runAt);

        assertTrue(promoted >= 1);
        assertTrue(offersOf(response.getId()).stream().allMatch(o -> o.getResponse() == DispatchOfferResponse.EXPIRED));
        SecurityIncident incident = reload(response.getId());
        assertEquals(IncidentDispatchStage.BROADCAST, incident.getDispatchStage());
        assertNotNull(incident.getBroadcastAt());
        assertEquals(recipientResolver.findOnDutyGuards(runAt).size(), guardRecipientCount(response.getId()));
    }

    // ===== d. Mọi người từ chối =====

    @Test
    @DisplayName("d. Mọi người được mời từ chối (qua API) -> BROADCAST ngay, không cần job")
    void d_allInvitedDecline_broadcastImmediately() throws Exception {
        setDispatchEnabled("true");
        List<User> guards = List.of(patrolGuard("GD1"), patrolGuard("GD2"), patrolGuard("GD3"));
        IncidentDetailResponse response = ingestHigh();
        Set<UUID> invited = invitedGuardIds(response.getId());
        assertFalse(invited.isEmpty());

        List<User> invitedGuards = guards.stream().filter(g -> invited.contains(g.getId())).toList();
        for (int i = 0; i < invitedGuards.size(); i++) {
            MvcResult result = postAs(invitedGuards.get(i), "/api/incidents/" + response.getId() + "/offers/decline");
            assertEquals(200, result.getResponse().getStatus(), result.getResponse().getContentAsString());
            IncidentDispatchStage expected = i < invitedGuards.size() - 1 ? IncidentDispatchStage.STEP1 : IncidentDispatchStage.BROADCAST;
            assertEquals(expected, reload(response.getId()).getDispatchStage(), "sau lần từ chối thứ " + (i + 1));
        }

        assertTrue(offersOf(response.getId()).stream().allMatch(o -> o.getResponse() == DispatchOfferResponse.DECLINED));
        assertNotNull(reload(response.getId()).getBroadcastAt());

        // Từ chối lần nữa -> 409 INCIDENT_OFFER_NOT_PENDING
        MvcResult again = postAs(invitedGuards.get(0), "/api/incidents/" + response.getId() + "/offers/decline");
        assertEquals(409, again.getResponse().getStatus());
        assertEquals("INCIDENT_OFFER_NOT_PENDING", json(again).path("errorCode").asText());
    }

    // ===== e. Leo thang không báo FM ngay =====

    @Test
    @DisplayName("e. guard_recipient_count ở STEP1 khác 0 -> job leo thang ngay sau ingest KHÔNG báo FM")
    void e_step1RecipientCountNonZero_escalationNotDue() {
        setDispatchEnabled("true");
        patrolGuard("GE1");
        patrolGuard("GE2");
        IncidentDetailResponse response = ingestHigh();
        assertTrue(guardRecipientCount(response.getId()) > 0);

        Outcome outcome = escalationJob.processEscalation(escalationIdOf(response.getId()), OffsetDateTime.now());

        assertEquals(Outcome.NOT_DUE, outcome);
        IncidentEscalation escalation = escalationRepository.findById(escalationIdOf(response.getId())).orElseThrow();
        assertNull(escalation.getFmEscalatedAt());
        assertNull(escalation.getClosedAt());
    }

    // ===== f. Leo thang theo mức =====

    private UUID incidentWithEscalation(IncidentSeverity severity) {
        SecurityIncident incident = incidentRepository.save(SecurityIncident.builder()
                .cameraCode(camera.getCameraCode())
                .area(area)
                .building(area.getBuilding())
                .eventType(EVENT_TYPE_HIGH)
                .detectedAt(OffsetDateTime.now())
                .status(IncidentStatus.NEW)
                .severity(severity)
                .version(0)
                .build());
        escalationRepository.save(IncidentEscalation.builder()
                .incident(incident)
                .guardsNotifiedAt(OffsetDateTime.now())
                .guardRecipientCount(1)
                .build());
        return incident.getId();
    }

    /** Hạn leo thang đúng {@code minutes} phút kể từ lúc tạo sự cố: 1 giây trước hạn NOT_DUE, đúng hạn ESCALATED. */
    private void assertEscalatesAfter(UUID incidentId, int minutes) {
        OffsetDateTime createdAt = reload(incidentId).getCreatedAt();
        UUID escalationId = escalationIdOf(incidentId);
        assertEquals(Outcome.NOT_DUE, escalationJob.processEscalation(escalationId, createdAt.plusMinutes(minutes).minusSeconds(1)),
                "1 giây trước hạn " + minutes + " phút");
        assertEquals(Outcome.ESCALATED, escalationJob.processEscalation(escalationId, createdAt.plusMinutes(minutes)),
                "đúng hạn " + minutes + " phút");
    }

    @Test
    @DisplayName("f. Bật: CRITICAL / MEDIUM leo thang theo escalate_fm_minutes của chính sách; tắt: theo INCIDENT_ESCALATE_FM_MINUTES")
    void f_escalationMinutesBySeverity() {
        saveUser("FMDSP", Role.FACILITY_MANAGER);
        int critical = policy(IncidentSeverity.CRITICAL).getEscalateFmMinutes();
        int medium = policy(IncidentSeverity.MEDIUM).getEscalateFmMinutes();

        setDispatchEnabled("true");
        assertEscalatesAfter(incidentWithEscalation(IncidentSeverity.CRITICAL), critical);
        assertEscalatesAfter(incidentWithEscalation(IncidentSeverity.MEDIUM), medium);

        setDispatchEnabled("false");
        int fallback = systemConfigService.getInt(ConfigKey.INCIDENT_ESCALATE_FM_MINUTES);
        assertEscalatesAfter(incidentWithEscalation(IncidentSeverity.CRITICAL), fallback);
        assertEscalatesAfter(incidentWithEscalation(IncidentSeverity.MEDIUM), fallback);
    }

    // ===== g. Claim lúc STEP1 =====

    @Test
    @DisplayName("g. Claim lúc STEP1: không có lời mời -> 403 INCIDENT_NOT_OFFERED; có lời mời -> 200, ACCEPTED, lời mời khác CANCELLED")
    void g_claimDuringStep1() throws Exception {
        setDispatchEnabled("true");
        int step1Size = policy(IncidentSeverity.HIGH).getStep1Size();
        assertTrue(step1Size >= 2, "case g cần step1_size HIGH >= 2, đang là " + step1Size);
        List<User> guards = new ArrayList<>();
        for (int i = 1; i <= step1Size + 1; i++) {
            guards.add(patrolGuard("GG" + i));
        }
        IncidentDetailResponse response = ingestHigh();
        Set<UUID> invited = invitedGuardIds(response.getId());
        User notInvited = guards.stream().filter(g -> !invited.contains(g.getId())).findFirst().orElseThrow();
        User winner = guards.stream().filter(g -> invited.contains(g.getId())).findFirst().orElseThrow();

        MvcResult forbidden = postAs(notInvited, "/api/incidents/" + response.getId() + "/claim");
        assertEquals(403, forbidden.getResponse().getStatus(), forbidden.getResponse().getContentAsString());
        assertEquals("INCIDENT_NOT_OFFERED", json(forbidden).path("errorCode").asText());
        assertEquals(IncidentStatus.NEW, reload(response.getId()).getStatus());

        MvcResult ok = postAs(winner, "/api/incidents/" + response.getId() + "/claim");
        assertEquals(200, ok.getResponse().getStatus(), ok.getResponse().getContentAsString());

        assertEquals(IncidentStatus.CLAIMED, reload(response.getId()).getStatus());
        for (IncidentDispatchOffer offer : offersOf(response.getId())) {
            if (offer.getGuard().getId().equals(winner.getId())) {
                assertEquals(DispatchOfferResponse.ACCEPTED, offer.getResponse());
            } else {
                assertEquals(DispatchOfferResponse.CANCELLED, offer.getResponse());
                assertEquals(DispatchOfferCancelReason.CLAIMED_BY_OTHER, offer.getCancelReason());
            }
        }
    }

    // ===== h. Race 2 luồng claim =====

    @Test
    @DisplayName("h. 2 bảo vệ được mời claim cùng lúc -> đúng 1 thành công, người kia 409 nêu đúng tên người đã nhận")
    void h_concurrentClaims_exactlyOneWins() throws Exception {
        setDispatchEnabled("true");
        assertTrue(policy(IncidentSeverity.HIGH).getStep1Size() >= 2);
        User g1 = patrolGuard("GH1");
        User g2 = patrolGuard("GH2");
        IncidentDetailResponse response = ingestHigh();
        assertEquals(Set.of(g1.getId(), g2.getId()), invitedGuardIds(response.getId()));

        String url = "/api/incidents/" + response.getId() + "/claim";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        MvcResult r1;
        MvcResult r2;
        try {
            Callable<MvcResult> claim1 = () -> {
                start.await();
                return postAs(g1, url);
            };
            Callable<MvcResult> claim2 = () -> {
                start.await();
                return postAs(g2, url);
            };
            Future<MvcResult> f1 = pool.submit(claim1);
            Future<MvcResult> f2 = pool.submit(claim2);
            start.countDown();
            r1 = f1.get(30, TimeUnit.SECONDS);
            r2 = f2.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        List<Integer> statuses = List.of(r1.getResponse().getStatus(), r2.getResponse().getStatus());
        assertEquals(1, statuses.stream().filter(s -> s == 200).count(), "statuses=" + statuses);
        assertEquals(1, statuses.stream().filter(s -> s == 409).count(), "statuses=" + statuses);

        SecurityIncident incident = new TransactionTemplate(transactionManager).execute(status -> {
            SecurityIncident i = reload(response.getId());
            i.getClaimedBy().getFullName();
            return i;
        });
        User winner = r1.getResponse().getStatus() == 200 ? g1 : g2;
        User loser = winner == g1 ? g2 : g1;
        MvcResult loserResult = winner == g1 ? r2 : r1;
        assertEquals(winner.getId(), incident.getClaimedBy().getId());
        String message = json(loserResult).path("message").asText();
        assertTrue(message.contains(winner.getFullName()), "message=" + message);
        assertFalse(message.contains(loser.getFullName()), "message=" + message);

        Map<UUID, DispatchOfferResponse> responses = offersOf(response.getId()).stream()
                .collect(Collectors.toMap(o -> o.getGuard().getId(), IncidentDispatchOffer::getResponse));
        assertEquals(DispatchOfferResponse.ACCEPTED, responses.get(winner.getId()));
        assertEquals(DispatchOfferResponse.CANCELLED, responses.get(loser.getId()));
    }

    // ===== i. Lọc theo toà của ca =====

    @Test
    @DisplayName("i. Ca ở toà khác hoặc ca không có building_id -> không được mời")
    void i_onlyShiftsInIncidentBuildingInvited() {
        setDispatchEnabled("true");
        User sameBuilding = patrolGuard("GI1");
        User otherBuildingGuard = onDutyGuard("GI2", otherBuilding, "PATROL", true);
        User noBuilding = onDutyGuard("GI3", null, "PATROL", true);

        IncidentDetailResponse response = ingestHigh();

        Set<UUID> invited = invitedGuardIds(response.getId());
        assertEquals(Set.of(sameBuilding.getId()), invited);
        assertFalse(invited.contains(otherBuildingGuard.getId()));
        assertFalse(invited.contains(noBuilding.getId()));
    }

    // ===== j. Trùng event_id =====

    @Test
    @DisplayName("j. Sự cố trùng event_id gửi 2 lần -> không tạo lời mời lần 2")
    void j_duplicateEventId_noSecondOffers() {
        setDispatchEnabled("true");
        patrolGuard("GJ1");
        patrolGuard("GJ2");
        patrolGuard("GJ3");
        String eventId = "DSP-" + suffix + "-DUP";

        IncidentDetailResponse first = incidentService.ingestIncident(event(EVENT_TYPE_HIGH, eventId));
        int offersAfterFirst = jdbc.queryForObject("SELECT count(*) FROM incident_dispatch_offers o "
                + "JOIN security_incidents i ON i.id = o.incident_id WHERE i.event_id = ?", Integer.class, eventId);
        IncidentDetailResponse second = incidentService.ingestIncident(event(EVENT_TYPE_HIGH, eventId));
        int offersAfterSecond = jdbc.queryForObject("SELECT count(*) FROM incident_dispatch_offers o "
                + "JOIN security_incidents i ON i.id = o.incident_id WHERE i.event_id = ?", Integer.class, eventId);

        assertEquals(first.getId(), second.getId());
        assertTrue(offersAfterFirst > 0);
        assertEquals(offersAfterFirst, offersAfterSecond);
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM security_incidents WHERE event_id = ?", Integer.class, eventId));
    }

    // ===== k. Phiên mobile và vị trí trực =====

    @Test
    @DisplayName("k1. Không có phiên mobile -> không được mời; trực GATE không được mời khi còn người PATROL")
    void k1_mobileSessionAndDutyPostFilter() {
        setDispatchEnabled("true");
        User noMobile = onDutyGuard("GK1", building, "PATROL", false);
        User gate = onDutyGuard("GK2", building, "GATE", true);
        User patrol = patrolGuard("GK3");

        IncidentDetailResponse response = ingestHigh();

        Set<UUID> invited = invitedGuardIds(response.getId());
        assertEquals(Set.of(patrol.getId()), invited);
        assertFalse(invited.contains(noMobile.getId()));
        assertFalse(invited.contains(gate.getId()));
    }

    @Test
    @DisplayName("k2. E11: mọi ứng viên đều trực GATE -> vẫn được mời")
    void k2_allAtGate_stillInvited() {
        setDispatchEnabled("true");
        int step1Size = policy(IncidentSeverity.HIGH).getStep1Size();
        User gate1 = onDutyGuard("GK4", building, "GATE", true);
        User gate2 = onDutyGuard("GK5", building, "GATE", true);

        IncidentDetailResponse response = ingestHigh();

        assertEquals(IncidentDispatchStage.STEP1, reload(response.getId()).getDispatchStage());
        Set<UUID> invited = invitedGuardIds(response.getId());
        assertEquals(Math.min(step1Size, 2), invited.size());
        assertTrue(Set.of(gate1.getId(), gate2.getId()).containsAll(invited));
    }
}
