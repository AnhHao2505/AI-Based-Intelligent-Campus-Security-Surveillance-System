package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.AccessRequestMember;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * T1b (Lucas chốt 11/11): API trả trạng thái HIỆU LỰC ngay khi qua mốc giờ, không chờ job (≤ 15 phút):
 * APPROVED đã qua endTime -> FINISHED (lý do "Hết thời gian hiệu lực"), PENDING đã qua startTime -> EXPIRED.
 * Bộ lọc trạng thái lọc theo trạng thái hiệu lực ngay trong truy vấn (tổng số trang đúng). Thao tác ghi trên đơn đã hết
 * hiệu lực trả lỗi như khi trạng thái lưu đã đổi, không audit. Job chạy sau đó không làm đổi kết quả API.
 * Mỗi test một khu riêng và lọc theo areaId để không lẫn dữ liệu của test khác trên DB test dùng chung.
 */
class AccessRequestEffectiveStatusIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserRepository userRepository;
    @Autowired private AreaRepository areaRepository;
    @Autowired private BuildingRepository buildingRepository;
    @Autowired private FloorRepository floorRepository;
    @Autowired private AccessRequestRepository accessRequestRepository;
    @Autowired private AccessRequestService accessRequestService;

    private static final String REASON = "Kết thúc theo yêu cầu của ban tổ chức";

    private final List<UUID> createdUsers = new ArrayList<>();
    private String suffix;
    private User fm;
    private User requester;
    private User member;
    private Area area;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        Building building = buildingRepository.findByNameIgnoreCase("Tòa T1b")
                .orElseGet(() -> buildingRepository.save(Building.builder().name("Tòa T1b").build()));
        Floor floor = floorRepository.findByBuildingNameIgnoreCaseAndNameIgnoreCase("Tòa T1b", "Tầng 1")
                .orElseGet(() -> floorRepository.save(Floor.builder().building(building).name("Tầng 1").floorOrder(1).build()));
        fm = user("fm", Role.FACILITY_MANAGER);
        requester = user("req", Role.NORMAL_USER);
        member = user("mem", Role.NORMAL_USER);
        area = areaRepository.save(Area.builder()
                .name("T1b khu " + suffix)
                .building(building.getName())
                .floor(floor.getName())
                .floorEntity(floor)
                .areaLevel(AreaLevel.CONFIDENTIAL)
                .areaAccessLevel(2)
                .isActive(true)
                .build());
    }

    @AfterEach
    void deactivateFixtureUsers() {
        for (User u : userRepository.findAllById(createdUsers)) {
            u.setIsActive(false);
            userRepository.save(u);
        }
        createdUsers.clear();
    }

    private User user(String tag, Role role) {
        User u = userRepository.save(User.builder()
                .userCode("T1B-" + tag.toUpperCase() + "-" + suffix)
                .fullName("T1b " + tag + " " + suffix)
                .email("t1b-" + tag + "-" + suffix + "@fpt.edu.vn")
                .role(role)
                .accessLevel(1)
                .isActive(true)
                .build());
        createdUsers.add(u.getId());
        return u;
    }

    private AccessRequest request(RequestStatus status, OffsetDateTime startAt, OffsetDateTime endAt) {
        AccessRequest req = AccessRequest.builder()
                .area(area)
                .requester(requester)
                .requestType(RequestType.GROUP)
                .purpose("Đơn T1b " + suffix)
                .startTime(startAt.truncatedTo(ChronoUnit.SECONDS))
                .endTime(endAt.truncatedTo(ChronoUnit.SECONDS))
                .status(status)
                .reviewer(status == RequestStatus.PENDING ? null : fm)
                .reviewedAt(status == RequestStatus.PENDING ? null : startAt.minusHours(1))
                .build();
        req.getMembers().add(AccessRequestMember.builder().accessRequest(req).user(member).build());
        return accessRequestRepository.save(req);
    }

    /** (a): APPROVED, đã bắt đầu, endTime = now − 1 phút, job chưa chạy. */
    private AccessRequest elapsedApproved() {
        OffsetDateTime now = OffsetDateTime.now();
        return request(RequestStatus.APPROVED, now.minusHours(2), now.minusMinutes(1));
    }

    private MvcResult call(MockHttpServletRequestBuilder builder, User actor) throws Exception {
        return mockMvc.perform(builder.header("Authorization", "Bearer " + jwtTokenProvider.generateToken(actor))).andReturn();
    }

    private MvcResult callJson(MockHttpServletRequestBuilder builder, User actor, String json) throws Exception {
        return call(builder.contentType(MediaType.APPLICATION_JSON).content(json), actor);
    }

    private JsonNode data(MvcResult r) throws Exception {
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertEquals(200, r.getResponse().getStatus(), body);
        return objectMapper.readTree(body).path("data");
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode detail(AccessRequest r, User actor) throws Exception {
        return data(call(get("/api/access-requests/{id}", r.getId()), actor));
    }

    private JsonNode fmList(RequestStatus status, int page, int size) throws Exception {
        MockHttpServletRequestBuilder b = get("/api/access-requests").param("areaId", area.getId().toString())
                .param("page", String.valueOf(page)).param("size", String.valueOf(size));
        if (status != null) {
            b = b.param("status", status.name());
        }
        return data(call(b, fm));
    }

    private JsonNode myList(RequestStatus status) throws Exception {
        MockHttpServletRequestBuilder b = get("/api/access-requests/my").param("areaId", area.getId().toString()).param("size", "50");
        if (status != null) {
            b = b.param("status", status.name());
        }
        return data(call(b, requester));
    }

    private static Set<String> ids(JsonNode page) {
        Set<String> ids = new HashSet<>();
        page.path("content").forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }

    private static String statusOf(JsonNode page, AccessRequest r) {
        for (JsonNode n : page.path("content")) {
            if (r.getId().toString().equals(n.path("id").asText())) {
                return n.path("status").asText();
            }
        }
        return null;
    }

    private String dbStatus(AccessRequest r) {
        return jdbc.queryForObject("SELECT status FROM access_requests WHERE id = ?", String.class, r.getId());
    }

    private int audits(AccessRequest r) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND target_id = ?",
                Integer.class, r.getId().toString());
    }

    private int auditsWithAction(AccessRequest r, String action) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE target_type = 'ACCESS_REQUEST' AND action = ? AND target_id = ?",
                Integer.class, action, r.getId().toString());
    }

    // ------------------------------------------------------------------ a) trạng thái hiệu lực trong API

    @Test
    @DisplayName("a) APPROVED đã qua endTime 1 phút, job chưa chạy -> chi tiết (FM, người tạo, thành viên) và danh sách (FM, /my) trả FINISHED, lý do 'Hết thời gian hiệu lực'; DB vẫn APPROVED")
    void a_elapsedApproved_apiReturnsFinished() throws Exception {
        AccessRequest r = elapsedApproved();

        for (User viewer : List.of(fm, requester, member)) {
            JsonNode d = detail(r, viewer);
            assertEquals("FINISHED", d.path("status").asText(), viewer.getRole().name());
            assertEquals(AccessRequestService.FINISH_REASON_ELAPSED, d.path("finishReason").asText());
        }
        assertEquals("FINISHED", statusOf(fmList(null, 0, 50), r));
        assertEquals("FINISHED", statusOf(myList(null), r));
        assertEquals("APPROVED", dbStatus(r), "trạng thái lưu chỉ đổi khi job chạy");
    }

    // ------------------------------------------------------------------ b) bộ lọc + phân trang

    @Test
    @DisplayName("b) Lọc 'Đã duyệt' không có đơn (a); lọc 'Đã kết thúc' có đơn (a) + đơn đã lưu FINISHED; tổng phần tử / số trang đúng (lọc trong truy vấn)")
    void b_filterByEffectiveStatus_paginationCorrect() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        AccessRequest elapsed = elapsedApproved();
        AccessRequest ongoing = request(RequestStatus.APPROVED, now.minusHours(1), now.plusHours(1));
        AccessRequest storedFinished = request(RequestStatus.FINISHED, now.minusHours(5), now.minusHours(3));

        JsonNode approved = fmList(RequestStatus.APPROVED, 0, 10);
        assertEquals(Set.of(ongoing.getId().toString()), ids(approved));
        assertEquals(1, approved.path("totalElements").asInt());

        JsonNode finished = fmList(RequestStatus.FINISHED, 0, 10);
        assertEquals(Set.of(elapsed.getId().toString(), storedFinished.getId().toString()), ids(finished));
        assertEquals(2, finished.path("totalElements").asInt());
        assertTrue(statusOf(finished, elapsed).equals("FINISHED"));

        // size = 1 -> 2 trang, mỗi trang 1 đơn khác nhau
        JsonNode p0 = fmList(RequestStatus.FINISHED, 0, 1);
        JsonNode p1 = fmList(RequestStatus.FINISHED, 1, 1);
        assertEquals(2, p0.path("totalPages").asInt());
        assertEquals(2, p0.path("totalElements").asInt());
        Set<String> both = new HashSet<>(ids(p0));
        both.addAll(ids(p1));
        assertEquals(Set.of(elapsed.getId().toString(), storedFinished.getId().toString()), both);

        // Người tạo (/my) lọc như FM
        assertEquals(Set.of(ongoing.getId().toString()), ids(myList(RequestStatus.APPROVED)));
        assertEquals(Set.of(elapsed.getId().toString(), storedFinished.getId().toString()), ids(myList(RequestStatus.FINISHED)));
    }

    // ------------------------------------------------------------------ c) PENDING đã qua startTime

    @Test
    @DisplayName("c) PENDING đã qua startTime 1 phút -> API trả EXPIRED, lọc PENDING không có / EXPIRED có; FM duyệt -> 409 ERR_AC_012; người tạo huỷ -> 409 'đã hết hạn'; không audit")
    void c_elapsedPending_expired_reviewAndCancelRejected() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        AccessRequest r = request(RequestStatus.PENDING, now.minusMinutes(1), now.plusHours(2));

        assertEquals("EXPIRED", detail(r, fm).path("status").asText());
        assertEquals("EXPIRED", statusOf(myList(null), r));
        assertTrue(ids(fmList(RequestStatus.PENDING, 0, 10)).isEmpty());
        assertEquals(Set.of(r.getId().toString()), ids(fmList(RequestStatus.EXPIRED, 0, 10)));

        MvcResult approve = callJson(patch("/api/access-requests/{id}/review", r.getId()), fm, "{\"status\":\"APPROVED\"}");
        assertEquals(409, approve.getResponse().getStatus(), body(approve));
        assertTrue(body(approve).contains("ERR_AC_012"), body(approve));

        MvcResult cancel = call(patch("/api/access-requests/{id}/cancel", r.getId()), requester);
        assertEquals(409, cancel.getResponse().getStatus(), body(cancel));
        assertTrue(body(cancel).contains("Yêu cầu này đã hết hạn, không thể huỷ."), body(cancel));

        assertEquals("PENDING", dbStatus(r));
        assertEquals(0, audits(r));
    }

    // ------------------------------------------------------------------ d) FM kết thúc / huỷ đơn (a)

    @Test
    @DisplayName("d) FM 'Kết thúc' đơn (a) -> 400 như đơn đã FINISHED; FM 'Huỷ' đơn (a) -> 409 'đã ở trạng thái FINISHED' (không phải ERR_AC_008); không audit, DB vẫn APPROVED")
    void d_finishOrCancelElapsed_rejectedWithoutAudit() throws Exception {
        AccessRequest r = elapsedApproved();

        MvcResult finish = callJson(patch("/api/access-requests/{id}/finish", r.getId()), fm, "{\"reason\":\"" + REASON + "\"}");
        assertEquals(400, finish.getResponse().getStatus(), body(finish));
        assertTrue(body(finish).contains("Chỉ yêu cầu truy cập ở trạng thái Đã duyệt (APPROVED) mới có thể chuyển sang Hoàn thành."), body(finish));

        MvcResult cancel = callJson(patch("/api/access-requests/{id}/cancel-approved", r.getId()), fm, "{\"reason\":\"" + REASON + "\"}");
        assertEquals(409, cancel.getResponse().getStatus(), body(cancel));
        assertTrue(body(cancel).contains("Yêu cầu này đã ở trạng thái FINISHED, không thể huỷ."), body(cancel));
        assertTrue(!body(cancel).contains("ERR_AC_008"), body(cancel));

        assertEquals("APPROVED", dbStatus(r));
        assertEquals(0, audits(r));
    }

    @Test
    @DisplayName("d) Đơn còn trong giờ vẫn 'Kết thúc' được như cũ (không chặn nhầm)")
    void d_ongoingApproved_finishStillWorks() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        AccessRequest r = request(RequestStatus.APPROVED, now.minusHours(1), now.plusHours(1));
        MvcResult finish = callJson(patch("/api/access-requests/{id}/finish", r.getId()), fm, "{\"reason\":\"" + REASON + "\"}");
        assertEquals("FINISHED", data(finish).path("status").asText());
        assertEquals(REASON, data(finish).path("finishReason").asText());
        assertEquals(1, auditsWithAction(r, "FINISH"));
    }

    // ------------------------------------------------------------------ e) sau khi job chạy

    @Test
    @DisplayName("e) Job chạy sau: chi tiết và danh sách giống hệt trước khi job chạy (status, finishReason), DB FINISHED, audit FINISH của job đúng 1 dòng")
    void e_afterJob_sameApiResult_oneAudit() throws Exception {
        AccessRequest r = elapsedApproved();
        JsonNode before = detail(r, fm);

        accessRequestService.finishElapsedApprovedRequests();

        JsonNode after = detail(r, fm);
        assertEquals(before.path("status").asText(), after.path("status").asText());
        assertEquals(before.path("finishReason").asText(), after.path("finishReason").asText());
        assertEquals("FINISHED", after.path("status").asText());
        assertEquals("FINISHED", statusOf(fmList(RequestStatus.FINISHED, 0, 10), r));
        assertEquals("FINISHED", dbStatus(r));
        assertEquals(1, auditsWithAction(r, "FINISH"));

        // Job chạy lần 2 không ghi thêm
        accessRequestService.finishElapsedApprovedRequests();
        assertEquals(1, auditsWithAction(r, "FINISH"));
    }
}
