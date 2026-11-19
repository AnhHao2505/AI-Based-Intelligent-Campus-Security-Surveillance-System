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
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.IncidentDispatchStage;
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
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.scheduler.IncidentDispatchJob;
import com.fa26se040.icss.scheduler.IncidentEscalationJob;
import com.fa26se040.icss.scheduler.IncidentEscalationJob.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2 (round 2, DEF-16): mọi bảo vệ đang trực đều đã đăng xuất app mobile -> không ai nhận được cảnh báo
 * ({@code /topic/guards/{id}/alerts} chỉ app mobile subscribe). Bản ghi leo thang phải có guard_recipient_count = 0 để
 * job leo thang báo FM ngay (BR-IE-05, {@code IncidentEscalationJob} nhánh noGuardNotified), thay vì chờ đủ số phút.
 *
 * <p>Dựng như {@link IncidentDispatchIntegrationTest}: toà riêng, DISPATCH_ENABLED bật rồi khôi phục, phiên STOMP giả qua
 * reflection. Số bảo vệ đang trực là toàn cục; test chỉ đúng khi không bảo vệ đang trực nào khác trong DB test có phiên
 * mobile (phiên chỉ tồn tại trong bộ nhớ, các test khác tự đóng ở @AfterEach) — kiểm ở đầu mỗi test.</p>
 */
class IncidentEscalationLoggedOutGuardsIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String EVENT_TYPE_HIGH = "UNAUTHORIZED_ACCESS"; // V90: UNAUTHORIZED_ACCESS + CONFIDENTIAL -> HIGH

    @Autowired private SecurityIncidentService incidentService;
    @Autowired private IncidentDispatchJob dispatchJob;
    @Autowired private IncidentEscalationJob escalationJob;
    @Autowired private IncidentRecipientResolver recipientResolver;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private WebSocketSessionRegistry sessionRegistry;
    @Autowired private SecurityIncidentRepository incidentRepository;
    @Autowired private IncidentDispatchOfferRepository offerRepository;
    @Autowired private GuardShiftRepository guardShiftRepository;
    @Autowired private CameraRepository cameraRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private String suffix;
    private Building building;
    private Floor floor;
    private Area area;
    private Camera camera;
    private String originalDispatchEnabled;
    private Object originalAiServiceUrl;
    private final List<UUID> userIds = new ArrayList<>();
    private final List<String> sessionIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        building = buildingRepository.save(Building.builder().name("Tòa G2 " + suffix).build());
        floor = floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build());
        area = areaRepository.save(Area.builder()
                .name("Phòng G2 " + suffix)
                .building("TOA_G2_" + suffix.toUpperCase())
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .version(0L)
                .build());
        camera = cameraRepository.save(Camera.builder()
                .cameraCode("CAM-G2-" + suffix)
                .name("Camera G2 " + suffix)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build());

        originalDispatchEnabled = jdbc.queryForObject(
                "SELECT config_value FROM system_configurations WHERE config_key = ?", String.class,
                ConfigKey.DISPATCH_ENABLED.getKey());
        setDispatchEnabled("true");

        Object target = AopTestUtils.getUltimateTargetObject(incidentService);
        originalAiServiceUrl = ReflectionTestUtils.getField(target, "aiServiceUrl");
        ReflectionTestUtils.setField(target, "aiServiceUrl", "");

        saveUser("FMG2", Role.FACILITY_MANAGER); // có FM active -> job leo thang báo được (không rơi vào WAITING_FOR_FM)
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
        buildingRepository.deleteById(building.getId());
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

    /** Bảo vệ có ca CHECKED_IN đang diễn ra ở toà của test. */
    private User onDutyGuard(String prefix) {
        User guard = saveUser(prefix, Role.GUARD);
        LocalDateTime now = LocalDateTime.now(VN_ZONE).truncatedTo(ChronoUnit.MINUTES);
        LocalDateTime start = now.minusHours(1);
        LocalDateTime end = now.plusHours(2);
        guardShiftRepository.save(GuardShift.builder()
                .guard(guard)
                .shiftDate(start.toLocalDate())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(start.toLocalTime())
                .endTime(end.toLocalTime())
                .status(ShiftStatus.CHECKED_IN)
                .building(building)
                .dutyPostCode("PATROL")
                .build());
        return guard;
    }

    private String connect(User guard, String clientType) {
        String sessionId = "g2-" + suffix + "-" + guard.getUserCode() + "-" + clientType + "-" + sessionIds.size();
        ReflectionTestUtils.invokeMethod(sessionRegistry, "userConnected", sessionId, guard.getId(), clientType);
        sessionIds.add(sessionId);
        return sessionId;
    }

    private void disconnect(String sessionId) {
        ReflectionTestUtils.invokeMethod(sessionRegistry, "sessionClosed", sessionId);
    }

    /** Tiền điều kiện: không bảo vệ đang trực nào (trong cả DB test) còn phiên mobile. */
    private void assertNoOnDutyGuardHasMobileSession() {
        List<String> withMobile = recipientResolver.findOnDutyGuards(OffsetDateTime.now()).stream()
                .filter(g -> sessionRegistry.hasMobileSession(g.getId()))
                .map(User::getUserCode)
                .collect(Collectors.toList());
        assertTrue(withMobile.isEmpty(), "tiền điều kiện: còn bảo vệ đang trực có phiên mobile " + withMobile);
    }

    private IncidentDetailResponse ingestHigh() {
        return incidentService.ingestIncident(IncidentEventDto.builder()
                .eventId("G2-" + suffix + "-" + UUID.randomUUID())
                .cameraCode(camera.getCameraCode())
                .eventType(EVENT_TYPE_HIGH)
                .details("Kiểm thử G2")
                .build());
    }

    private int guardRecipientCount(UUID incidentId) {
        return jdbc.queryForObject("SELECT guard_recipient_count FROM incident_escalations WHERE incident_id = ?",
                Integer.class, incidentId);
    }

    private UUID escalationIdOf(UUID incidentId) {
        return jdbc.queryForObject("SELECT id FROM incident_escalations WHERE incident_id = ?", UUID.class, incidentId);
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

    // ===== Ingest -> BROADCAST ngay =====

    @Test
    @DisplayName("G2-1: mọi bảo vệ đang trực đã đăng xuất mobile (còn web) -> BROADCAST, guard_recipient_count = 0, báo FM ngay")
    void allOnDutyGuardsLoggedOut_ingestBroadcast_escalatesToFmImmediately() {
        User g1 = onDutyGuard("G2A");
        User g2 = onDutyGuard("G2B");
        disconnect(connect(g1, WebSocketSessionRegistry.CLIENT_TYPE_MOBILE)); // đăng xuất app
        connect(g2, WebSocketSessionRegistry.CLIENT_TYPE_WEB);                 // chỉ còn web
        assertNoOnDutyGuardHasMobileSession();
        assertTrue(recipientResolver.findOnDutyGuards(OffsetDateTime.now()).size() >= 2);

        IncidentDetailResponse response = ingestHigh();

        assertEquals(IncidentDispatchStage.BROADCAST, incidentRepository.findById(response.getId()).orElseThrow().getDispatchStage());
        assertEquals(0, guardRecipientCount(response.getId()), "không bảo vệ nào có phiên mobile nhận được cảnh báo");
        Outcome outcome = escalationJob.processEscalation(escalationIdOf(response.getId()), OffsetDateTime.now());
        assertEquals(Outcome.ESCALATED, outcome, "BR-IE-05: không bảo vệ nào nhận -> báo FM ngay, không chờ đủ số phút");
    }

    // ===== STEP1 -> BROADCAST qua job =====

    @Test
    @DisplayName("G2-2: người được mời đăng xuất mobile, lời mời bị huỷ -> BROADCAST với guard_recipient_count = 0, báo FM ngay")
    void invitedGuardLogsOut_promotedToBroadcast_escalatesToFmImmediately() {
        User guard = onDutyGuard("G2C");
        String mobile = connect(guard, WebSocketSessionRegistry.CLIENT_TYPE_MOBILE);
        IncidentDetailResponse response = ingestHigh();
        assertEquals(IncidentDispatchStage.STEP1, incidentRepository.findById(response.getId()).orElseThrow().getDispatchStage());
        assertFalse(offersOf(response.getId()).isEmpty());

        disconnect(mobile); // đăng xuất app khi đang có lời mời
        assertNoOnDutyGuardHasMobileSession();
        OffsetDateTime runAt = offersOf(response.getId()).stream()
                .map(IncidentDispatchOffer::getExpiresAt).max(Comparator.naturalOrder()).orElseThrow().plusSeconds(1);
        dispatchJob.runOnce(runAt);

        assertEquals(IncidentDispatchStage.BROADCAST, incidentRepository.findById(response.getId()).orElseThrow().getDispatchStage());
        assertEquals(0, guardRecipientCount(response.getId()), "không bảo vệ nào có phiên mobile nhận được cảnh báo");
        Outcome outcome = escalationJob.processEscalation(escalationIdOf(response.getId()), runAt);
        assertEquals(Outcome.ESCALATED, outcome, "BR-IE-05: không bảo vệ nào nhận -> báo FM ngay, không chờ đủ số phút");
    }
}
