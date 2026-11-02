package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.consumer.SecurityIncidentMqttConsumer;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Chống tạo trùng sự cố theo event_id trên DB test thật (V92): bước kiểm tra đầu ingestIncident và unique index
 * uq_security_incidents_event_id cho trường hợp hai tin đến cùng lúc.
 *
 * <p>Tắt gọi ghi hình sang AI service (aiServiceUrl rỗng) để test không bắn request ra cổng 8000.
 * Sau mỗi test xoá sự cố theo event_id (bản ghi leo thang xoá theo ON DELETE CASCADE), nhật ký camera, camera,
 * khu vực và user test tạo.</p>
 */
class SecurityIncidentDedupeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SecurityIncidentService incidentService;
    @Autowired
    private SecurityIncidentMqttConsumer mqttConsumer;
    @Autowired
    private SecurityIncidentRepository incidentRepository;
    @Autowired
    private CameraRepository cameraRepository;
    @Autowired
    private AreaRepository areaRepository;
    @Autowired
    private BuildingRepository buildingRepository;
    @Autowired
    private FloorRepository floorRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String suffix;
    private String eventIdPrefix;
    private Area area;
    private Camera camera;
    private User adminUser;
    private Object originalAiServiceUrl;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        eventIdPrefix = "DEDUPE-" + suffix;
        Building building = buildingRepository.findByNameIgnoreCase("Tòa Beta")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa Beta").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa Beta", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        area = areaRepository.save(Area.builder()
                .name("Phòng chống trùng " + suffix)
                .building("TOA_BETA")
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .version(0L)
                .build());
        camera = cameraRepository.save(Camera.builder()
                .cameraCode("CAM-DD-" + suffix)
                .name("Camera chống trùng " + suffix)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build());
        adminUser = userRepository.save(User.builder()
                .userCode("ADDD-" + suffix)
                .fullName("ADDD " + suffix)
                .email("addd-" + suffix + "@fpt.edu.vn")
                .role(Role.ADMIN)
                .accessLevel(3)
                .isActive(true)
                .build());

        Object target = AopTestUtils.getUltimateTargetObject(incidentService);
        originalAiServiceUrl = ReflectionTestUtils.getField(target, "aiServiceUrl");
        ReflectionTestUtils.setField(target, "aiServiceUrl", "");
    }

    @AfterEach
    void cleanUp() {
        Object target = AopTestUtils.getUltimateTargetObject(incidentService);
        ReflectionTestUtils.setField(target, "aiServiceUrl", originalAiServiceUrl);
        jdbc.update("DELETE FROM security_incidents WHERE event_id LIKE ?", eventIdPrefix + "%");
        jdbc.update("DELETE FROM camera_access_logs WHERE camera_code = ?", camera.getCameraCode());
        cameraRepository.deleteById(camera.getId());
        areaRepository.deleteById(area.getId());
        userRepository.deleteById(adminUser.getId());
    }

    private IncidentEventDto event(String eventId) {
        return IncidentEventDto.builder()
                .eventId(eventId)
                .cameraCode(camera.getCameraCode())
                .eventType("UNAUTHORIZED_ENTRY")
                .details("Kiểm thử chống trùng")
                .build();
    }

    private String payload(String eventId) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "event_id", eventId,
                "camera_code", camera.getCameraCode(),
                "event_type", "UNAUTHORIZED_ENTRY",
                "details", "Kiểm thử chống trùng"));
    }

    private int countByEventId(String eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM security_incidents WHERE event_id = ?", Integer.class, eventId);
    }

    private int countEscalations(String eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM incident_escalations e JOIN security_incidents i ON i.id = e.incident_id"
                + " WHERE i.event_id = ?", Integer.class, eventId);
    }

    /**
     * Luồng khác giữ một transaction đã INSERT sự cố cùng event_id nhưng chưa commit: ingestIncident không thấy bản đó
     * ở bước kiểm tra đầu (READ COMMITTED), INSERT của nó chờ trên unique index, rồi vấp ràng buộc khi luồng kia commit.
     * Đây là đúng tình huống hai tin trùng đến cùng lúc, nhưng tái hiện được ổn định.
     */
    private <T> T whileOtherTransactionHoldsSameEventId(String eventId, Callable<T> action) throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                incidentRepository.saveAndFlush(SecurityIncident.builder()
                        .eventId(eventId)
                        .cameraCode(camera.getCameraCode())
                        .area(area)
                        .building("TOA_BETA")
                        .eventType("UNAUTHORIZED_ENTRY")
                        .detectedAt(OffsetDateTime.now())
                        .status(IncidentStatus.NEW)
                        .version(0)
                        .build());
                inserted.countDown();
                try {
                    // Đủ lâu để action bên dưới chạy tới INSERT và chờ trên unique index trước khi transaction này commit
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertTrue(inserted.await(30, TimeUnit.SECONDS));
            try {
                return action.call();
            } finally {
                holder.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("V92: unique index uq_security_incidents_event_id có, partial theo event_id có giá trị")
    void migration_uniqueIndexExists() {
        String indexDef = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'security_incidents' AND indexname = 'uq_security_incidents_event_id'",
                String.class);
        assertNotNull(indexDef);
        assertTrue(indexDef.startsWith("CREATE UNIQUE INDEX"), indexDef);
        assertTrue(indexDef.contains("WHERE"), indexDef);
    }

    @Test
    @DisplayName("Gửi lại cùng event_id -> trả đúng sự cố cũ, chỉ 1 sự cố và 1 bản ghi leo thang")
    void sameEventIdTwice_returnsExistingIncident() {
        String eventId = eventIdPrefix + "-SEQ";

        IncidentDetailResponse first = incidentService.ingestIncident(event(eventId));
        IncidentDetailResponse second = incidentService.ingestIncident(event(eventId));

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(first.getId(), second.getId());
        assertEquals(1, countByEventId(eventId));
        assertEquals(1, countEscalations(eventId));
    }

    @Test
    @DisplayName("2 luồng MQTT cùng event_id cùng lúc -> đúng 1 sự cố, consumer không ném lỗi")
    void concurrentMqttMessages_sameEventId_exactlyOneIncident() throws Exception {
        String eventId = eventIdPrefix + "-RACE";
        String json = payload(eventId);

        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> run = () -> {
            start.await();
            mqttConsumer.processMessage(json);
            return null;
        };
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Void> first = pool.submit(run);
            Future<Void> second = pool.submit(run);
            start.countDown();
            first.get(30, TimeUnit.SECONDS);
            second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, countByEventId(eventId));
        assertEquals(1, countEscalations(eventId));
    }

    @Test
    @DisplayName("Tin trùng lọt qua bước kiểm tra -> unique index chặn, ném DataIntegrityViolationException nêu tên index")
    void duplicatePassingPreCheck_blockedByUniqueIndex() throws Exception {
        String eventId = eventIdPrefix + "-IDX";

        DataIntegrityViolationException ex = whileOtherTransactionHoldsSameEventId(eventId,
                () -> assertThrows(DataIntegrityViolationException.class, () -> incidentService.ingestIncident(event(eventId))));

        assertTrue(String.valueOf(ex.getMostSpecificCause().getMessage()).contains("uq_security_incidents_event_id"),
                ex.getMostSpecificCause().getMessage());
        assertEquals(1, countByEventId(eventId));
        // Bản bị chặn không tạo bản ghi leo thang (bản giữ chỗ do test tạo không có leo thang)
        assertEquals(0, countEscalations(eventId));
    }

    @Test
    @DisplayName("Tin trùng lọt qua bước kiểm tra qua MQTT -> consumer bỏ qua, không ném lỗi, vẫn 1 sự cố")
    void duplicatePassingPreCheck_mqttConsumerSkips() throws Exception {
        String eventId = eventIdPrefix + "-MQTT";
        String json = payload(eventId);

        whileOtherTransactionHoldsSameEventId(eventId, () -> {
            mqttConsumer.processMessage(json);
            return null;
        });

        assertEquals(1, countByEventId(eventId));
        assertEquals(0, countEscalations(eventId));
    }

    @Test
    @DisplayName("Tin trùng lọt qua bước kiểm tra qua REST /api/incidents/ingest -> 409 (GlobalExceptionHandler sẵn có)")
    void duplicatePassingPreCheck_restIngestReturns409() throws Exception {
        String eventId = eventIdPrefix + "-REST";
        String json = payload(eventId);
        String token = jwtTokenProvider.generateToken(adminUser);

        MvcResult result = whileOtherTransactionHoldsSameEventId(eventId, () -> mockMvc.perform(post("/api/incidents/ingest")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andReturn());

        assertEquals(409, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals(1, countByEventId(eventId));
    }

    @Test
    @DisplayName("Gửi lại cùng event_id qua REST -> lần 2 trả sự cố cũ (201 như cũ), không tạo thêm")
    void sameEventIdTwice_rest_returnsExisting() throws Exception {
        String eventId = eventIdPrefix + "-REST2";
        String json = payload(eventId);
        String token = jwtTokenProvider.generateToken(adminUser);

        for (int i = 0; i < 2; i++) {
            MvcResult result = mockMvc.perform(post("/api/incidents/ingest")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json))
                    .andReturn();
            assertEquals(201, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        }

        assertEquals(1, countByEventId(eventId));
        assertEquals(1, countEscalations(eventId));
    }
}
