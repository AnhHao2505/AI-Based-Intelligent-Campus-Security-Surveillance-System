package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccessRequestIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    @Test
    @Transactional
    @DisplayName("Integration Test: GET /api/access-requests/my và GET /api/access-requests hoạt động đúng khi areaId null và khi có areaId")
    void testAccessRequests_AreaIdNullAndNotNull_ReturnsCorrectResults() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. Tạo User Sinh viên và FM
        User student = User.builder()
                .userCode("STU-" + uniqueSuffix)
                .fullName("Student Real Test " + uniqueSuffix)
                .email("student-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build();
        student = userRepository.save(student);

        User fm = User.builder()
                .userCode("FM-" + uniqueSuffix)
                .fullName("FM Real Test " + uniqueSuffix)
                .email("fm-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.FACILITY_MANAGER)
                .accessLevel(3)
                .isActive(true)
                .build();
        fm = userRepository.save(fm);

        // 2. Tạo 2 Area
        Area area1 = Area.builder()
                .name("Area 1 " + uniqueSuffix)
                .building("ALPHA")
                .floor("1")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .build();
        area1 = areaRepository.save(area1);

        Area area2 = Area.builder()
                .name("Area 2 " + uniqueSuffix)
                .building("BETA")
                .floor("2")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .build();
        area2 = areaRepository.save(area2);

        // 3. Tạo 2 AccessRequest cho sinh viên
        AccessRequest req1 = AccessRequest.builder()
                .area(area1)
                .requester(student)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Học tại Area 1")
                .startTime(OffsetDateTime.now().plusDays(1))
                .endTime(OffsetDateTime.now().plusDays(1).plusHours(2))
                .status(RequestStatus.PENDING)
                .createdAt(OffsetDateTime.now())
                .build();
        req1 = accessRequestRepository.save(req1);

        AccessRequest req2 = AccessRequest.builder()
                .area(area2)
                .requester(student)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Học tại Area 2")
                .startTime(OffsetDateTime.now().plusDays(2))
                .endTime(OffsetDateTime.now().plusDays(2).plusHours(2))
                .status(RequestStatus.APPROVED)
                .reviewer(fm)
                .reviewedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now().plusMinutes(5))
                .build();
        req2 = accessRequestRepository.save(req2);

        String studentToken = "Bearer " + jwtTokenProvider.generateToken(student);
        String fmToken = "Bearer " + jwtTokenProvider.generateToken(fm);

        // ========================================================
        // A. Kiểm tra GET /api/access-requests/my (Sinh viên)
        // ========================================================
        // Case 1: Không truyền areaId (areaId = null) -> Trả về tất cả yêu cầu của sinh viên (ít nhất 2)
        mockMvc.perform(get("/api/access-requests/my")
                        .header("Authorization", studentToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.content[0].areaId").exists())
                .andExpect(jsonPath("$.content[0].areaName").exists());

        // Case 2: Truyền areaId = area1.id -> Chỉ trả về bản ghi của area1
        mockMvc.perform(get("/api/access-requests/my")
                        .header("Authorization", studentToken)
                        .param("areaId", area1.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].id", is(req1.getId().toString())))
                .andExpect(jsonPath("$.content[0].areaId", is(area1.getId().toString())))
                .andExpect(jsonPath("$.content[0].areaName", is(area1.getName())));

        // Case 3: Truyền areaId = area2.id và status = APPROVED -> Chỉ trả về req2
        mockMvc.perform(get("/api/access-requests/my")
                        .header("Authorization", studentToken)
                        .param("areaId", area2.getId().toString())
                        .param("status", "APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].id", is(req2.getId().toString())))
                .andExpect(jsonPath("$.content[0].areaId", is(area2.getId().toString())))
                .andExpect(jsonPath("$.content[0].status", is("APPROVED")));

        // ========================================================
        // B. Kiểm tra GET /api/access-requests (FM)
        // ========================================================
        // Case 4: FM gọi không truyền areaId -> Trả về danh sách (ít nhất 2)
        mockMvc.perform(get("/api/access-requests")
                        .header("Authorization", fmToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.content[0].areaId").exists())
                .andExpect(jsonPath("$.content[0].areaName").exists());

        // Case 5: FM gọi lọc theo areaId = area2.id -> Trả về bản ghi của area2
        mockMvc.perform(get("/api/access-requests")
                        .header("Authorization", fmToken)
                        .param("areaId", area2.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].areaId", is(area2.getId().toString())))
                .andExpect(jsonPath("$.content[0].areaName", is(area2.getName())));

        // Case 6: FM gọi lọc theo areaId = area1.id và status = PENDING -> Trả về req1
        mockMvc.perform(get("/api/access-requests")
                        .header("Authorization", fmToken)
                        .param("areaId", area1.getId().toString())
                        .param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id", is(req1.getId().toString())))
                .andExpect(jsonPath("$.content[0].areaId", is(area1.getId().toString())))
                .andExpect(jsonPath("$.content[0].status", is("PENDING")));
    }

    @Test
    @Transactional
    @DisplayName("Integration Test: User B là thành viên đơn nhóm (không phải người tạo) -> GET /my của B phải thấy đơn đó, có và không có areaId, thứ tự mới nhất trước")
    void testMyRequests_UserIsMemberOfGroupRequest_ReturnsRequestInOrder() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. Tạo User A (người tạo đơn) và User B (thành viên)
        User studentA = userRepository.save(User.builder()
                .userCode("STU-A-" + uniqueSuffix)
                .fullName("Student A " + uniqueSuffix)
                .email("student-a-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());

        User studentB = userRepository.save(User.builder()
                .userCode("STU-B-" + uniqueSuffix)
                .fullName("Student B " + uniqueSuffix)
                .email("student-b-" + uniqueSuffix + "@fpt.edu.vn")
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());

        // 2. Tạo 2 Khu vực Area 1 và Area 2
        Area area1 = areaRepository.save(Area.builder()
                .name("Group Area 1 " + uniqueSuffix)
                .building("ALPHA")
                .floor("1")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .build());

        Area area2 = areaRepository.save(Area.builder()
                .name("Individual Area 2 " + uniqueSuffix)
                .building("BETA")
                .floor("2")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(true)
                .isActive(true)
                .build());

        // 3. Tạo đơn cá nhân cũ hơn cho User B (createdAt = now - 1h)
        AccessRequest reqBIndividual = accessRequestRepository.save(AccessRequest.builder()
                .area(area2)
                .requester(studentB)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Học cá nhân tại Area 2")
                .startTime(OffsetDateTime.now().plusDays(1))
                .endTime(OffsetDateTime.now().plusDays(1).plusHours(2))
                .status(RequestStatus.APPROVED)
                .createdAt(OffsetDateTime.now().minusHours(1))
                .build());

        // 4. Tạo đơn nhóm mới hơn do User A tạo, User B là THÀNH VIÊN (createdAt = now + 10m)
        AccessRequest reqGroup = AccessRequest.builder()
                .area(area1)
                .requester(studentA)
                .requestType(RequestType.GROUP)
                .purpose("Học nhóm tại Area 1")
                .startTime(OffsetDateTime.now().plusDays(2))
                .endTime(OffsetDateTime.now().plusDays(2).plusHours(3))
                .status(RequestStatus.PENDING)
                .createdAt(OffsetDateTime.now().plusMinutes(10))
                .build();

        AccessRequestMember arm = AccessRequestMember.builder()
                .accessRequest(reqGroup)
                .user(studentB)
                .build();
        reqGroup.getMembers().add(arm);
        reqGroup = accessRequestRepository.save(reqGroup);

        String studentBToken = "Bearer " + jwtTokenProvider.generateToken(studentB);

        // A. GET /api/access-requests/my KHÔNG có areaId:
        // User B phải thấy cả đơn nhóm (với tư cách member) và đơn cá nhân (với tư cách requester).
        // Thứ tự mới nhất trước: content[0] là reqGroup, content[1] là reqBIndividual.
        mockMvc.perform(get("/api/access-requests/my")
                        .header("Authorization", studentBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(2)))
                .andExpect(jsonPath("$.content[0].id", is(reqGroup.getId().toString())))
                .andExpect(jsonPath("$.content[0].isRequester", is(false)))
                .andExpect(jsonPath("$.content[0].requestType", is("GROUP")))
                .andExpect(jsonPath("$.content[0].areaId", is(area1.getId().toString())))
                .andExpect(jsonPath("$.content[1].id", is(reqBIndividual.getId().toString())))
                .andExpect(jsonPath("$.content[1].isRequester", is(true)))
                .andExpect(jsonPath("$.content[1].requestType", is("INDIVIDUAL")))
                .andExpect(jsonPath("$.content[1].areaId", is(area2.getId().toString())));

        // B. GET /api/access-requests/my CÓ areaId = area1.id (khu vực của đơn nhóm):
        // User B phải thấy đơn nhóm mà mình là thành viên
        mockMvc.perform(get("/api/access-requests/my")
                        .header("Authorization", studentBToken)
                        .param("areaId", area1.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].id", is(reqGroup.getId().toString())))
                .andExpect(jsonPath("$.content[0].isRequester", is(false)))
                .andExpect(jsonPath("$.content[0].areaId", is(area1.getId().toString())))
                .andExpect(jsonPath("$.content[0].requestType", is("GROUP")));

        // C. GET /api/access-requests/my CÓ areaId = area2.id (khu vực của đơn cá nhân):
        mockMvc.perform(get("/api/access-requests/my")
                        .header("Authorization", studentBToken)
                        .param("areaId", area2.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements", is(1)))
                .andExpect(jsonPath("$.content[0].id", is(reqBIndividual.getId().toString())))
                .andExpect(jsonPath("$.content[0].isRequester", is(true)))
                .andExpect(jsonPath("$.content[0].areaId", is(area2.getId().toString())));

        // D. User B (thành viên) gọi API huỷ đơn nhóm của User A -> Bị từ chối quyền (403 Forbidden)
        mockMvc.perform(patch("/api/access-requests/" + reqGroup.getId() + "/cancel")
                        .header("Authorization", studentBToken))
                .andExpect(status().isForbidden());
    }
}
