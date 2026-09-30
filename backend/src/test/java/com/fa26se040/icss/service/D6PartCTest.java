package com.fa26se040.icss.service;

import com.fa26se040.icss.context.AuditContext;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AccessRequestSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAccessRulesAuditSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaCamerasSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaGeometrySnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AreaSnapshot;
import com.fa26se040.icss.dto.accesscontrol.snapshot.AuditSnapshot;
import com.fa26se040.icss.dto.accessrequest.AccessRequestReviewRequest;
import com.fa26se040.icss.dto.accessrequest.GroupAccessRequestCreateRequest;
import com.fa26se040.icss.dto.accessrequest.IndividualAccessRequestCreateRequest;
import com.fa26se040.icss.dto.area.AreaCreateRequest;
import com.fa26se040.icss.dto.area.AreaGeometry;
import com.fa26se040.icss.dto.area.AreaUpdateRequest;
import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.AccessRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.AreaLevelPreset;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.RequestStatus;
import com.fa26se040.icss.enums.RequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AccessRequestRepository;
import com.fa26se040.icss.repository.AreaLevelPresetRepository;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.FloorRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.security.MemberLookupRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class D6PartCTest {

    @Mock
    private AreaRepository areaRepository;
    @Mock
    private CameraRepository cameraRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private AreaLevelPresetRepository areaLevelPresetRepository;
    @Mock
    private FloorRepository floorRepository;
    @Mock
    private AreaValidator areaValidator;
    @Mock
    private AreaDependencyChecker dependencyChecker;
    @Mock
    private AreaGeometryValidator geometryValidator;
    @Mock
    private AuditService auditService;
    @Mock
    private SystemConfigService systemConfigService;

    @InjectMocks
    private AreaService areaService;

    @Mock
    private AccessRequestRepository accessRequestRepository;
    @Mock
    private InAppNotificationService inAppNotificationService;
    @Mock
    private MemberLookupRateLimiter memberLookupRateLimiter;

    @InjectMocks
    private AccessRequestService accessRequestService;

    private User fmUser;
    private User studentUser;
    private Area testArea;
    private Floor testFloor;
    private Building testBuilding;

    @BeforeEach
    void setUp() {
        fmUser = User.builder()
                .id(UUID.randomUUID())
                .email("fm@fpt.edu.vn")
                .fullName("Facility Manager")
                .role(Role.FACILITY_MANAGER)
                .isActive(true)
                .build();

        studentUser = User.builder()
                .id(UUID.randomUUID())
                .email("student@fpt.edu.vn")
                .userCode("SV-001")
                .fullName("Nguyen Van A")
                .role(Role.NORMAL_USER)
                .accessLevel(3)
                .isActive(true)
                .build();

        testBuilding = Building.builder()
                .id(UUID.randomUUID())
                .code("ALPHA")
                .name("Alpha Building")
                .build();

        testFloor = Floor.builder()
                .id(UUID.randomUUID())
                .floorCode("L1")
                .building(testBuilding)
                .build();

        testArea = Area.builder()
                .id(UUID.randomUUID())
                .name("Room 101")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .areaAccessLevel(2)
                .explicitAuthorizationRequired(false)
                .building("ALPHA")
                .floor("L1")
                .floorEntity(testFloor)
                .isActive(true)
                .build();

        when(userRepository.findByEmail("fm@fpt.edu.vn")).thenReturn(Optional.of(fmUser));
        when(userRepository.findByEmail("student@fpt.edu.vn")).thenReturn(Optional.of(studentUser));
    }

    // ==========================================
    // D6-05: 12 cases audit logging
    // ==========================================

    @Test
    @DisplayName("D6-05 Case 1: AreaService.create ghi audit AREA CREATE")
    void testD6_05_AreaCreate() {
        AreaCreateRequest req = AreaCreateRequest.builder()
                .name("Room 101")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .floorId(testFloor.getId())
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .build();

        when(areaValidator.validateAndNormalizeName("Room 101")).thenReturn("Room 101");
        when(floorRepository.findById(testFloor.getId())).thenReturn(Optional.of(testFloor));
        when(areaRepository.existsByFloorIdAndNameIgnoreCase(testFloor.getId(), "Room 101")).thenReturn(false);
        when(areaRepository.saveAndFlush(any(Area.class))).thenAnswer(inv -> {
            Area a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        areaService.create(req, "fm@fpt.edu.vn");

        verify(auditService).record(
                eq(AuditTargetType.AREA),
                eq(AuditAction.CREATE),
                any(String.class),
                any(Area.class),
                isNull(),
                isNull(),
                any(AreaSnapshot.class),
                isNull(),
                eq(fmUser)
        );
    }

    @Test
    @DisplayName("D6-05 Case 2: AreaService.update ghi audit AREA UPDATE")
    void testD6_05_AreaUpdate() {
        AreaUpdateRequest req = AreaUpdateRequest.builder()
                .name("Room 101 Updated")
                .areaLevel(AreaLevel.INTERNAL_CONFIDENTIAL)
                .floorId(testFloor.getId())
                .centerLatitude(10.8418)
                .centerLongitude(106.8100)
                .version(0L)
                .build();

        when(areaRepository.findByIdWithLock(testArea.getId())).thenReturn(Optional.of(testArea));
        when(areaValidator.validateAndNormalizeName("Room 101 Updated")).thenReturn("Room 101 Updated");
        when(floorRepository.findById(testFloor.getId())).thenReturn(Optional.of(testFloor));
        when(areaRepository.existsByFloorIdAndNameIgnoreCaseExcludingId(testArea.getId(), testFloor.getId(), "Room 101 Updated"))
                .thenReturn(false);
        when(areaRepository.saveAndFlush(any(Area.class))).thenReturn(testArea);

        areaService.update(testArea.getId(), req, "fm@fpt.edu.vn");

        verify(auditService).record(
                eq(AuditTargetType.AREA),
                eq(AuditAction.UPDATE),
                eq(testArea.getId().toString()),
                eq(testArea),
                isNull(),
                any(AreaSnapshot.class),
                any(AreaSnapshot.class),
                isNull(),
                eq(fmUser)
        );
    }

    @Test
    @DisplayName("D6-05 Case 3: AreaService.saveGeometry ghi audit AREA UPDATE_GEOMETRY")
    void testD6_05_AreaSaveGeometry() {
        when(areaRepository.findByIdWithLock(testArea.getId())).thenReturn(Optional.of(testArea));
        when(areaRepository.findByBuildingIgnoreCaseAndFloorIgnoreCaseAndDeletedAtIsNull(any(), any()))
                .thenReturn(List.of());
        when(areaRepository.save(any(Area.class))).thenReturn(testArea);

        AreaGeometry geom = AreaGeometry.builder()
                .type("polygon")
                .version(1)
                .vertices(List.of(new AreaGeometry.Vertex(new BigDecimal("0.1"), new BigDecimal("0.2"))))
                .build();

        areaService.saveGeometry(testArea.getId(), geom, 0L, "fm@fpt.edu.vn");

        verify(auditService).record(
                eq(AuditTargetType.AREA),
                eq(AuditAction.UPDATE_GEOMETRY),
                eq(testArea.getId().toString()),
                eq(testArea),
                isNull(),
                nullable(AreaGeometrySnapshot.class),
                any(AreaGeometrySnapshot.class),
                isNull(),
                eq(fmUser)
        );
    }

    @Test
    @DisplayName("D6-05 Case 4: AreaService.deleteGeometry ghi audit AREA DELETE_GEOMETRY")
    void testD6_05_AreaDeleteGeometry() {
        testArea.setGeometry(AreaGeometry.builder().type("polygon").version(1).build());
        when(areaRepository.findByIdWithLock(testArea.getId())).thenReturn(Optional.of(testArea));
        when(areaRepository.save(any(Area.class))).thenReturn(testArea);

        areaService.deleteGeometry(testArea.getId(), 0L, "fm@fpt.edu.vn");

        verify(auditService).record(
                eq(AuditTargetType.AREA),
                eq(AuditAction.DELETE_GEOMETRY),
                eq(testArea.getId().toString()),
                eq(testArea),
                isNull(),
                any(AreaGeometrySnapshot.class),
                isNull(),
                isNull(),
                eq(fmUser)
        );
    }

    @Test
    @DisplayName("D6-05 Case 5: AreaService.deactivate ghi audit AREA DEACTIVATE")
    void testD6_05_AreaDeactivate() {
        when(areaRepository.findByIdWithLock(testArea.getId())).thenReturn(Optional.of(testArea));
        when(dependencyChecker.evaluate(eq(testArea), any())).thenReturn(new AreaDependencyChecker.DeactivationEvaluation(
                List.of(), List.of(), List.of(), List.of(), List.of()));
        when(areaRepository.save(any(Area.class))).thenReturn(testArea);

        areaService.deactivate(testArea.getId(),
                new com.fa26se040.icss.dto.area.AreaDeactivateRequest("Khu vực ngừng sử dụng để sửa chữa", testArea.getVersion()),
                "fm@fpt.edu.vn");

        verify(auditService).record(
                eq(AuditTargetType.AREA),
                eq(AuditAction.DEACTIVATE),
                eq(testArea.getId().toString()),
                eq(testArea),
                isNull(),
                any(AreaSnapshot.class),
                any(AreaSnapshot.class),
                eq("Khu vực ngừng sử dụng để sửa chữa"),
                eq(fmUser)
        );
    }

    @Test
    @DisplayName("D6-05 Case 6: AreaService.updateCamerasForArea ghi audit AREA UPDATE_CAMERAS")
    void testD6_05_AreaUpdateCameras() {
        when(areaRepository.findByIdWithLock(testArea.getId())).thenReturn(Optional.of(testArea));
        when(areaRepository.findByIdAndDeletedAtIsNull(testArea.getId())).thenReturn(Optional.of(testArea));
        UUID camId1 = UUID.randomUUID();
        UUID camId2 = UUID.randomUUID();
        Camera cam1 = Camera.builder().id(camId1).status(CameraStatus.ACTIVE).area(testArea).build();
        Camera cam2 = Camera.builder().id(camId2).status(CameraStatus.ACTIVE).build();

        when(cameraRepository.findByAreaIdAndDeletedAtIsNull(testArea.getId())).thenReturn(List.of(cam1));
        when(cameraRepository.findAllById(List.of(cam2.getId()))).thenReturn(List.of(cam2));

        areaService.updateCamerasForArea(testArea.getId(), List.of(cam2.getId()));

        verify(auditService).record(
                eq(AuditTargetType.AREA),
                eq(AuditAction.UPDATE_CAMERAS),
                eq(testArea.getId().toString()),
                eq(testArea),
                isNull(),
                any(AreaCamerasSnapshot.class),
                any(AreaCamerasSnapshot.class),
                isNull()
        );
    }

    @Test
    @DisplayName("D6-05 Case 7: AccessRequestService.createIndividualRequest ghi audit ACCESS_REQUEST CREATE")
    void testD6_05_AccessRequestCreateIndividual() {
        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = start.plusHours(2);
        IndividualAccessRequestCreateRequest req = new IndividualAccessRequestCreateRequest(
                testArea.getId(), start, end, "Ly do cong viec"
        );

        when(areaRepository.findByIdAndDeletedAtIsNull(testArea.getId())).thenReturn(Optional.of(testArea));
        when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_PAST_START_BUFFER_MINUTES)).thenReturn(5);
        when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_ADVANCE_DAYS)).thenReturn(30);
        when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_DURATION_HOURS)).thenReturn(24);
        when(accessRequestRepository.findOverlappingRequests(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(accessRequestRepository.save(any(AccessRequest.class))).thenAnswer(inv -> {
            AccessRequest ar = inv.getArgument(0);
            ar.setId(UUID.randomUUID());
            return ar;
        });

        accessRequestService.createIndividualRequest(req, "student@fpt.edu.vn");

        verify(auditService).record(
                eq(AuditTargetType.ACCESS_REQUEST),
                eq(AuditAction.CREATE),
                any(String.class),
                eq(testArea),
                eq(studentUser),
                isNull(),
                any(AccessRequestSnapshot.class),
                isNull(),
                eq(studentUser)
        );
    }

    @Test
    @DisplayName("D6-05 Case 8: AccessRequestService.createGroupRequest ghi audit ACCESS_REQUEST CREATE")
    void testD6_05_AccessRequestCreateGroup() {
        User student2 = User.builder()
                .id(UUID.randomUUID())
                .email("student2@fpt.edu.vn")
                .userCode("SV-002")
                .fullName("Nguyen Van B")
                .role(Role.NORMAL_USER)
                .accessLevel(3)
                .isActive(true)
                .build();

        OffsetDateTime start = OffsetDateTime.now().plusHours(1);
        OffsetDateTime end = start.plusHours(2);
        GroupAccessRequestCreateRequest req = new GroupAccessRequestCreateRequest(
                testArea.getId(), start, end, "Hoc nhom", List.of("SV-002")
        );

        when(areaRepository.findByIdAndDeletedAtIsNull(testArea.getId())).thenReturn(Optional.of(testArea));
        when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_PAST_START_BUFFER_MINUTES)).thenReturn(5);
        when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_ADVANCE_DAYS)).thenReturn(30);
        when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_DURATION_HOURS)).thenReturn(24);
        when(systemConfigService.getInt(ConfigKey.ACCESS_REQUEST_MAX_GROUP_MEMBERS)).thenReturn(10);
        when(userRepository.findByUserCode("SV-002")).thenReturn(Optional.of(student2));
        when(accessRequestRepository.findOverlappingRequests(any(), any(), any(), any(), any())).thenReturn(List.of());
        when(accessRequestRepository.save(any(AccessRequest.class))).thenAnswer(inv -> {
            AccessRequest ar = inv.getArgument(0);
            ar.setId(UUID.randomUUID());
            return ar;
        });

        accessRequestService.createGroupRequest(req, "student@fpt.edu.vn");

        verify(auditService).record(
                eq(AuditTargetType.ACCESS_REQUEST),
                eq(AuditAction.CREATE),
                any(String.class),
                eq(testArea),
                eq(studentUser),
                isNull(),
                any(AccessRequestSnapshot.class),
                isNull(),
                eq(studentUser)
        );
    }

    @Test
    @DisplayName("D6-05 Case 9: AccessRequestService.reviewRequest (APPROVE) ghi audit ACCESS_REQUEST APPROVE")
    void testD6_05_AccessRequestApprove() {
        UUID reqId = UUID.randomUUID();
        AccessRequest pendingReq = AccessRequest.builder()
                .id(reqId)
                .area(testArea)
                .requester(studentUser)
                .status(RequestStatus.PENDING)
                .requestType(RequestType.INDIVIDUAL)
                .members(new ArrayList<>())
                .build();

        AccessRequest approvedReq = AccessRequest.builder()
                .id(reqId)
                .area(testArea)
                .requester(studentUser)
                .status(RequestStatus.APPROVED)
                .reviewer(fmUser)
                .requestType(RequestType.INDIVIDUAL)
                .members(new ArrayList<>())
                .build();

        when(accessRequestRepository.findByIdWithDetails(reqId))
                .thenReturn(Optional.of(pendingReq))
                .thenReturn(Optional.of(approvedReq));
        when(accessRequestRepository.reviewIfPending(eq(reqId), eq(RequestStatus.APPROVED), eq(fmUser), any(), isNull(), eq(RequestStatus.PENDING)))
                .thenReturn(1);

        AccessRequestReviewRequest reviewReq = new AccessRequestReviewRequest(RequestStatus.APPROVED, null);
        accessRequestService.reviewRequest(reqId, reviewReq, "fm@fpt.edu.vn");

        verify(auditService).record(
                eq(AuditTargetType.ACCESS_REQUEST),
                eq(AuditAction.APPROVE),
                eq(reqId.toString()),
                eq(testArea),
                eq(studentUser),
                any(AccessRequestSnapshot.class),
                any(AccessRequestSnapshot.class),
                isNull(),
                eq(fmUser)
        );
    }

    @Test
    @DisplayName("D6-05 Case 10: AccessRequestService.reviewRequest (REJECT) ghi audit ACCESS_REQUEST REJECT với lý do")
    void testD6_05_AccessRequestReject() {
        UUID reqId = UUID.randomUUID();
        AccessRequest pendingReq = AccessRequest.builder()
                .id(reqId)
                .area(testArea)
                .requester(studentUser)
                .status(RequestStatus.PENDING)
                .requestType(RequestType.INDIVIDUAL)
                .members(new ArrayList<>())
                .build();

        AccessRequest rejectedReq = AccessRequest.builder()
                .id(reqId)
                .area(testArea)
                .requester(studentUser)
                .status(RequestStatus.REJECTED)
                .reviewer(fmUser)
                .rejectionReason("Khu vuc dang sua chua")
                .requestType(RequestType.INDIVIDUAL)
                .members(new ArrayList<>())
                .build();

        when(accessRequestRepository.findByIdWithDetails(reqId))
                .thenReturn(Optional.of(pendingReq))
                .thenReturn(Optional.of(rejectedReq));
        when(accessRequestRepository.reviewIfPending(eq(reqId), eq(RequestStatus.REJECTED), eq(fmUser), any(), eq("Khu vuc dang sua chua"), eq(RequestStatus.PENDING)))
                .thenReturn(1);

        AccessRequestReviewRequest reviewReq = new AccessRequestReviewRequest(RequestStatus.REJECTED, "Khu vuc dang sua chua");
        accessRequestService.reviewRequest(reqId, reviewReq, "fm@fpt.edu.vn");

        verify(auditService).record(
                eq(AuditTargetType.ACCESS_REQUEST),
                eq(AuditAction.REJECT),
                eq(reqId.toString()),
                eq(testArea),
                eq(studentUser),
                any(AccessRequestSnapshot.class),
                any(AccessRequestSnapshot.class),
                eq("Khu vuc dang sua chua"),
                eq(fmUser)
        );
    }

    @Test
    @DisplayName("D6-05 Case 11: AccessRequestService.cancelRequest ghi audit ACCESS_REQUEST CANCEL")
    void testD6_05_AccessRequestCancel() {
        UUID reqId = UUID.randomUUID();
        AccessRequest pendingReq = AccessRequest.builder()
                .id(reqId)
                .area(testArea)
                .requester(studentUser)
                .status(RequestStatus.PENDING)
                .requestType(RequestType.INDIVIDUAL)
                .members(new ArrayList<>())
                .build();

        AccessRequest cancelledReq = AccessRequest.builder()
                .id(reqId)
                .area(testArea)
                .requester(studentUser)
                .status(RequestStatus.CANCELLED)
                .requestType(RequestType.INDIVIDUAL)
                .members(new ArrayList<>())
                .build();

        when(accessRequestRepository.findByIdWithDetails(reqId))
                .thenReturn(Optional.of(pendingReq))
                .thenReturn(Optional.of(cancelledReq));
        when(accessRequestRepository.cancelIfPending(eq(reqId), eq(RequestStatus.CANCELLED), any(), eq(RequestStatus.PENDING)))
                .thenReturn(1);

        accessRequestService.cancelRequest(reqId, "student@fpt.edu.vn");

        verify(auditService).record(
                eq(AuditTargetType.ACCESS_REQUEST),
                eq(AuditAction.CANCEL),
                eq(reqId.toString()),
                eq(testArea),
                eq(studentUser),
                any(AccessRequestSnapshot.class),
                any(AccessRequestSnapshot.class),
                isNull(),
                eq(studentUser)
        );
    }

    @Test
    @DisplayName("D6-05 Case 12: AccessRequestService.finishRequest ghi audit ACCESS_REQUEST FINISH")
    void testD6_05_AccessRequestFinish() {
        UUID reqId = UUID.randomUUID();
        AccessRequest approvedReq = AccessRequest.builder()
                .id(reqId)
                .area(testArea)
                .requester(studentUser)
                .status(RequestStatus.APPROVED)
                .requestType(RequestType.INDIVIDUAL)
                .members(new ArrayList<>())
                .build();

        AccessRequest finishedReq = AccessRequest.builder()
                .id(reqId)
                .area(testArea)
                .requester(studentUser)
                .status(RequestStatus.FINISHED)
                .requestType(RequestType.INDIVIDUAL)
                .members(new ArrayList<>())
                .build();

        when(accessRequestRepository.findByIdWithDetails(reqId)).thenReturn(Optional.of(approvedReq));
        when(accessRequestRepository.save(any(AccessRequest.class))).thenReturn(finishedReq);

        accessRequestService.finishRequest(reqId, "student@fpt.edu.vn");

        verify(auditService).record(
                eq(AuditTargetType.ACCESS_REQUEST),
                eq(AuditAction.FINISH),
                eq(reqId.toString()),
                eq(testArea),
                eq(studentUser),
                any(AccessRequestSnapshot.class),
                any(AccessRequestSnapshot.class),
                isNull(),
                eq(studentUser)
        );
    }

    // ==========================================
    // D6-06: Expire overdue job test
    // ==========================================

    @Test
    @DisplayName("D6-06: Expire job chỉ log request thực tế bị expire; actor = SYSTEM; chung 1 correlationId")
    void testD6_06_ExpireOverdueJob() {
        AccessRequest req1 = AccessRequest.builder()
                .id(UUID.randomUUID())
                .area(testArea)
                .requester(studentUser)
                .status(RequestStatus.PENDING)
                .startTime(OffsetDateTime.now().minusHours(2))
                .endTime(OffsetDateTime.now().minusHours(1))
                .build();

        AccessRequest req2 = AccessRequest.builder()
                .id(UUID.randomUUID())
                .area(testArea)
                .requester(studentUser)
                .status(RequestStatus.PENDING)
                .startTime(OffsetDateTime.now().minusHours(3))
                .endTime(OffsetDateTime.now().minusHours(2))
                .build();

        when(accessRequestRepository.findPendingOverdueRequestsForUpdate(eq(RequestStatus.PENDING), any()))
                .thenReturn(List.of(req1, req2));
        when(accessRequestRepository.expireOverdueRequestsByIds(anyCollection(), eq(RequestStatus.PENDING), eq(RequestStatus.EXPIRED), any()))
                .thenReturn(2);

        // Capture correlation_id and actor inside record
        List<UUID> capturedCorrelationIds = new ArrayList<>();
        List<AuditActor> capturedActors = new ArrayList<>();

        when(auditService.record(
                eq(AuditTargetType.ACCESS_REQUEST),
                eq(AuditAction.EXPIRE),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        )).thenAnswer(inv -> {
            capturedCorrelationIds.add(AuditContext.getCorrelationId());
            capturedActors.add(AuditContext.getCurrentActor());
            return null;
        });

        int result = accessRequestService.expireOverdueRequests();

        assertEquals(2, result);
        assertEquals(2, capturedCorrelationIds.size());
        assertNotNull(capturedCorrelationIds.get(0));
        assertEquals(capturedCorrelationIds.get(0), capturedCorrelationIds.get(1),
                "Cả 2 audit log phải dùng chung 1 correlation_id");

        assertEquals(2, capturedActors.size());
        assertEquals("SYSTEM", capturedActors.get(0).getActorType());
        assertEquals("EXPIRE_OVERDUE_REQUESTS_JOB", capturedActors.get(0).getActorSource());
        assertNull(capturedActors.get(0).getUser());
    }

    @Test
    @DisplayName("D6-15: Bước khoá đơn quá hạn lỗi -> ném lỗi, không update, không ghi audit")
    void testD6_15_ExpireJob_LockQueryFails_NoUpdateNoAudit() {
        when(accessRequestRepository.findPendingOverdueRequestsForUpdate(eq(RequestStatus.PENDING), any()))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout"));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.dao.CannotAcquireLockException.class,
                () -> accessRequestService.expireOverdueRequests());

        verify(accessRequestRepository, never()).expireOverdueRequestsByIds(anyCollection(), any(), any(), any());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("D6-16: Số đơn update khác số đơn đã khoá -> ném lỗi (rollback), không ghi audit")
    void testD6_16_ExpireJob_CountMismatch_Throws() {
        AccessRequest req1 = AccessRequest.builder()
                .id(UUID.randomUUID()).area(testArea).requester(studentUser).status(RequestStatus.PENDING)
                .startTime(OffsetDateTime.now().minusHours(2)).endTime(OffsetDateTime.now().minusHours(1)).build();
        AccessRequest req2 = AccessRequest.builder()
                .id(UUID.randomUUID()).area(testArea).requester(studentUser).status(RequestStatus.PENDING)
                .startTime(OffsetDateTime.now().minusHours(3)).endTime(OffsetDateTime.now().minusHours(2)).build();
        when(accessRequestRepository.findPendingOverdueRequestsForUpdate(eq(RequestStatus.PENDING), any()))
                .thenReturn(List.of(req1, req2));
        when(accessRequestRepository.expireOverdueRequestsByIds(anyCollection(), eq(RequestStatus.PENDING), eq(RequestStatus.EXPIRED), any()))
                .thenReturn(1);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> accessRequestService.expireOverdueRequests());
        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("D6-06b: Expire job khi 0 request quá hạn thì không log audit")
    void testD6_06_ExpireOverdueJob_ZeroOverdue() {
        when(accessRequestRepository.findPendingOverdueRequestsForUpdate(eq(RequestStatus.PENDING), any()))
                .thenReturn(List.of());

        int result = accessRequestService.expireOverdueRequests();

        assertEquals(0, result);
        verify(auditService, never()).record(
                eq(AuditTargetType.ACCESS_REQUEST),
                eq(AuditAction.EXPIRE),
                any(), any(), any(), any(), any(), any()
        );
    }

    // ==========================================
    // D6-12: Snapshot Reflection Test
    // ==========================================

    @Test
    @DisplayName("D6-12: Phản chiếu assert không có snapshot record nào chứa sensitive fields (password, token, secret, embedding, photo, image, face)")
    void testD6_12_NoSensitiveFieldsInSnapshots() {
        List<Class<? extends AuditSnapshot>> snapshotClasses = List.of(
                AreaSnapshot.class,
                AreaGeometrySnapshot.class,
                AreaCamerasSnapshot.class,
                AccessRequestSnapshot.class,
                AreaAccessRulesAuditSnapshot.class,
                com.fa26se040.icss.dto.accesscontrol.snapshot.AreaAssignmentAuditSnapshot.class,
                com.fa26se040.icss.dto.accesscontrol.snapshot.AreaEventModeAuditSnapshot.class,
                com.fa26se040.icss.dto.accesscontrol.snapshot.LevelPresetAuditSnapshot.class,
                com.fa26se040.icss.dto.accesscontrol.snapshot.ReasonCatalogAuditSnapshot.class,
                com.fa26se040.icss.dto.accesscontrol.snapshot.UserAccessLevelAuditSnapshot.class
        );

        List<String> forbiddenKeywords = List.of(
                "password", "token", "secret", "embedding", "image", "photo", "face"
        );

        for (Class<? extends AuditSnapshot> clazz : snapshotClasses) {
            assertTrue(clazz.isRecord(), clazz.getSimpleName() + " phải là một Java record");

            for (RecordComponent comp : clazz.getRecordComponents()) {
                String fieldName = comp.getName().toLowerCase();
                for (String keyword : forbiddenKeywords) {
                    assertFalse(
                            fieldName.contains(keyword),
                            String.format("Snapshot record %s chứa trường nhạy cảm: '%s' (khớp từ khóa '%s')",
                                    clazz.getSimpleName(), comp.getName(), keyword)
                    );
                }
            }

            for (Field field : clazz.getDeclaredFields()) {
                String fieldName = field.getName().toLowerCase();
                for (String keyword : forbiddenKeywords) {
                    assertFalse(
                            fieldName.contains(keyword),
                            String.format("Snapshot record %s chứa trường nhạy cảm: '%s' (khớp từ khóa '%s')",
                                    clazz.getSimpleName(), field.getName(), keyword)
                    );
                }
            }
        }
    }
}
