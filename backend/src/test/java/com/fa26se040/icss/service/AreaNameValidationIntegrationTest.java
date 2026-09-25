package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.area.AreaCreateRequest;
import com.fa26se040.icss.dto.area.AreaUpdateRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.BuildingRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AreaNameValidationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private FloorRepository floorRepository;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String adminToken;
    private Building testBuilding;
    private Floor testFloor1;
    private Floor testFloor2;

    @BeforeEach
    void setUp() {
        User admin = userRepository.findByEmail("admin@fpt.edu.vn").orElseGet(() -> {
            User u = User.builder()
                    .userCode("ADMIN-INTEG")
                    .fullName("Admin Integration Test")
                    .email("admin@fpt.edu.vn")
                    .role(Role.ADMIN)
                    .isActive(true)
                    .build();
            return userRepository.save(u);
        });
        adminToken = "Bearer " + jwtTokenProvider.generateToken(admin);

        testBuilding = buildingRepository.findByCodeIgnoreCase("TOA_ALPHA")
                .orElseGet(() -> buildingRepository.save(Building.builder().code("TOA_ALPHA").name("Tòa Alpha").build()));

        testFloor1 = floorRepository.findByBuildingCodeIgnoreCaseAndFloorCodeIgnoreCase("TOA_ALPHA", "1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(testBuilding).floorCode("1").name("Tầng 1").floorOrder(1).build()));

        testFloor2 = floorRepository.findByBuildingCodeIgnoreCaseAndFloorCodeIgnoreCase("TOA_ALPHA", "2")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(testBuilding).floorCode("2").name("Tầng 2").floorOrder(2).build()));
    }

    @Test
    @DisplayName("Migration V53: DB test sạch không còn floor_id NULL và unique index ux_areas_floor_name_active tồn tại")
    void testMigration_NoNullFloorId_AndUniqueIndexExists() {
        Integer nullFloorCount = jdbcTemplate.queryForObject("SELECT count(*) FROM areas WHERE floor_id IS NULL", Integer.class);
        assertEquals(0, nullFloorCount, "Sau V53 không được còn dòng nào có floor_id IS NULL");

        Integer indexCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname = 'ux_areas_floor_name_active'", Integer.class);
        assertEquals(1, indexCount, "Index ux_areas_floor_name_active phải tồn tại trong DB");
    }

    @Test
    @DisplayName("Tạo khu vực không có tầng hợp lệ -> 400 ERR_AREA_021")
    void testCreate_InvalidFloor_ThrowsErrArea021() throws Exception {
        AreaCreateRequest req = AreaCreateRequest.builder()
                .name("Phòng Hợp Lệ " + UUID.randomUUID().toString().substring(0, 5))
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(UUID.randomUUID()) // floorId không tồn tại
                .build();

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_AREA_021")));
    }

    @Test
    @DisplayName("BR-AR-05: Tạo 2 khu vực cùng tên khác hoa thường cùng tầng -> Lần 2 bị chặn với 409 Conflict ERR_AREA_020")
    void testCreate_SameNameDifferentCaseSameFloor_ThrowsConflict() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String name1 = "Phòng Hội Thảo " + suffix;
        String name2 = "phòng hội thảo " + suffix;

        AreaCreateRequest req1 = AreaCreateRequest.builder()
                .name(name1)
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(testFloor1.getId())
                .build();

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        AreaCreateRequest req2 = AreaCreateRequest.builder()
                .name(name2)
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(testFloor1.getId())
                .build();

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_AREA_020")));
    }

    @Test
    @DisplayName("BR-AR-05: Cùng tên nhưng khác tầng -> Được phép tạo thành công (201 Created)")
    void testCreate_SameNameDifferentFloor_Success() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String name = "Phòng Lab Đa Năng " + suffix;

        // Tầng 1
        AreaCreateRequest req1 = AreaCreateRequest.builder()
                .name(name)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .floorId(testFloor1.getId())
                .build();

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        // Tầng 2
        AreaCreateRequest req2 = AreaCreateRequest.builder()
                .name(name)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .floorId(testFloor2.getId())
                .build();

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("BR-AR-05: Khu vực trùng tên đã xoá mềm -> Tạo mới được thành công (201 Created)")
    void testCreate_SameNameAsSoftDeletedArea_Success() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String name = "Phòng Tạm Thời " + suffix;

        AreaCreateRequest req1 = AreaCreateRequest.builder()
                .name(name)
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(testFloor1.getId())
                .build();

        MvcResult res1 = mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andReturn();

        String areaId1 = objectMapper.readTree(res1.getResponse().getContentAsString()).get("id").asText();

        // Xoá mềm khu vực 1
        mockMvc.perform(delete("/api/areas/" + areaId1)
                        .header("Authorization", adminToken))
                .andExpect(status().isNoContent());

        // Tạo lại khu vực mới cùng tên cùng tầng
        AreaCreateRequest req2 = AreaCreateRequest.builder()
                .name(name)
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(testFloor1.getId())
                .build();

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("BR-AR-05: Sửa khu vực đổi sang tầng đã có tên đó -> Bị chặn với 409 Conflict ERR_AREA_020")
    void testUpdate_ChangeFloorToExistingName_ThrowsConflict() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String name = "Phòng Nghiên Cứu " + suffix;

        // Khu vực A ở Tầng 1 có tên "Phòng Nghiên Cứu <suffix>"
        AreaCreateRequest reqA = AreaCreateRequest.builder()
                .name(name)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .floorId(testFloor1.getId())
                .build();

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqA)))
                .andExpect(status().isCreated());

        // Khu vực B ở Tầng 2 có tên khác
        AreaCreateRequest reqB = AreaCreateRequest.builder()
                .name("Phòng Khác " + suffix)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .floorId(testFloor2.getId())
                .build();

        MvcResult resB = mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqB)))
                .andExpect(status().isCreated())
                .andReturn();

        String areaBId = objectMapper.readTree(resB.getResponse().getContentAsString()).get("id").asText();

        // Sửa khu vực B: đổi sang Tầng 1 VÀ đổi tên thành "Phòng Nghiên Cứu <suffix>" -> Đã có ở tầng 1!
        AreaUpdateRequest updateReq = AreaUpdateRequest.builder()
                .name(name)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .floorId(testFloor1.getId())
                .build();

        mockMvc.perform(put("/api/areas/" + areaBId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_AREA_020")));
    }

    @Test
    @DisplayName("BR-AR-05: Sửa giữ nguyên tên của chính nó -> Được phép thành công (200 OK)")
    void testUpdate_KeepSameName_Success() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String name = "Phòng Giữ Nguyên " + suffix;

        AreaCreateRequest req = AreaCreateRequest.builder()
                .name(name)
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(testFloor1.getId())
                .build();

        MvcResult res = mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        String areaId = objectMapper.readTree(res.getResponse().getContentAsString()).get("id").asText();

        // Sửa: đổi cấp độ, giữ nguyên tên
        AreaUpdateRequest updateReq = AreaUpdateRequest.builder()
                .name(name)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .floorId(testFloor1.getId())
                .build();

        mockMvc.perform(put("/api/areas/" + areaId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areaLevel", is("INTERNAL_CONFIDENTIAL")));
    }

    @Test
    @DisplayName("BR-AR-01: Gửi chuỗi tiếng Việt dạng NFD -> Lưu thành công và trả về dạng NFC")
    void testVietnameseNfd_StoredAndReturnedAsNfc() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String originalNfc = "Phòng Thí Nghiệm Vi Sinh " + suffix;
        String nfdName = Normalizer.normalize(originalNfc, Normalizer.Form.NFD);

        assertTrue(Normalizer.isNormalized(nfdName, Normalizer.Form.NFD));

        AreaCreateRequest req = AreaCreateRequest.builder()
                .name(nfdName)
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .floorId(testFloor1.getId())
                .build();

        MvcResult result = mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();

        String areaId = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("id").asText();
        String responseName = objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).get("name").asText();

        assertEquals(originalNfc, responseName);
        assertTrue(Normalizer.isNormalized(responseName, Normalizer.Form.NFC), "Tên trả về từ API phải ở chuẩn NFC");

        Area areaInDb = areaRepository.findById(UUID.fromString(areaId)).orElseThrow();
        assertEquals(originalNfc, areaInDb.getName());
        assertTrue(Normalizer.isNormalized(areaInDb.getName(), Normalizer.Form.NFC), "Tên lưu trong DB phải ở chuẩn NFC");
    }

    @Test
    @DisplayName("BR-AR-03 & BR-AR-04: Tên không hợp lệ bị từ chối với mã lỗi tương ứng")
    void testInvalidNames_RejectedWithAppropriateErrorCode() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        // Chỉ số -> ERR_AREA_018
        AreaCreateRequest reqOnlyNumbers = AreaCreateRequest.builder()
                .name("123456")
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(testFloor1.getId())
                .build();

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqOnlyNumbers)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_AREA_018")));

        // Ký tự cấm -> ERR_AREA_019
        AreaCreateRequest reqForbiddenChars = AreaCreateRequest.builder()
                .name("Phòng Máy Chủ @#$")
                .areaLevel(AreaLevel.PUBLIC)
                .floorId(testFloor1.getId())
                .build();

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqForbiddenChars)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_AREA_019")));
    }
}
