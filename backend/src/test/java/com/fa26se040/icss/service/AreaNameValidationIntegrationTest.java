package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.area.AreaCreateRequest;
import com.fa26se040.icss.dto.area.AreaUpdateRequest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.text.Normalizer;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

public class AreaNameValidationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String adminToken;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        User admin = User.builder()
                .userCode("ADM-" + suffix)
                .fullName("Admin Name Test")
                .email("admin-" + suffix + "@fpt.edu.vn")
                .role(Role.ADMIN)
                .isActive(true)
                .build();
        admin = userRepository.save(admin);
        adminToken = "Bearer " + jwtTokenProvider.generateToken(admin);
    }

    @Test
    @DisplayName("BR-AR-05: Trùng tên trong cùng building + floor -> 409 Conflict ERR_AREA_020")
    void testDuplicateName_SameBuildingAndFloor_ReturnsConflict() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String name = "Phòng Máy Chủ " + suffix;
        String building = "Tòa Alpha " + suffix;
        String floor = "Tầng 1";

        AreaCreateRequest req1 = new AreaCreateRequest(
                "A1-" + suffix,
                name,
                AreaLevel.INTERNAL_CONFIDENTIAL,
                building,
                floor,
                "Mô tả khu vực 1"
        );

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is(name)));

        // Gửi request thứ 2 với cùng tên, building, floor
        AreaCreateRequest req2 = new AreaCreateRequest(
                "A2-" + suffix,
                name,
                AreaLevel.INTERNAL_CONFIDENTIAL,
                building,
                floor,
                "Mô tả khu vực 2"
        );

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_AREA_020")));
    }

    @Test
    @DisplayName("BR-AR-05: Cùng tên nhưng khác tầng -> Tạo thành công (201 Created)")
    void testSameName_DifferentFloor_ReturnsCreated() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String name = "Phòng Họp " + suffix;
        String building = "Tòa Beta " + suffix;

        AreaCreateRequest req1 = new AreaCreateRequest(
                "B1-" + suffix,
                name,
                AreaLevel.PUBLIC,
                building,
                "Tầng 1",
                "Mô tả 1"
        );

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        AreaCreateRequest req2 = new AreaCreateRequest(
                "B2-" + suffix,
                name,
                AreaLevel.PUBLIC,
                building,
                "Tầng 2",
                "Mô tả 2"
        );

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name", is(name)));
    }

    @Test
    @DisplayName("BR-AR-05: Trùng tên khác chữ hoa/thường và khoảng trắng thừa -> 409 Conflict ERR_AREA_020")
    void testDuplicateName_DifferentCaseAndSpaces_ReturnsConflict() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String building = "Tòa Gamma " + suffix;
        String floor = "Tầng G";

        AreaCreateRequest req1 = new AreaCreateRequest(
                "C1-" + suffix,
                "Phòng Nghiên Cứu " + suffix,
                AreaLevel.INTERNAL_CONFIDENTIAL,
                building,
                floor,
                "Mô tả"
        );

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        // Request 2: chữ hoa toàn bộ và nhiều khoảng trắng ở giữa / đầu / cuối
        AreaCreateRequest req2 = new AreaCreateRequest(
                "C2-" + suffix,
                "   PHÒNG    NGHIÊN    CỨU    " + suffix + "   ",
                AreaLevel.INTERNAL_CONFIDENTIAL,
                "  " + building + "  ",
                "  " + floor + "  ",
                "Mô tả"
        );

        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_AREA_020")));
    }

    @Test
    @DisplayName("BR-AR-05: Update chính nó giữ nguyên tên -> 200 OK")
    void testUpdateSelf_SameName_ReturnsOk() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String name = "Phòng Lab " + suffix;
        String building = "Tòa Delta " + suffix;
        String floor = "Tầng 3";

        AreaCreateRequest req1 = new AreaCreateRequest(
                "D1-" + suffix,
                name,
                AreaLevel.INTERNAL_CONFIDENTIAL,
                building,
                floor,
                "Mô tả ban đầu"
        );

        MvcResult createRes = mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated())
                .andReturn();

        String responseBody = createRes.getResponse().getContentAsString();
        String areaId = objectMapper.readTree(responseBody).get("id").asText();

        AreaUpdateRequest updateReq = new AreaUpdateRequest(
                "D1-" + suffix,
                name,
                AreaLevel.INTERNAL_CONFIDENTIAL,
                building,
                floor,
                "Mô tả sau khi cập nhật"
        );

        mockMvc.perform(put("/api/areas/" + areaId)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description", is("Mô tả sau khi cập nhật")));
    }

    @Test
    @DisplayName("BR-AR-05: Update đổi tên thành tên khu vực khác cùng building + floor -> 409 Conflict ERR_AREA_020")
    void testUpdate_CollidesWithExistingArea_ReturnsConflict() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String building = "Tòa Epsilon " + suffix;
        String floor = "Tầng 4";

        AreaCreateRequest req1 = new AreaCreateRequest(
                "E1-" + suffix,
                "Khu Vực Một " + suffix,
                AreaLevel.PUBLIC,
                building,
                floor,
                "Mô tả 1"
        );
        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        AreaCreateRequest req2 = new AreaCreateRequest(
                "E2-" + suffix,
                "Khu Vực Hai " + suffix,
                AreaLevel.PUBLIC,
                building,
                floor,
                "Mô tả 2"
        );
        MvcResult res2 = mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isCreated())
                .andReturn();

        String area2Id = objectMapper.readTree(res2.getResponse().getContentAsString()).get("id").asText();

        // Đổi tên area 2 thành "Khu Vực Một " + suffix (đã có ở area 1)
        AreaUpdateRequest updateReq = new AreaUpdateRequest(
                "E2-" + suffix,
                "Khu Vực Một " + suffix,
                AreaLevel.PUBLIC,
                building,
                floor,
                "Đổi tên gây trùng"
        );

        mockMvc.perform(put("/api/areas/" + area2Id)
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateReq)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_AREA_020")));
    }

    @Test
    @DisplayName("BR-AR-01: Gửi chuỗi tiếng Việt dạng NFD -> Lưu thành công và trả về dạng NFC")
    void testVietnameseNfd_StoredAndReturnedAsNfc() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String originalNfc = "Phòng Thí Nghiệm Vi Sinh " + suffix;
        String nfdName = Normalizer.normalize(originalNfc, Normalizer.Form.NFD);

        // Đảm bảo request gửi lên ở dạng NFD
        assertTrue(Normalizer.isNormalized(nfdName, Normalizer.Form.NFD));

        AreaCreateRequest req = new AreaCreateRequest(
                "NFD-" + suffix,
                nfdName,
                AreaLevel.INTERNAL_CONFIDENTIAL,
                "Tòa Vi Sinh " + suffix,
                "Tầng 5",
                "Mô tả NFD"
        );

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

        // Kiểm tra trong DB
        var areaInDb = areaRepository.findById(UUID.fromString(areaId)).orElseThrow();
        assertEquals(originalNfc, areaInDb.getName());
        assertTrue(Normalizer.isNormalized(areaInDb.getName(), Normalizer.Form.NFC), "Tên lưu trong DB phải ở chuẩn NFC");
    }

    @Test
    @DisplayName("BR-AR-03 & BR-AR-04: Tên không hợp lệ bị từ chối với mã lỗi tương ứng")
    void testInvalidNames_RejectedWithAppropriateErrorCode() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        // Chỉ số -> ERR_AREA_018
        AreaCreateRequest reqOnlyNumbers = new AreaCreateRequest(
                "NUM-" + suffix,
                "123456",
                AreaLevel.PUBLIC,
                "Tòa Nhà",
                "1",
                "Mô tả"
        );
        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqOnlyNumbers)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_AREA_018")));

        // Ký tự cấm -> ERR_AREA_019
        AreaCreateRequest reqForbiddenChars = new AreaCreateRequest(
                "FRB-" + suffix,
                "Phòng Máy Chủ @#$",
                AreaLevel.PUBLIC,
                "Tòa Nhà",
                "1",
                "Mô tả"
        );
        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqForbiddenChars)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("ERR_AREA_019")));
    }

    @Test
    @DisplayName("Vi phạm constraint khác (trùng code) -> Trả về đúng ERR_AREA_001, không phải ERR_AREA_020")
    void testDuplicateCode_ReturnsErrArea001_NotErrArea020() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        String code = "DUP-" + suffix;

        AreaCreateRequest req1 = new AreaCreateRequest(
                code,
                "Phòng Ban Đầu " + suffix,
                AreaLevel.PUBLIC,
                "Tòa Nhà 1 " + suffix,
                "Tầng 1",
                "Mô tả 1"
        );
        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req1)))
                .andExpect(status().isCreated());

        // Tạo khu vực thứ 2: tên khác, tòa khác, tầng khác nhưng TRÙNG CODE
        AreaCreateRequest req2 = new AreaCreateRequest(
                code,
                "Phòng Khác Hoàn Toàn " + suffix,
                AreaLevel.PUBLIC,
                "Tòa Nhà 2 " + suffix,
                "Tầng 2",
                "Mô tả 2"
        );
        mockMvc.perform(post("/api/areas")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("ERR_AREA_001")));
    }
}
