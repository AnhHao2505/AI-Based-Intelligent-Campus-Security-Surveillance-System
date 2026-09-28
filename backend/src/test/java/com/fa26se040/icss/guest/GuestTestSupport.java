package com.fa26se040.icss.guest;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.*;
import com.fa26se040.icss.enums.*;
import com.fa26se040.icss.repository.*;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fa26se040.icss.service.GuestFaceEmbeddingClient;
import com.fa26se040.icss.service.GuestPhotoStorageService;
import com.fa26se040.icss.service.SystemConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Nền chung cho test khách G-A / G-C. Mỗi test dùng user + khu vực riêng (hậu tố ngẫu nhiên),
 * dữ liệu không rollback nên chỉ assert trên dữ liệu của chính test.
 * Kho ảnh MinIO được mock (dự án chưa có MinIO container cho test).
 */
public abstract class GuestTestSupport extends AbstractIntegrationTest {

    protected static final String PURPOSE = "Tham quan phòng thí nghiệm theo lời mời";
    protected static final String REASON = "Lý do hợp lệ đủ mười ký tự";
    protected static final double LAT = 10.8418;
    protected static final double LNG = 106.8100;

    @Autowired protected MockMvc mockMvc;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected JwtTokenProvider jwtTokenProvider;
    @Autowired protected UserRepository userRepository;
    @Autowired protected AreaRepository areaRepository;
    @Autowired protected BuildingRepository buildingRepository;
    @Autowired protected FloorRepository floorRepository;
    @Autowired protected AreaAssignedPersonnelRepository assignedPersonnelRepository;
    @Autowired protected AccessRequestRepository accessRequestRepository;
    @Autowired protected AreaEventSessionRepository sessionRepository;
    @Autowired protected AuditLogRepository auditLogRepository;
    @Autowired protected NotificationRepository notificationRepository;
    @Autowired protected SystemConfigService systemConfigService;
    @Autowired protected GuestVisitRepository guestVisitRepository;
    @Autowired protected GuestRepository guestRepository;
    @Autowired protected GuestFaceEmbeddingRepository embeddingRepository;
    @Autowired protected TransactionTemplate transactionTemplate;
    @Autowired protected jakarta.persistence.EntityManager entityManager;

    @MockBean protected GuestPhotoStorageService photoStorage;
    @MockBean protected GuestFaceEmbeddingClient faceClient;

    protected String suffix;
    protected Building building;
    protected Floor floor;
    protected User admin;
    protected User fm;
    protected User fm2;
    protected User hostL2;
    protected User hostL1;
    protected User otherHost;
    protected User guard;
    /** INTERNAL, cấp 2, không bắt buộc chỉ định: host cấp 2 vào bằng ACCESS_LEVEL. */
    protected Area internalArea;
    /** CONTACT, cấp 2, bắt buộc chỉ định: host chỉ vào được bằng AP. */
    protected Area contactArea;
    protected Area highlyArea;
    protected Area publicArea;

    @BeforeEach
    void setUpGuestBase() {
        Mockito.reset(photoStorage, faceClient);
        suffix = UUID.randomUUID().toString().substring(0, 8);
        building = buildingRepository.findByCodeIgnoreCase("TEST_BLD_GA")
                .orElseGet(() -> buildingRepository.save(Building.builder()
                        .name("Tòa nhà Test khách").code("TEST_BLD_GA").isActive(true).build()));
        floor = floorRepository.save(Floor.builder()
                .name("Tầng test khách " + suffix).floorCode("FGA-" + suffix).floorOrder(1)
                .building(building).isActive(true).build());

        admin = newUser("adm", Role.ADMIN, 3, true);
        fm = newUser("fma", Role.FACILITY_MANAGER, 3, true);
        fm2 = newUser("fmb", Role.FACILITY_MANAGER, 3, true);
        hostL2 = newUser("h2", Role.NORMAL_USER, 2, true);
        hostL1 = newUser("h1", Role.NORMAL_USER, 1, true);
        otherHost = newUser("h2b", Role.NORMAL_USER, 2, true);
        guard = newUser("grd", Role.GUARD, 2, true);

        internalArea = newArea(AreaLevel.INTERNAL_CONFIDENTIAL, 2, false);
        contactArea = newArea(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED, 2, true);
        highlyArea = newArea(AreaLevel.HIGHLY_CONFIDENTIAL, 3, true);
        publicArea = newArea(AreaLevel.PUBLIC, 1, false);

        for (ConfigKey k : List.of(ConfigKey.GUEST_HOST_MIN_LEVEL, ConfigKey.GUEST_MAX_PER_VISIT, ConfigKey.GUEST_VISIT_MAX_HOURS,
                ConfigKey.GUEST_MAX_ADVANCE_DAYS, ConfigKey.GUEST_FACE_RETENTION_HOURS, ConfigKey.GUEST_RECORD_RETENTION_DAYS,
                ConfigKey.GUEST_PHOTO_URL_TTL_SECONDS, ConfigKey.GUEST_CONSENT_NOTICE_VERSION)) {
            setConfig(k, k.getDefaultValue());
        }
    }

    // ------------------------------------------------------------------ dữ liệu

    protected void setConfig(ConfigKey key, String value) {
        if (!value.equals(systemConfigService.getString(key))) {
            systemConfigService.update(key.name(), value, admin.getEmail());
        }
    }

    protected User newUser(String tag, Role role, int accessLevel, boolean active) {
        User u = saveUser(tag, role, accessLevel, active);
        createdUsers.add(u.getId());
        return u;
    }

    private final List<UUID> createdUsers = new ArrayList<>();

    /**
     * Vô hiệu hoá user fixture sau mỗi test: thông báo "mọi FM / ADMIN đang hoạt động" của các module khác không phải
     * gửi thêm cho user test đã xong (DB test không rollback, số user đang hoạt động tăng dần qua các lượt chạy).
     */
    @AfterEach
    void deactivateFixtureUsers() {
        transactionTemplate.executeWithoutResult(tx -> {
            for (User u : userRepository.findAllById(createdUsers)) {
                u.setIsActive(false);
                userRepository.save(u);
            }
        });
        createdUsers.clear();
    }

    private User saveUser(String tag, Role role, int accessLevel, boolean active) {
        return userRepository.save(User.builder()
                .email("tga." + tag + "." + suffix + "@fpt.edu.vn")
                .userCode("TGA-" + tag.toUpperCase() + "-" + suffix)
                .fullName("Test khách " + tag + " " + suffix)
                .role(role)
                .accessLevel(accessLevel)
                .isActive(active)
                .build());
    }

    protected Area newArea(AreaLevel level, int accessLevel, boolean explicit) {
        return areaRepository.save(Area.builder()
                .name("Khu khách " + level.name().substring(0, 6).toLowerCase() + " " + UUID.randomUUID().toString().substring(0, 6))
                .areaLevel(level)
                .areaAccessLevel(accessLevel)
                .explicitAuthorizationRequired(explicit)
                .floorEntity(floor)
                .building(building.getCode())
                .floor(floor.getFloorCode())
                .centerLatitude(LAT)
                .centerLongitude(LNG)
                .isActive(true)
                .openToMembers(false)
                .build());
    }

    protected Area reload(Area area) {
        return areaRepository.findById(area.getId()).orElseThrow();
    }

    protected AreaAssignedPersonnel newAp(Area area, User user, OffsetDateTime from, OffsetDateTime to) {
        return assignedPersonnelRepository.save(AreaAssignedPersonnel.builder()
                .area(area).user(user).validFrom(from).validTo(to).note("AP test khách").createdBy(fm).build());
    }

    protected AccessRequest newApprovedRequest(Area area, User requester, OffsetDateTime start, OffsetDateTime end) {
        return accessRequestRepository.save(AccessRequest.builder()
                .area(area).requester(requester).requestType(RequestType.INDIVIDUAL)
                .purpose("Đơn test khách " + suffix).startTime(start).endTime(end)
                .status(RequestStatus.APPROVED).reviewer(fm).reviewedAt(OffsetDateTime.now().minusHours(1))
                .build());
    }

    /**
     * Mở sự kiện trực tiếp qua JPA. Giữ trong giới hạn chế độ sự kiện (≤ EVENT_MODE_MAX_HOURS) và luôn đóng ở @AfterEach:
     * sự kiện mở vượt giới hạn làm SystemConfigService phát EVENT_MODE_LIMIT_CHANGED cho mọi FM ở các test khác.
     */
    protected void openEvent(Area area, OffsetDateTime startedAt, OffsetDateTime until) {
        Area a = reload(area);
        a.setOpenToMembers(true);
        a.setOpenUntil(until);
        areaRepository.save(a);
        sessionRepository.save(AreaEventSession.builder()
                .area(a).startedAt(startedAt).plannedEnd(until).actualEnd(null).startedBy(fm).createdAt(startedAt).build());
        openedEventAreas.add(a.getId());
    }

    private final List<UUID> openedEventAreas = new ArrayList<>();

    @AfterEach
    void closeOpenedEvents() {
        for (UUID areaId : openedEventAreas) {
            closeEvents(areaId);
        }
        openedEventAreas.clear();
    }

    /** Đóng các sự kiện còn mở của fixture khách từ lượt chạy trước (chỉ khu vực thuộc tòa TEST_BLD_GA). */
    @BeforeAll
    void closeLeftoverGuestFixtureEvents() {
        List<UUID> areaIds = transactionTemplate.execute(tx -> entityManager.createQuery(
                        "SELECT DISTINCT s.area.id FROM AreaEventSession s WHERE s.actualEnd IS NULL AND s.area.building = 'TEST_BLD_GA'", UUID.class)
                .getResultList());
        if (areaIds != null) {
            areaIds.forEach(this::closeEvents);
        }
    }

    private void closeEvents(UUID areaId) {
        transactionTemplate.executeWithoutResult(tx -> {
            OffsetDateTime now = OffsetDateTime.now();
            for (AreaEventSession s : sessionRepository.findByAreaId(areaId)) {
                if (s.getActualEnd() == null) {
                    s.setActualEnd(now.isBefore(s.getStartedAt()) ? s.getStartedAt() : now);
                    sessionRepository.save(s);
                }
            }
            areaRepository.findById(areaId).ifPresent(a -> {
                a.setOpenToMembers(false);
                a.setOpenUntil(null);
                areaRepository.save(a);
            });
        });
    }

    /** Mốc tương lai tròn giây: ngày mai + 2 ngày, 09:00 theo giờ hiện tại + offset phút. */
    protected OffsetDateTime future(long plusMinutes) {
        return OffsetDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MINUTES).plusMinutes(plusMinutes);
    }

    /** Lượt khách dựng thẳng qua JPA (không qua API) cho các trạng thái chưa có API tạo ra. */
    protected GuestVisit newVisit(User host, GuestVisitStatus status, OffsetDateTime start, OffsetDateTime end,
                                  List<Area> areas, String... guestNames) {
        return transactionTemplate.execute(tx -> {
            GuestVisit v = GuestVisit.builder()
                    .host(host).purpose(PURPOSE).startTime(start).endTime(end).status(status)
                    .reviewedBy(status == GuestVisitStatus.PENDING ? null : fm)
                    .reviewedAt(status == GuestVisitStatus.PENDING ? null : OffsetDateTime.now().minusMinutes(5))
                    .closedAt(status == GuestVisitStatus.EXPIRED || status == GuestVisitStatus.COMPLETED ? end : null)
                    .cancelledAt(status == GuestVisitStatus.CANCELLED ? OffsetDateTime.now() : null)
                    .build();
            v.getAreas().addAll(areas);
            for (String n : guestNames) {
                v.getGuests().add(Guest.builder().visit(v).fullName(n).build());
            }
            return guestVisitRepository.save(v);
        });
    }

    /** Gắn ảnh + embedding giả vào khách (không qua API) để thử luồng xoá sinh trắc. */
    protected Guest withPhoto(Guest guest, OffsetDateTime expiresAt) {
        return transactionTemplate.execute(tx -> {
            Guest g = guestRepository.findById(guest.getId()).orElseThrow();
            g.setBiometricStatus(GuestBiometricStatus.PHOTO_READY);
            g.setPhotoObjectKey("visits/" + g.getVisit().getId() + "/" + g.getId() + "/test.jpg");
            g.setConsentConfirmedBy(admin);
            g.setConsentConfirmedAt(OffsetDateTime.now().minusMinutes(1));
            g.setConsentNoticeVersion("v1");
            g.setPhotoAttachedAt(OffsetDateTime.now().minusMinutes(1));
            guestRepository.save(g);
            embeddingRepository.save(GuestFaceEmbedding.builder()
                    .guestId(g.getId()).embedding(vector(0.01f)).expiresAt(expiresAt).build());
            return g;
        });
    }

    protected static String vector(float v) {
        StringJoiner sj = new StringJoiner(",", "[", "]");
        for (int i = 0; i < 512; i++) {
            sj.add(Float.toString(v));
        }
        return sj.toString();
    }

    protected GuestVisit visit(UUID id) {
        return guestVisitRepository.findById(id).orElseThrow();
    }

    protected List<Guest> guestsOf(UUID visitId) {
        return guestRepository.findByVisitIdOrderByCreatedAtAscIdAsc(visitId);
    }

    protected Guest guest(UUID id) {
        return guestRepository.findById(id).orElseThrow();
    }

    // ------------------------------------------------------------------ body

    protected Map<String, Object> guestInput(String name, String org) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("fullName", name);
        if (org != null) {
            g.put("organization", org);
        }
        return g;
    }

    protected Map<String, Object> createBody(OffsetDateTime start, OffsetDateTime end, List<Area> areas, List<Map<String, Object>> guests) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("purpose", PURPOSE);
        b.put("startTime", start.toString());
        b.put("endTime", end.toString());
        b.put("areaIds", areas.stream().map(a -> a.getId().toString()).toList());
        b.put("guests", guests);
        return b;
    }

    protected Map<String, Object> validBody() {
        return createBody(future(0), future(120), List.of(internalArea),
                List.of(guestInput("Nguyễn Văn Khách", "Công ty ABC"), guestInput("Trần Thị Mời", null)));
    }

    // ------------------------------------------------------------------ HTTP

    protected String bearer(User user) {
        return "Bearer " + jwtTokenProvider.generateToken(user);
    }

    protected ResultActions send(MockHttpServletRequestBuilder builder, User actor, Object body) throws Exception {
        builder.header("Authorization", bearer(actor));
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON)
                    .content(body instanceof String s ? s : objectMapper.writeValueAsString(body));
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

    protected int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    // ------------------------------------------------------------------ audit + thông báo

    protected List<AuditLog> auditsForTarget(String targetId) {
        return auditLogRepository.findAll((root, q, cb) -> cb.equal(root.get("targetId"), targetId));
    }

    protected List<AuditLog> audits(String targetId, AuditTargetType type, AuditAction action) {
        return auditsForTarget(targetId).stream()
                .filter(a -> a.getTargetType() == type && a.getAction() == action).toList();
    }

    protected List<Notification> notificationsOf(User user, NotificationType type) {
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(user.getId(), Pageable.unpaged()).getContent()
                .stream().filter(n -> n.getType() == type).toList();
    }
}
