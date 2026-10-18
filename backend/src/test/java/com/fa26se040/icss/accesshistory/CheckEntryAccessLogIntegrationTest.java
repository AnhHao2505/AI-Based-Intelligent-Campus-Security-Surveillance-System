package com.fa26se040.icss.accesshistory;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.CameraAccessLogRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.security.InternalServiceKeyFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * POST /api/access-control/check-entry: lượt DENIED của mã người không tồn tại vẫn gắn khu nên ghi được nhật ký,
 * và lỗi ghi nhật ký không làm hỏng quyết định trả cho AI (vẫn HTTP 200, allowed=false).
 * Không dùng @Transactional: recordAccessLog chạy REQUIRES_NEW, cần thấy fixture đã commit (FK area_id).
 * Fixture (khu, camera, log) được dọn trong @AfterEach.
 */
class CheckEntryAccessLogIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private CameraRepository cameraRepository;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private CameraAccessLogRepository cameraAccessLogRepository;
    @Value("${icss.internal.service-key}") private String serviceKey;

    /**
     * Thời điểm cố định 10:00 giờ VN gửi trong trường timestamp của request: CameraAccessLogService xếp mọi lượt
     * bị từ chối trong khung 22:00–06:00 vào INTRUSION, nên không ghim giờ thì test đổi kết quả theo giờ chạy.
     */
    private static final String FIXED_ENTRY_TIME = "2026-10-14T10:00:00+07:00";

    private String suffix;
    private Area area;
    private Camera camera;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Building building = buildingRepository.findByNameIgnoreCase("Tòa lịch sử ra vào")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa lịch sử ra vào").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa lịch sử ra vào", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        area = areaRepository.save(Area.builder()
                .name("AH check-entry " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
        camera = cameraRepository.save(Camera.builder()
                .cameraCode("AH-CE-" + suffix)
                .name("Camera check-entry " + suffix)
                .status(CameraStatus.ACTIVE)
                .operationalStatus(OperationalStatus.ONLINE)
                .area(area)
                .build());
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM camera_access_logs WHERE camera_code = ?", camera.getCameraCode());
        cameraRepository.deleteById(camera.getId());
        areaRepository.deleteById(area.getId());
    }

    private MvcResult checkEntry(String userCode) throws Exception {
        return mockMvc.perform(post("/api/access-control/check-entry")
                        .header(InternalServiceKeyFilter.HEADER, serviceKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "cameraCode", camera.getCameraCode(),
                                "userCode", userCode,
                                "timestamp", FIXED_ENTRY_TIME))))
                .andReturn();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private int logCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM camera_access_logs WHERE camera_code = ?",
                Integer.class, camera.getCameraCode());
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("Mã người không tồn tại → 200 allowed=false, response có khu; ghi đúng 1 dòng UNKNOWN_PERSON gắn khu")
    void unknownUser_deniedAndLoggedWithArea() throws Exception {
        String unknownCode = "AH-NOUSER-" + suffix;

        MvcResult r = checkEntry(unknownCode);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode d = data(r);
        assertThat(d.path("allowed").asBoolean(true)).isFalse();
        assertThat(d.path("areaId").asText()).isEqualTo(area.getId().toString());
        assertThat(d.path("areaName").asText()).isEqualTo(area.getName());
        assertThat(d.path("cameraCode").asText()).isEqualTo(camera.getCameraCode());

        assertThat(logCount()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT area_id, user_id, user_code, access_result, logged_at FROM camera_access_logs WHERE camera_code = ?",
                camera.getCameraCode());
        assertThat(row.get("area_id")).isEqualTo(area.getId());
        assertThat(row.get("user_id")).isNull();
        assertThat(row.get("user_code")).isEqualTo("UNKNOWN");
        assertThat(row.get("access_result")).isEqualTo("UNKNOWN_PERSON");
        // Log ghi đúng thời điểm gửi trong request, không phải giờ máy chạy test
        assertThat(((Timestamp) row.get("logged_at")).toInstant())
                .isEqualTo(OffsetDateTime.parse(FIXED_ENTRY_TIME).toInstant());
    }

    @Test
    @DisplayName("Ghi nhật ký lỗi (repository ném lỗi) → quyết định vẫn trả 200 allowed=false, không có dòng log")
    void logFailure_doesNotBreakDecision() throws Exception {
        doThrow(new DataIntegrityViolationException("giả lập lỗi ghi nhật ký"))
                .when(cameraAccessLogRepository).save(any());

        MvcResult r = checkEntry("AH-NOUSER-FAIL-" + suffix);

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        JsonNode d = data(r);
        assertThat(d.path("allowed").asBoolean(true)).isFalse();
        assertThat(d.path("reason").asText()).contains("không tồn tại");
        assertThat(logCount()).isZero();
    }
}
