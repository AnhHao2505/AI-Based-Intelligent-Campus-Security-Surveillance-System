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
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.ConfigKey;
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
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Luật chọn bảo vệ được mời ở bước 1 (BR-DSP-02, BR-DSP-13, quyết định 15/11/2026: mời từ giờ bắt đầu ca), chạy trên DB
 * test thật qua {@link SecurityIncidentService#ingestIncident}, mỗi lý do loại một test (a–g).
 *
 * <p>Cách dựng giống {@link IncidentDispatchIntegrationTest}: mỗi test một toà riêng nên ứng viên bước 1 chỉ là bảo vệ do
 * test tạo; chính sách HIGH đọc từ bảng incident_dispatch_policies; DISPATCH_ENABLED bật qua system_configurations rồi
 * khôi phục; phiên STOMP giả bằng {@code WebSocketSessionRegistry.userConnected / sessionClosed} (package-private, gọi qua
 * reflection).</p>
 *
 * <p>Mỗi case loại trừ có một bảo vệ đối chứng đủ điều kiện cùng toà. HIGH cần step1_size &gt;= 2: nếu luật loại trừ hỏng
 * thì bảo vệ bị thử cũng lọt vào lời mời và test đỏ, không phụ thuộc thứ tự xếp hạng.</p>
 */
class IncidentDispatchCandidateRulesIntegrationTest extends AbstractIntegrationTest {

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final String EVENT_TYPE_HIGH = "UNAUTHORIZED_ACCESS"; // V90: UNAUTHORIZED_ACCESS + CONFIDENTIAL -> HIGH

    @Autowired private SecurityIncidentService incidentService;
    @Autowired private IncidentDispatchService dispatchService;
    @Autowired private SystemConfigService systemConfigService;
    @Autowired private WebSocketSessionRegistry sessionRegistry;
    @Autowired private SecurityIncidentRepository incidentRepository;
    @Autowired private IncidentDispatchOfferRepository offerRepository;
    @Autowired private IncidentDispatchPolicyRepository policyRepository;
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
        building = buildingRepository.save(Building.builder().name("Tòa CR " + suffix).build());
        floor = floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build());
        area = areaRepository.save(Area.builder()
                .name("Phòng luật mời " + suffix)
                .building("TOA_CR_" + suffix.toUpperCase())
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .version(0L)
                .build());
        camera = cameraRepository.save(Camera.builder()
                .cameraCode("CAM-CR-" + suffix)
                .name("Camera luật mời " + suffix)
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

        int step1Size = highPolicy().getStep1Size();
        assertTrue(step1Size >= 2, "các case loại trừ cần step1_size HIGH >= 2, đang là " + step1Size);
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

    private IncidentDispatchPolicy highPolicy() {
        return policyRepository.findById(IncidentSeverity.HIGH).orElseThrow();
    }

    private User saveGuard(String prefix) {
        User user = userRepository.save(User.builder()
                .userCode(prefix + "-" + suffix)
                .fullName(prefix + " " + suffix)
                .email(prefix.toLowerCase() + "-" + suffix + "@fpt.edu.vn")
                .role(Role.GUARD)
                .accessLevel(2)
                .isActive(true)
                .build());
        userIds.add(user.getId());
        return user;
    }

    /** Ca CHECKED_IN ở toà của test, vị trí PATROL, khung [start, end) theo giờ Việt Nam. */
    private void checkedInShift(User guard, LocalDateTime start, LocalDateTime end) {
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
    }

    private static LocalDateTime nowVn() {
        return LocalDateTime.now(VN_ZONE).truncatedTo(ChronoUnit.MINUTES);
    }

    private String connect(User guard, String clientType) {
        String sessionId = "cr-" + suffix + "-" + guard.getUserCode() + "-" + clientType + "-" + sessionIds.size();
        ReflectionTestUtils.invokeMethod(sessionRegistry, "userConnected", sessionId, guard.getId(), clientType);
        sessionIds.add(sessionId);
        return sessionId;
    }

    private void disconnect(String sessionId) {
        ReflectionTestUtils.invokeMethod(sessionRegistry, "sessionClosed", sessionId);
    }

    /** Bảo vệ đủ mọi điều kiện: ca CHECKED_IN đang diễn ra ở toà của sự cố + phiên mobile. */
    private User eligibleGuard(String prefix) {
        User guard = saveGuard(prefix);
        LocalDateTime now = nowVn();
        checkedInShift(guard, now.minusHours(1), now.plusHours(2));
        connect(guard, WebSocketSessionRegistry.CLIENT_TYPE_MOBILE);
        return guard;
    }

    private IncidentDetailResponse ingestHigh() {
        return incidentService.ingestIncident(IncidentEventDto.builder()
                .eventId("CR-" + suffix + "-" + UUID.randomUUID())
                .cameraCode(camera.getCameraCode())
                .eventType(EVENT_TYPE_HIGH)
                .details("Kiểm thử luật mời bước 1")
                .build());
    }

    /** Sự cố khác ở cùng khu (để xoá theo area_id khi dọn). */
    private SecurityIncident otherIncident(IncidentStatus status, User claimedBy) {
        return incidentRepository.save(SecurityIncident.builder()
                .cameraCode(camera.getCameraCode())
                .area(area)
                .building(area.getBuilding())
                .eventType(EVENT_TYPE_HIGH)
                .detectedAt(OffsetDateTime.now().minusMinutes(5))
                .status(status)
                .claimedBy(claimedBy)
                .claimedAt(claimedBy != null ? OffsetDateTime.now().minusMinutes(5) : null)
                .version(0)
                .build());
    }

    private IncidentDispatchOffer pendingOffer(SecurityIncident incident, User guard, OffsetDateTime expiresAt) {
        return offerRepository.save(IncidentDispatchOffer.builder()
                .incident(incident)
                .guard(guard)
                .offeredAt(expiresAt.minusSeconds(highPolicy().getStep1TimeoutSeconds()))
                .expiresAt(expiresAt)
                .build());
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

    private DispatchOfferResponse responseOf(UUID offerId) {
        return offerRepository.findById(offerId).orElseThrow().getResponse();
    }

    private int guardRecipientCount(UUID incidentId) {
        return jdbc.queryForObject("SELECT guard_recipient_count FROM incident_escalations WHERE incident_id = ?",
                Integer.class, incidentId);
    }

    // ===== a. Đủ điều kiện =====

    @Test
    @DisplayName("a. Có phiên mobile + trong giờ ca + CHECKED_IN -> được mời, sự cố STEP1")
    void a_mobileSessionWithinCheckedInShift_isInvited() {
        User guard = eligibleGuard("CRA1");

        IncidentDetailResponse response = ingestHigh();

        SecurityIncident incident = reload(response.getId());
        assertEquals(IncidentDispatchStage.STEP1, incident.getDispatchStage());
        assertEquals(IncidentDispatchStage.STEP1, response.getDispatchStage());
        assertNull(incident.getBroadcastAt());
        List<IncidentDispatchOffer> offers = offersOf(response.getId());
        assertEquals(1, offers.size());
        assertEquals(guard.getId(), offers.get(0).getGuard().getId());
        assertNull(offers.get(0).getResponse());
        assertEquals(1, guardRecipientCount(response.getId()));
    }

    // ===== b. Mất phiên mobile (DEF-16 phía backend) =====

    @Test
    @DisplayName("b. Đã đăng xuất mobile (chỉ còn phiên web) -> không được mời; không ai đủ điều kiện -> BROADCAST")
    void b_loggedOutOfMobile_notInvited_broadcastWhenNobodyEligible() {
        User guard = saveGuard("CRB1");
        LocalDateTime now = nowVn();
        checkedInShift(guard, now.minusHours(1), now.plusHours(2));
        String mobileSession = connect(guard, WebSocketSessionRegistry.CLIENT_TYPE_MOBILE);
        connect(guard, WebSocketSessionRegistry.CLIENT_TYPE_WEB);
        assertTrue(sessionRegistry.hasMobileSession(guard.getId()));
        disconnect(mobileSession); // đăng xuất app -> SessionDisconnectEvent -> sessionClosed
        assertFalse(sessionRegistry.hasMobileSession(guard.getId()));

        IncidentDetailResponse response = ingestHigh();

        assertTrue(offersOf(response.getId()).isEmpty(), "bảo vệ không có phiên mobile không được nhận lời mời");
        assertEquals(IncidentDispatchStage.BROADCAST, reload(response.getId()).getDispatchStage());
        assertEquals(IncidentDispatchStage.BROADCAST, response.getDispatchStage());
        // Broadcast vẫn gửi cho mọi bảo vệ đang trực, nhưng chỉ người có phiên mobile mới được đếm là đã nhận
        // (/topic/guards/{id}/alerts chỉ app mobile nghe). Count = 0 -> job leo thang báo FM ngay (G2, BR-IE-05).
        // guardRecipientCount đọc bản ghi incident_escalations của sự cố (không có bản ghi -> queryForObject ném lỗi).
        assertEquals(0, guardRecipientCount(response.getId()),
                "bảo vệ không còn phiên mobile không nhận được /topic/guards/{id}/alerts nên không được đếm (G2, BR-IE-05)");
    }

    // ===== c. Check-in sớm, chưa tới giờ bắt đầu ca =====

    @Test
    @DisplayName("c. CHECKED_IN sớm nhưng chưa tới giờ bắt đầu ca -> không được mời (mời từ giờ bắt đầu ca)")
    void c_checkedInBeforeShiftStart_notInvited() {
        User control = eligibleGuard("CRC1");
        User early = saveGuard("CRC2");
        LocalDateTime now = nowVn();
        checkedInShift(early, now.plusMinutes(30), now.plusHours(3));
        connect(early, WebSocketSessionRegistry.CLIENT_TYPE_MOBILE);

        IncidentDetailResponse response = ingestHigh();

        Set<UUID> invited = invitedGuardIds(response.getId());
        assertEquals(Set.of(control.getId()), invited);
        assertFalse(invited.contains(early.getId()));
    }

    // ===== d. Đang giữ sự cố CLAIMED =====

    @Test
    @DisplayName("d. Đang giữ một sự cố CLAIMED -> không được mời")
    void d_holdingClaimedIncident_notInvited() {
        User control = eligibleGuard("CRD1");
        User busy = eligibleGuard("CRD2");
        otherIncident(IncidentStatus.CLAIMED, busy);

        IncidentDetailResponse response = ingestHigh();

        Set<UUID> invited = invitedGuardIds(response.getId());
        assertEquals(Set.of(control.getId()), invited);
        assertFalse(invited.contains(busy.getId()));
    }

    // ===== e. Đang có lời mời chưa trả lời =====

    @Test
    @DisplayName("e. Có lời mời chưa trả lời (response IS NULL) -> không được mời; sau khi DECLINED -> được mời lại")
    void e_pendingOfferExcludes_untilDeclined() {
        User control = eligibleGuard("CRE1");
        User offered = eligibleGuard("CRE2");
        SecurityIncident previous = otherIncident(IncidentStatus.NEW, null);
        IncidentDispatchOffer pending = pendingOffer(previous, offered, OffsetDateTime.now().plusMinutes(5));

        IncidentDetailResponse first = ingestHigh();
        Set<UUID> invitedFirst = invitedGuardIds(first.getId());
        assertEquals(Set.of(control.getId()), invitedFirst);
        assertFalse(invitedFirst.contains(offered.getId()));

        Boolean declined = new TransactionTemplate(transactionManager).execute(status ->
                dispatchService.decline(previous.getId(), offered.getId(), OffsetDateTime.now()));
        assertTrue(Boolean.TRUE.equals(declined));
        assertEquals(DispatchOfferResponse.DECLINED, responseOf(pending.getId()));

        // control đang có lời mời chờ của sự cố thứ nhất -> bị loại; offered đã từ chối -> được mời lại
        IncidentDetailResponse second = ingestHigh();
        Set<UUID> invitedSecond = invitedGuardIds(second.getId());
        assertEquals(Set.of(offered.getId()), invitedSecond);
        assertEquals(IncidentDispatchStage.STEP1, reload(second.getId()).getDispatchStage());
    }

    // ===== f. Tài khoản không hoạt động =====

    @Test
    @DisplayName("f. Tài khoản bị vô hiệu hoá hoặc xoá mềm (vẫn còn ca CHECKED_IN + phiên mobile) -> không được mời")
    void f_inactiveAccount_notInvited() {
        User control = eligibleGuard("CRF1");
        User inactive = eligibleGuard("CRF2");
        inactive.setIsActive(false);
        userRepository.save(inactive);

        IncidentDetailResponse first = ingestHigh();
        Set<UUID> invitedFirst = invitedGuardIds(first.getId());
        assertEquals(Set.of(control.getId()), invitedFirst);
        assertFalse(invitedFirst.contains(inactive.getId()));

        // Xoá mềm: control đang có lời mời chờ -> bị loại; người xoá mềm cũng bị loại -> không ai đủ -> BROADCAST
        User softDeleted = eligibleGuard("CRF3");
        softDeleted.setDeletedAt(OffsetDateTime.now());
        userRepository.save(softDeleted);

        IncidentDetailResponse second = ingestHigh();
        assertTrue(offersOf(second.getId()).isEmpty(), "tài khoản xoá mềm không được nhận lời mời");
        assertEquals(IncidentDispatchStage.BROADCAST, reload(second.getId()).getDispatchStage());
    }

    // ===== g. Lời mời quá hạn nhưng job chưa chạy =====

    @Test
    @DisplayName("g. Lời mời quá hạn, job chưa chạy -> vẫn bị loại; sau expireDueOffers -> EXPIRED, được mời lại")
    void g_overdueOfferExcludesUntilJobExpiresIt() {
        User control = eligibleGuard("CRG1");
        User overdue = eligibleGuard("CRG2");
        SecurityIncident previous = otherIncident(IncidentStatus.NEW, null);
        IncidentDispatchOffer stale = pendingOffer(previous, overdue, OffsetDateTime.now().minusMinutes(1));

        IncidentDetailResponse first = ingestHigh();
        Set<UUID> invitedFirst = invitedGuardIds(first.getId());
        assertEquals(Set.of(control.getId()), invitedFirst);
        assertFalse(invitedFirst.contains(overdue.getId()), "lời mời quá hạn còn response IS NULL vẫn loại bảo vệ");
        assertNull(responseOf(stale.getId()));

        int expired = dispatchService.expireDueOffers(OffsetDateTime.now());
        assertTrue(expired >= 1, "expireDueOffers phải đóng ít nhất lời mời quá hạn của test, expired=" + expired);
        assertEquals(DispatchOfferResponse.EXPIRED, responseOf(stale.getId()));

        IncidentDetailResponse second = ingestHigh();
        Set<UUID> invitedSecond = invitedGuardIds(second.getId());
        assertEquals(Set.of(overdue.getId()), invitedSecond);
    }
}
