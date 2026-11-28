package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.FloorRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SecurityIncidentAnonymousDedupIntegrationTest extends AbstractIntegrationTest {

    @Autowired private SecurityIncidentService incidentService;
    @Autowired private CameraRepository cameraRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private JdbcTemplate jdbc;

    private String suffix;
    private String eventIdPrefix;
    private Area area;
    private Camera camera;
    private Object originalAiServiceUrl;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        eventIdPrefix = "ANONDEDUP-" + suffix;
        Building building = buildingRepository.findByNameIgnoreCase("Tòa Beta")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa Beta").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa Beta", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        area = areaRepository.save(Area.builder()
                .name("Phòng ban đêm " + suffix)
                .building("TOA_BETA")
                .floor("1")
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .version(0L)
                .build());
        camera = cameraRepository.save(Camera.builder()
                .cameraCode("CAM-ANON-" + suffix)
                .name("Camera ban đêm " + suffix)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build());
        Object target = AopTestUtils.getUltimateTargetObject(incidentService);
        originalAiServiceUrl = ReflectionTestUtils.getField(target, "aiServiceUrl");
        ReflectionTestUtils.setField(target, "aiServiceUrl", "");
    }

    @AfterEach
    void cleanUp() {
        Object target = AopTestUtils.getUltimateTargetObject(incidentService);
        ReflectionTestUtils.setField(target, "aiServiceUrl", originalAiServiceUrl);
        jdbc.update("DELETE FROM security_incidents WHERE camera_code = ?", camera.getCameraCode());
        jdbc.update("DELETE FROM camera_access_logs WHERE camera_code = ?", camera.getCameraCode());
        cameraRepository.deleteById(camera.getId());
        areaRepository.deleteById(area.getId());
    }

    @Test
    @DisplayName("Anonymous PERSON_INTRUSION and UNKNOWN_PERSON alerts create separate incidents without merging")
    void anonymousAlerts_withinCooldown_createSeparateIncidents() {
        OffsetDateTime base = OffsetDateTime.now().minusSeconds(30);
        IncidentDetailResponse first = incidentService.ingestIncident(IncidentEventDto.builder()
                .eventId(eventIdPrefix + "-1")
                .cameraCode(camera.getCameraCode())
                .eventType("PERSON_INTRUSION")
                .detectedAt(base.toString())
                .details("[PERSON_INTRUSION] Vị trí: " + area.getName())
                .build());
        assertNotNull(first);
        assertEquals(1, first.getRepeatCount());

        for (int i = 2; i <= 5; i++) {
            IncidentDetailResponse next = incidentService.ingestIncident(IncidentEventDto.builder()
                    .eventId(eventIdPrefix + "-" + i)
                    .cameraCode(camera.getCameraCode())
                    .eventType(i % 2 == 0 ? "UNKNOWN_PERSON" : "PERSON_INTRUSION")
                    .detectedAt(base.plusSeconds((i - 1) * 5L).toString())
                    .details("[" + (i % 2 == 0 ? "UNKNOWN_PERSON" : "PERSON_INTRUSION") + "] Vị trí: " + area.getName())
                    .build());
            assertNotNull(next, "Expected anonymous alert #" + i + " to create its own incident");
            assertNotEquals(first.getId(), next.getId());
            assertEquals(1, next.getRepeatCount());
        }

        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM security_incidents WHERE camera_code = ?",
                Integer.class, camera.getCameraCode());
        Integer mergedCount = jdbc.queryForObject(
                "SELECT count(*) FROM security_incidents WHERE camera_code = ? AND repeat_count > 1",
                Integer.class, camera.getCameraCode());
        assertEquals(5, count);
        assertEquals(0, mergedCount);
    }

    @Test
    @DisplayName("UNVERIFIED_TIMEOUT incident persists verification_status and exposes NFR trace timestamps")
    void timeoutIncident_persistsVerificationStatusAndTraceTimestamps() {
        OffsetDateTime captured = OffsetDateTime.now().minusSeconds(2);
        OffsetDateTime published = captured.plusNanos(150_000_000L);
        OffsetDateTime received = published.plusNanos(50_000_000L);

        IncidentDetailResponse res = incidentService.ingestIncident(IncidentEventDto.builder()
                .eventId(eventIdPrefix + "-TIMEOUT")
                .cameraCode(camera.getCameraCode())
                .eventType("PERSON_INTRUSION")
                .verificationStatus("UNVERIFIED_TIMEOUT")
                .frameCapturedAt(captured.toString())
                .mqttPublishedAt(published.toString())
                .mqttReceivedAt(received.toString())
                .detectedAt(published.toString())
                .details("[PERSON_INTRUSION] Tên: Nguyen Van A (SE99999)\nLý do: Không thể xác thực quyền (Backend không phản hồi / quá thời gian)\nVị trí: " + area.getName())
                .build());

        assertNotNull(res);
        assertEquals("UNVERIFIED_TIMEOUT", res.getVerificationStatus());
        assertEquals(captured, res.getFrameCapturedAt());
        assertEquals(published, res.getMqttPublishedAt());
        assertEquals(received, res.getMqttReceivedAt());
        assertNotNull(res.getWsPushedAt());

        String dbStatus = jdbc.queryForObject(
                "SELECT verification_status FROM security_incidents WHERE id = ?",
                String.class, res.getId());
        assertEquals("UNVERIFIED_TIMEOUT", dbStatus);
    }
}
