package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Ô "Hết giờ hành chính" (Lucas 05/11): tick = quyền chỉ định hết hạn lúc đóng cửa CỦA NGÀY bắt đầu (giờ Việt Nam).
 * Giờ đóng cửa = AI_AFTER_HOUR_START (giờ bắt đầu khung ngoài giờ), đọc qua SystemConfigService.
 * Bắt đầu đã qua giờ đóng cửa của ngày đó -> 400 ERR_AP_014, không tạo bản ghi. Không tick -> như cũ (validTo trống).
 * @Transactional: dữ liệu rollback; giá trị cấu hình trong cache được trả lại ở @AfterEach.
 */
@Transactional
class AssignedPersonnelUntilClosingTimeTest extends AbstractIntegrationTest {

    private static final String BASE = "/api/areas/{areaId}/assigned-personnel";
    private static final ZoneId VN = ZoneId.of("Asia/Ho_Chi_Minh");

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AreaAssignedPersonnelRepository assignedPersonnelRepository;
    @Autowired private SystemConfigService systemConfigService;

    private String suffix;
    private User fm;
    private User target;
    private Area area;
    private String originalClosing;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa AP closing")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa AP closing").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa AP closing", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        fm = user("fm", Role.FACILITY_MANAGER);
        target = user("usr", Role.NORMAL_USER);
        area = areaRepository.save(Area.builder()
                .name("AP closing " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
        originalClosing = systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START);
    }

    /** Trả giá trị cấu hình về như cũ (cache trong bộ nhớ không rollback theo transaction). */
    @AfterEach
    void restoreClosing() {
        if (originalClosing != null && !originalClosing.equals(systemConfigService.getString(ConfigKey.AI_AFTER_HOUR_START))) {
            systemConfigService.update(ConfigKey.AI_AFTER_HOUR_START.getKey(), originalClosing, fm.getEmail());
        }
    }

    private void setClosing(String hhmm) {
        systemConfigService.update(ConfigKey.AI_AFTER_HOUR_START.getKey(), hhmm, fm.getEmail());
    }

    private User user(String tag, Role role) {
        return userRepository.save(User.builder()
                .userCode("APC-" + tag.toUpperCase() + "-" + suffix)
                .fullName("AP closing " + tag + " " + suffix)
                .email("apc-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
    }

    /** Ngày mai theo giờ Việt Nam, lúc hh:mm. */
    private static OffsetDateTime tomorrowAt(int hour, int minute) {
        LocalDate tomorrow = OffsetDateTime.now().atZoneSameInstant(VN).toLocalDate().plusDays(1);
        return tomorrow.atTime(LocalTime.of(hour, minute)).atZone(VN).toOffsetDateTime();
    }

    private MvcResult assign(OffsetDateTime validFrom, OffsetDateTime validTo, Boolean untilClosingTime) throws Exception {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("userId", target.getId());
        if (validFrom != null) {
            b.put("validFrom", validFrom.toString());
        }
        if (validTo != null) {
            b.put("validTo", validTo.toString());
        }
        if (untilClosingTime != null) {
            b.put("untilClosingTime", untilClosingTime);
        }
        b.put("reason", "Gán quyền chỉ định đến hết giờ hành chính");
        return mockMvc.perform(post(BASE, area.getId())
                        .header("Authorization", "Bearer " + jwtTokenProvider.generateToken(fm))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(b)))
                .andReturn();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    private String body(MvcResult r) throws Exception {
        return "HTTP " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private String code(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("code").asText();
    }

    @Test
    @DisplayName("Tick, bắt đầu 09:00 ngày mai, đóng cửa 18:00 -> 201, validTo = 18:00 ngày mai (giờ Việt Nam)")
    void tickWithinHours_validToIsClosingTimeOfStartDay() throws Exception {
        setClosing("18:00");
        OffsetDateTime from = tomorrowAt(9, 0);

        MvcResult r = assign(from, null, true);

        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        OffsetDateTime validTo = OffsetDateTime.parse(data(r).path("validTo").asText());
        assertThat(validTo.isEqual(tomorrowAt(18, 0))).as("validTo = " + validTo).isTrue();
        assertThat(data(r).path("status").asText()).isEqualTo("UPCOMING");
    }

    @Test
    @DisplayName("Tick, bắt đầu 19:00 ngày mai, đóng cửa 18:00 -> 400 ERR_AP_014, không tạo bản ghi")
    void tickStartAfterClosing_rejected() throws Exception {
        setClosing("18:00");

        MvcResult r = assign(tomorrowAt(19, 0), null, true);

        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(400);
        assertThat(code(r)).isEqualTo("ERR_AP_014");
        assertThat(assignedPersonnelRepository.findByAreaIdWithDetails(area.getId())).isEmpty();
    }

    @Test
    @DisplayName("Tick, bắt đầu bỏ trống (lúc cấp), giờ đóng cửa 00:00 -> lúc cấp đã qua giờ đóng cửa hôm nay -> 400 ERR_AP_014")
    void tickGrantedAfterClosingToday_rejected() throws Exception {
        setClosing("00:00");

        MvcResult r = assign(null, null, true);

        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(400);
        assertThat(code(r)).isEqualTo("ERR_AP_014");
        assertThat(assignedPersonnelRepository.findByAreaIdWithDetails(area.getId())).isEmpty();
    }

    @Test
    @DisplayName("Đổi cấu hình giờ đóng cửa sang 20:30 -> validTo đổi theo (20:30 ngày bắt đầu)")
    void closingConfigChanged_validToFollows() throws Exception {
        setClosing("20:30");

        MvcResult r = assign(tomorrowAt(9, 0), null, true);

        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        OffsetDateTime validTo = OffsetDateTime.parse(data(r).path("validTo").asText());
        assertThat(validTo.isEqual(tomorrowAt(20, 30))).as("validTo = " + validTo).isTrue();
    }

    @Test
    @DisplayName("Không tick, không có validTo -> như cũ: validTo trống (không thời hạn)")
    void notTicked_unchangedBehaviour() throws Exception {
        setClosing("18:00");

        MvcResult r = assign(tomorrowAt(9, 0), null, null);

        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);
        assertThat(data(r).path("validTo").isNull() || data(r).path("validTo").isMissingNode()).as(body(r)).isTrue();
    }

    @Test
    @DisplayName("Tick kèm validTo -> 400 ERR_AP_015 (ô tự tính thời điểm kết thúc), không tạo bản ghi")
    void tickWithExplicitValidTo_rejected() throws Exception {
        MvcResult r = assign(tomorrowAt(9, 0), tomorrowAt(12, 0), true);

        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(400);
        assertThat(code(r)).isEqualTo("ERR_AP_015");
        assertThat(assignedPersonnelRepository.findByAreaIdWithDetails(area.getId())).isEmpty();
    }
}
