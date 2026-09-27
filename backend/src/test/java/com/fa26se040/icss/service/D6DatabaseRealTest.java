package com.fa26se040.icss.service;

import com.fa26se040.icss.AbstractIntegrationTest;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.area.AreaCreateRequest;
import com.fa26se040.icss.dto.area.AreaResponse;
import com.fa26se040.icss.entity.AccessRequest;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class D6DatabaseRealTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AreaService areaService;

    @Autowired
    private AccessRequestService accessRequestService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AreaRepository areaRepository;

    @Autowired
    private BuildingRepository buildingRepository;

    @Autowired
    private FloorRepository floorRepository;

    @Autowired
    private AccessRequestRepository accessRequestRepository;

    private Building testBuilding;
    private Floor testFloor;
    private User adminUser;
    private User fmUser;
    private User studentUser;
    private String suffix;

    @BeforeEach
    void setUp() {
        suffix = UUID.randomUUID().toString().substring(0, 8);

        testBuilding = buildingRepository.findByCodeIgnoreCase("BLD_D6_REAL")
                .orElseGet(() -> buildingRepository.save(Building.builder()
                        .name("Tòa nhà Test D6 Real")
                        .code("BLD_D6_REAL")
                        .isActive(true)
                        .build()));

        testFloor = floorRepository.findByBuildingIdAndFloorCodeIgnoreCase(testBuilding.getId(), "F1")
                .orElseGet(() -> floorRepository.save(Floor.builder()
                        .name("Tầng 1 Test D6 Real")
                        .floorCode("F1")
                        .floorOrder(1)
                        .building(testBuilding)
                        .isActive(true)
                        .build()));

        adminUser = userRepository.save(User.builder()
                .email("admin." + suffix + "@fpt.edu.vn")
                .userCode("AD_" + suffix)
                .fullName("Admin " + suffix)
                .role(Role.ADMIN)
                .accessLevel(3)
                .isActive(true)
                .build());

        fmUser = userRepository.save(User.builder()
                .email("fm." + suffix + "@fpt.edu.vn")
                .userCode("FM_" + suffix)
                .fullName("FM " + suffix)
                .role(Role.FACILITY_MANAGER)
                .accessLevel(3)
                .isActive(true)
                .build());

        studentUser = userRepository.save(User.builder()
                .email("student." + suffix + "@fpt.edu.vn")
                .userCode("ST_" + suffix)
                .fullName("Student " + suffix)
                .role(Role.NORMAL_USER)
                .accessLevel(1)
                .isActive(true)
                .build());
    }

    @Test
    @DisplayName("D6-13: Gọi thật AreaService.create (ADMIN) và AccessRequestService.reviewRequest REJECT (FM) -> SELECT audit_logs JOIN audit_event_types")
    void testD6_13_RealDatabase_AreaCreate_And_AccessRequestReviewReject() {
        // 1. ADMIN tạo khu vực mới qua AreaService.create
        AreaCreateRequest areaReq = AreaCreateRequest.builder()
                .name("Khu vực D6-13 " + suffix)
                .areaLevel(AreaLevel.PUBLIC)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .floorId(testFloor.getId())
                .build();
        AreaResponse createdArea = areaService.create(areaReq, adminUser.getEmail());
        assertNotNull(createdArea);
        assertNotNull(createdArea.id());

        // Kiểm tra audit_logs cho thao tác AREA / CREATE
        String areaAuditSql = """
                SELECT al.target_type, al.action, al.actor_type, al.changed_by, al.correlation_id, aet.module
                FROM audit_logs al
                JOIN audit_event_types aet ON al.target_type = aet.target_type AND al.action = aet.action
                WHERE al.target_id = ? AND al.action = 'CREATE'
                """;
        List<Map<String, Object>> areaLogs = jdbcTemplate.queryForList(areaAuditSql, createdArea.id().toString());
        assertEquals(1, areaLogs.size(), "Phải có đúng 1 dòng audit log cho AREA / CREATE");

        Map<String, Object> areaLog = areaLogs.get(0);
        assertEquals("AREA", areaLog.get("target_type"));
        assertEquals("CREATE", areaLog.get("action"));
        assertEquals("USER", areaLog.get("actor_type"));
        assertEquals(adminUser.getId(), areaLog.get("changed_by"));
        assertNotNull(areaLog.get("correlation_id"), "correlation_id không được null");
        assertEquals("AREA", areaLog.get("module"), "Module phải là AREA qua JOIN audit_event_types");

        // 2. Tạo đơn AccessRequest PENDING
        Area areaEntity = areaRepository.findById(createdArea.id()).orElseThrow();
        AccessRequest request = accessRequestRepository.save(AccessRequest.builder()
                .area(areaEntity)
                .requester(studentUser)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Kiểm thử D6-13 mục đích truy cập hợp lệ")
                .startTime(OffsetDateTime.now().plusDays(1))
                .endTime(OffsetDateTime.now().plusDays(1).plusHours(2))
                .status(RequestStatus.PENDING)
                .build());
        assertNotNull(request.getId());

        // 3. FM từ chối đơn truy cập (lý do >= 10 ký tự) qua AccessRequestService.reviewRequest
        String rejectionReason = "Lý do từ chối kiểm thử hợp lệ D6-13";
        AccessRequestReviewRequest reviewReq = new AccessRequestReviewRequest(
                RequestStatus.REJECTED,
                rejectionReason
        );
        accessRequestService.reviewRequest(request.getId(), reviewReq, fmUser.getEmail());

        // Kiểm tra audit_logs cho thao tác ACCESS_REQUEST / REJECT
        String reqAuditSql = """
                SELECT al.target_type, al.action, al.actor_type, al.changed_by, al.correlation_id, aet.module
                FROM audit_logs al
                JOIN audit_event_types aet ON al.target_type = aet.target_type AND al.action = aet.action
                WHERE al.target_id = ? AND al.action = 'REJECT'
                """;
        List<Map<String, Object>> reqLogs = jdbcTemplate.queryForList(reqAuditSql, request.getId().toString());
        assertEquals(1, reqLogs.size(), "Phải có đúng 1 dòng audit log cho ACCESS_REQUEST / REJECT");

        Map<String, Object> reqLog = reqLogs.get(0);
        assertEquals("ACCESS_REQUEST", reqLog.get("target_type"));
        assertEquals("REJECT", reqLog.get("action"));
        assertEquals("USER", reqLog.get("actor_type"));
        assertEquals(fmUser.getId(), reqLog.get("changed_by"));
        assertNotNull(reqLog.get("correlation_id"), "correlation_id không được null");
        assertEquals("ACCESS_REQUEST", reqLog.get("module"), "Module phải là ACCESS_REQUEST qua JOIN audit_event_types");
    }

    @Test
    @DisplayName("D6-14: Tạo 2 đơn quá hạn, huỷ 1 đơn -> expireOverdueRequests -> đơn còn lại EXPIRED có 1 dòng SYSTEM, đơn huỷ không có dòng EXPIRE")
    void testD6_14_RealDatabase_ExpireOverdueRequests() {
        // Tạo khu vực mẫu
        Area area = areaRepository.save(Area.builder()
                .name("Khu vực D6-14 " + suffix)
                .areaLevel(AreaLevel.PUBLIC)
                .areaAccessLevel(1)
                .explicitAuthorizationRequired(false)
                .openToMembers(false)
                .floorEntity(testFloor)
                .building(testBuilding.getCode())
                .floor(testFloor.getFloorCode())
                .isActive(true)
                .build());

        // 1. Tạo 2 đơn PENDING có start_time trong quá khứ
        OffsetDateTime pastStart1 = OffsetDateTime.now().minusHours(3);
        OffsetDateTime pastEnd1 = OffsetDateTime.now().minusHours(2);
        AccessRequest req1 = accessRequestRepository.save(AccessRequest.builder()
                .area(area)
                .requester(studentUser)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Đơn 1 sẽ bị huỷ")
                .startTime(pastStart1)
                .endTime(pastEnd1)
                .status(RequestStatus.PENDING)
                .build());

        OffsetDateTime pastStart2 = OffsetDateTime.now().minusHours(2);
        OffsetDateTime pastEnd2 = OffsetDateTime.now().minusHours(1);
        AccessRequest req2 = accessRequestRepository.save(AccessRequest.builder()
                .area(area)
                .requester(studentUser)
                .requestType(RequestType.INDIVIDUAL)
                .purpose("Đơn 2 sẽ bị tự động hết hạn")
                .startTime(pastStart2)
                .endTime(pastEnd2)
                .status(RequestStatus.PENDING)
                .build());

        // 2. Huỷ 1 đơn (req1) bằng cancelRequest
        accessRequestService.cancelRequest(req1.getId(), studentUser.getEmail());

        AccessRequest reloadedReq1 = accessRequestRepository.findById(req1.getId()).orElseThrow();
        assertEquals(RequestStatus.CANCELLED, reloadedReq1.getStatus(), "req1 phải ở trạng thái CANCELLED sau khi huỷ");

        // 3. Gọi expireOverdueRequests
        int expiredCount = accessRequestService.expireOverdueRequests();
        assertTrue(expiredCount >= 1, "Phải hết hạn ít nhất đơn req2");

        AccessRequest reloadedReq2 = accessRequestRepository.findById(req2.getId()).orElseThrow();
        assertEquals(RequestStatus.EXPIRED, reloadedReq2.getStatus(), "req2 phải chuyển sang trạng thái EXPIRED");

        // 4. Kiểm tra audit_logs cho req2 (đơn được hết hạn tự động)
        String expireAuditSql = """
                SELECT al.target_type, al.action, al.actor_type, al.actor_source, al.changed_by, al.correlation_id, aet.module
                FROM audit_logs al
                JOIN audit_event_types aet ON al.target_type = aet.target_type AND al.action = aet.action
                WHERE al.target_id = ? AND al.action = 'EXPIRE'
                """;
        List<Map<String, Object>> req2Logs = jdbcTemplate.queryForList(expireAuditSql, req2.getId().toString());
        assertEquals(1, req2Logs.size(), "Đúng 1 dòng audit log EXPIRE cho đơn req2");

        Map<String, Object> req2Log = req2Logs.get(0);
        assertEquals("ACCESS_REQUEST", req2Log.get("target_type"));
        assertEquals("EXPIRE", req2Log.get("action"));
        assertEquals("SYSTEM", req2Log.get("actor_type"));
        assertEquals("EXPIRE_OVERDUE_REQUESTS_JOB", req2Log.get("actor_source"));
        assertNull(req2Log.get("changed_by"), "changed_by phải là NULL đối với actor_type SYSTEM");
        assertNotNull(req2Log.get("correlation_id"), "correlation_id không được null");
        assertEquals("ACCESS_REQUEST", req2Log.get("module"));

        // 5. Kiểm tra audit_logs cho req1 (đơn đã huỷ): không được có dòng EXPIRE
        List<Map<String, Object>> req1Logs = jdbcTemplate.queryForList(expireAuditSql, req1.getId().toString());
        assertEquals(0, req1Logs.size(), "Đơn đã huỷ không được có dòng EXPIRE trong audit_logs");
    }
}
