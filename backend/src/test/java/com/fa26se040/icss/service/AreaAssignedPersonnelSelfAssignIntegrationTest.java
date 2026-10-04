package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.AreaAssignedPersonnel;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaAssignedPersonnelRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * BR-AP-06 (TC-AA-09): FM không tự gán / tự gia hạn quyền chỉ định (AP) cho chính mình -> 403 ERR_AP_011.
 * Gán cho FM khác, được FM khác gán, và tự thu hồi AP của chính mình vẫn được phép.
 * @Transactional: dữ liệu fixture rollback sau mỗi test.
 */
@Transactional
class AreaAssignedPersonnelSelfAssignIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AreaAssignedPersonnelRepository assignedPersonnelRepository;

    private String suffix;
    private User fm1;
    private User fm2;
    private Area area;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa AP self")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa AP self").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa AP self", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        fm1 = user("fm1");
        fm2 = user("fm2");
        area = areaRepository.save(Area.builder()
                .name("AP self " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL_CONTACT_REQUIRED)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .isActive(true)
                .build());
    }

    private User user(String tag) {
        return userRepository.save(User.builder()
                .userCode("APS-" + tag.toUpperCase() + "-" + suffix)
                .fullName("AP self " + tag + " " + suffix)
                .email("aps-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .accessLevel(3)
                .isActive(true)
                .build());
    }

    private MvcResult send(MockHttpServletRequestBuilder b, User actor, Object body) throws Exception {
        b.header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor));
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(b).andReturn();
    }

    private JsonNode json(MvcResult r) throws Exception {
        String c = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        return c.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(c);
    }

    private String describe(MvcResult r) throws Exception {
        return "HTTP " + r.getResponse().getStatus() + " " + r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Map<String, Object> assignBody(User target, OffsetDateTime validTo) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("userId", target.getId());
        b.put("validTo", validTo.toString());
        b.put("reason", "Gán quyền chỉ định để thử BR-AP-06");
        return b;
    }

    private Map<String, Object> extendBody(OffsetDateTime validTo) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("validTo", validTo.toString());
        b.put("reason", "Gia hạn quyền chỉ định để thử BR-AP-06");
        return b;
    }

    @Test
    @DisplayName("TC-AA-09 (BR-AP-06): FM tự gán / tự gia hạn -> 403 ERR_AP_011; gán chéo FM-1 <-> FM-2 OK; tự thu hồi AP của mình OK")
    void tc_aa_09_fmCannotSelfAssign() throws Exception {
        String base = "/api/areas/{areaId}/assigned-personnel";
        OffsetDateTime until = OffsetDateTime.now().plusDays(7).truncatedTo(ChronoUnit.MINUTES);

        // 1. FM-1 tự gán -> từ chối, không tạo bản ghi nào
        MvcResult self = send(post(base, area.getId()), fm1, assignBody(fm1, until));
        assertThat(self.getResponse().getStatus()).as(describe(self)).isEqualTo(403);
        assertThat(json(self).path("code").asText()).as(describe(self)).isEqualTo("ERR_AP_011");
        assertThat(json(self).path("message").asText()).as(describe(self)).isNotBlank();
        assertThat(assignedPersonnelRepository.findByAreaIdWithDetails(area.getId())).isEmpty();

        // 2. FM-1 gán FM-2 -> OK
        MvcResult cross1 = send(post(base, area.getId()), fm1, assignBody(fm2, until));
        assertThat(cross1.getResponse().getStatus()).as(describe(cross1)).isEqualTo(201);

        // 3. FM-2 gán FM-1 -> OK
        MvcResult cross2 = send(post(base, area.getId()), fm2, assignBody(fm1, until));
        assertThat(cross2.getResponse().getStatus()).as(describe(cross2)).isEqualTo(201);
        UUID fm1ApId = UUID.fromString(json(cross2).path("data").path("id").asText());

        // 4. FM-1 gia hạn AP của chính mình -> từ chối, validTo không đổi
        MvcResult selfExtend = send(patch(base + "/{id}", area.getId(), fm1ApId), fm1, extendBody(until.plusDays(30)));
        assertThat(selfExtend.getResponse().getStatus()).as(describe(selfExtend)).isEqualTo(403);
        assertThat(json(selfExtend).path("code").asText()).as(describe(selfExtend)).isEqualTo("ERR_AP_011");
        AreaAssignedPersonnel afterSelfExtend = assignedPersonnelRepository.findById(fm1ApId).orElseThrow();
        assertThat(afterSelfExtend.getValidTo().toInstant()).isEqualTo(until.toInstant());

        // 5. FM-1 tự thu hồi AP của chính mình -> OK
        Map<String, Object> revoke = Map.of("reason", "Tự thu hồi quyền chỉ định của mình");
        MvcResult selfRevoke = send(patch(base + "/{id}/revoke", area.getId(), fm1ApId), fm1, revoke);
        assertThat(selfRevoke.getResponse().getStatus()).as(describe(selfRevoke)).isEqualTo(200);
        assertThat(assignedPersonnelRepository.findById(fm1ApId).orElseThrow().getRevokedAt()).isNotNull();
    }
}
