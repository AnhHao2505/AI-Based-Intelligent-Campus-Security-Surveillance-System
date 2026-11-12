package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.incident.IncidentClaimResponse;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.dto.incident.IncidentResolveRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.IncidentEscalation;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.IncidentEscalationRepository;
import com.fa26se040.icss.repository.OnDutyGuardQueryRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SecurityIncidentServiceTest {

    @Mock
    private SecurityIncidentRepository incidentRepository;

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private CameraAccessLogService cameraAccessLogService;

    @Mock
    private GuardShiftRepository guardShiftRepository;

    @Mock
    private IncidentRecipientResolver incidentRecipientResolver;

    @Mock
    private IncidentEscalationRepository incidentEscalationRepository;
    @Mock
    private com.fa26se040.icss.config.AiServiceRestTemplate restTemplate;

    @InjectMocks
    private SecurityIncidentService incidentService;

    private User guardUser;
    private Area area;
    private SecurityIncident incident;

    @BeforeEach
    void setUp() {
        guardUser = User.builder()
                .id(UUID.randomUUID())
                .fullName("Trần Văn Bảo")
                .email("guard.bao@fpt.edu.vn")
                .role(Role.GUARD)
                .build();

        area = Area.builder()
                .id(UUID.randomUUID())
                .name("Sảnh Tòa Alpha")
                .building("TOA_ALPHA")
                .build();

        incident = SecurityIncident.builder()
                .id(UUID.randomUUID())
                .cameraCode("CAM-001")
                .area(area)
                .building("TOA_ALPHA")
                .eventType("UNAUTHORIZED_ENTRY")
                .status(IncidentStatus.NEW)
                .version(0)
                .detectedAt(OffsetDateTime.now())
                .build();
    }

    @Test
    @DisplayName("Ingest sự cố từ AI và đẩy WebSocket tới topic tòa nhà thành công")
    void testIngestIncident_SuccessAndDispatchesToWebSocket() {
        IncidentEventDto eventDto = IncidentEventDto.builder()
                .eventId("EVT-100")
                .cameraCode("CAM-001")
                .eventType("UNAUTHORIZED_ENTRY")
                .imageUrl("http://minio:9000/security-evidence/snap.jpg")
                .build();

        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-001"))
                .thenReturn(Optional.of(Camera.builder().cameraCode("CAM-001").area(area).build()));
        when(incidentRepository.save(any())).thenReturn(incident);

        IncidentDetailResponse result = incidentService.ingestIncident(eventDto);

        assertNotNull(result);
        assertEquals("CAM-001", result.getCameraCode());
        assertEquals("TOA_ALPHA", result.getBuilding());
        // Verify WebSocket dispatch
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/buildings/TOA_ALPHA/alerts"), any(Object.class));
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/security-alerts"), any(Object.class));
    }

    @Test
    @DisplayName("Ingest sự cố ném lỗi IllegalArgumentException khi camera chưa được gán khu vực")
    void testIngestIncident_ThrowsException_WhenCameraHasNoArea() {
        IncidentEventDto eventDto = IncidentEventDto.builder()
                .eventId("EVT-101")
                .cameraCode("CAM-UNASSIGNED")
                .eventType("UNAUTHORIZED_ENTRY")
                .imageUrl("http://minio:9000/security-evidence/snap.jpg")
                .build();

        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-UNASSIGNED"))
                .thenReturn(Optional.of(Camera.builder().cameraCode("CAM-UNASSIGNED").area(null).build()));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> incidentService.ingestIncident(eventDto));

        assertTrue(ex.getMessage().contains("chưa được gán vào khu vực hoạt động"));
        verify(incidentRepository, never()).save(any());
    }

    // ===== BR-IE-01 / BR-IE-02 / BR-IE-03: gửi bảo vệ đang trực + tạo bản ghi leo thang =====

    private static final ZoneId VN_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");

    private IncidentEventDto alertEvent() {
        return IncidentEventDto.builder()
                .eventId("EVT-IE")
                .cameraCode("CAM-001")
                .eventType("UNAUTHORIZED_ENTRY")
                .imageUrl("http://minio:9000/security-evidence/snap.jpg")
                .build();
    }

    private void stubCameraAndSave() {
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-001"))
                .thenReturn(Optional.of(Camera.builder().cameraCode("CAM-001").area(area).build()));
        when(incidentRepository.save(any())).thenReturn(incident);
    }

    private static User guard(String name) {
        return User.builder().id(UUID.randomUUID()).fullName(name).email(UUID.randomUUID() + "@fpt.edu.vn").role(Role.GUARD).build();
    }

    /** Ca CHECKED_IN từ start tới end (giờ Việt Nam); qua nửa đêm thì end &lt; start như dữ liệu thật. */
    private static GuardShift checkedInShift(User guard, LocalDateTime start, LocalDateTime end) {
        return GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard)
                .shiftDate(start.toLocalDate())
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(start.toLocalTime())
                .endTime(end.toLocalTime())
                .status(ShiftStatus.CHECKED_IN)
                .build();
    }

    private static String guardTopic(User guard) {
        return "/topic/guards/" + guard.getId() + "/alerts";
    }

    @Test
    @DisplayName("BR-IE-01: gửi kênh riêng của từng bảo vệ đang trực, payload giữ nguyên")
    void ingest_dispatchesToEveryOnDutyGuardTopic() {
        stubCameraAndSave();
        User guardA = guard("Bảo vệ A");
        User guardB = guard("Bảo vệ B");
        when(incidentRecipientResolver.findOnDutyGuards(any())).thenReturn(List.of(guardA, guardB));

        IncidentDetailResponse result = incidentService.ingestIncident(alertEvent());

        verify(messagingTemplate).convertAndSend(eq(guardTopic(guardA)), same((Object) result));
        verify(messagingTemplate).convertAndSend(eq(guardTopic(guardB)), same((Object) result));
    }

    @Test
    @DisplayName("BR-IE-03: tạo đúng 1 bản ghi leo thang, guard_recipient_count = số bảo vệ đã gửi")
    void ingest_createsOneEscalationWithRecipientCount() {
        stubCameraAndSave();
        when(incidentRecipientResolver.findOnDutyGuards(any())).thenReturn(List.of(guard("A"), guard("B")));

        incidentService.ingestIncident(alertEvent());

        ArgumentCaptor<IncidentEscalation> captor = ArgumentCaptor.forClass(IncidentEscalation.class);
        verify(incidentEscalationRepository, times(1)).save(captor.capture());
        IncidentEscalation escalation = captor.getValue();
        assertSame(incident, escalation.getIncident());
        assertEquals(2, escalation.getGuardRecipientCount());
        assertNotNull(escalation.getGuardsNotifiedAt());
        assertNull(escalation.getClosedAt());
        assertNull(escalation.getFmEscalatedAt());
    }

    @Test
    @DisplayName("BR-IE-02: bảo vệ hết ca (quên trả ca) -> KHÔNG nhận")
    void ingest_guardOffShift_notNotified() {
        stubCameraAndSave();
        User onDuty = guard("Bảo vệ đang trực");
        User offShift = guard("Bảo vệ hết ca");
        LocalDateTime nowVn = LocalDateTime.now(VN_ZONE);
        OnDutyGuardQueryRepository shiftQuery = mock(OnDutyGuardQueryRepository.class);
        when(shiftQuery.findByStatusAndShiftDateBetween(eq(ShiftStatus.CHECKED_IN), any(), any())).thenReturn(List.of(
                checkedInShift(onDuty, nowVn.minusHours(1), nowVn.plusHours(1)),
                checkedInShift(offShift, nowVn.minusHours(3), nowVn.minusHours(1))));
        SecurityIncidentService service = new SecurityIncidentService(incidentRepository, cameraRepository, userRepository,
                messagingTemplate, cameraAccessLogService, guardShiftRepository,
                new IncidentRecipientResolver(shiftQuery), incidentEscalationRepository, mock(IncidentSeverityResolver.class), null);

        IncidentDetailResponse result = service.ingestIncident(alertEvent());

        verify(messagingTemplate).convertAndSend(eq(guardTopic(onDuty)), same((Object) result));
        verify(messagingTemplate, never()).convertAndSend(eq(guardTopic(offShift)), any(Object.class));
        ArgumentCaptor<IncidentEscalation> captor = ArgumentCaptor.forClass(IncidentEscalation.class);
        verify(incidentEscalationRepository).save(captor.capture());
        assertEquals(1, captor.getValue().getGuardRecipientCount());
    }

    @Test
    @DisplayName("BR-IE-01: topic toà nhà và /topic/security-alerts vẫn gửi như cũ")
    void ingest_buildingAndGlobalTopicsUnchanged() {
        stubCameraAndSave();
        when(incidentRecipientResolver.findOnDutyGuards(any())).thenReturn(List.of(guard("A")));

        IncidentDetailResponse result = incidentService.ingestIncident(alertEvent());

        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/buildings/TOA_ALPHA/alerts"), same((Object) result));
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/security-alerts"), same((Object) result));
    }

    @Test
    @DisplayName("Không có bảo vệ đang trực -> vẫn tạo bản ghi leo thang với 0 người nhận (BR-IE-05)")
    void ingest_noOnDutyGuard_escalationWithZeroRecipients() {
        stubCameraAndSave();
        when(incidentRecipientResolver.findOnDutyGuards(any())).thenReturn(List.of());

        incidentService.ingestIncident(alertEvent());

        ArgumentCaptor<IncidentEscalation> captor = ArgumentCaptor.forClass(IncidentEscalation.class);
        verify(incidentEscalationRepository).save(captor.capture());
        assertEquals(0, captor.getValue().getGuardRecipientCount());
        verify(messagingTemplate, never()).convertAndSend(startsWith("/topic/guards/"), any(Object.class));
    }

    @Test
    @DisplayName("Resolver lỗi -> sự cố vẫn tạo, bản ghi leo thang 0 người nhận để FM nhận ở lần chạy kế")
    void ingest_resolverFails_incidentStillCreated() {
        stubCameraAndSave();
        when(incidentRecipientResolver.findOnDutyGuards(any())).thenThrow(new IllegalStateException("resolver hỏng"));

        IncidentDetailResponse result = incidentService.ingestIncident(alertEvent());

        assertNotNull(result);
        ArgumentCaptor<IncidentEscalation> captor = ArgumentCaptor.forClass(IncidentEscalation.class);
        verify(incidentEscalationRepository).save(captor.capture());
        assertEquals(0, captor.getValue().getGuardRecipientCount());
        verify(messagingTemplate).convertAndSend(eq("/topic/security-alerts"), same((Object) result));
    }

    @Test
    @DisplayName("Lưu bản ghi leo thang lỗi (tầng Java) -> sự cố vẫn trả về, topic toà nhà / toàn cục vẫn gửi")
    void ingest_escalationSaveFails_incidentStillReturned() {
        stubCameraAndSave();
        when(incidentRecipientResolver.findOnDutyGuards(any())).thenReturn(List.of(guard("A")));
        when(incidentEscalationRepository.save(any())).thenThrow(new IllegalStateException("lưu lỗi"));

        IncidentDetailResponse result = incidentService.ingestIncident(alertEvent());

        assertNotNull(result);
        verify(messagingTemplate).convertAndSend(eq("/topic/buildings/TOA_ALPHA/alerts"), same((Object) result));
        verify(messagingTemplate).convertAndSend(eq("/topic/security-alerts"), same((Object) result));
    }

    // ===== Chống tạo trùng theo event_id =====

    @Test
    @DisplayName("Cùng event_id gửi 2 lần -> lần 2 trả sự cố cũ, không lưu, không gửi WebSocket, không tạo leo thang")
    void ingest_sameEventIdTwice_secondReturnsExistingWithoutSideEffects() {
        stubCameraAndSave();
        when(incidentRecipientResolver.findOnDutyGuards(any())).thenReturn(List.of(guard("A")));
        when(incidentRepository.findByEventId("EVT-IE")).thenReturn(Optional.empty(), Optional.of(incident));

        IncidentDetailResponse first = incidentService.ingestIncident(alertEvent());
        clearInvocations(incidentRepository, cameraRepository, messagingTemplate, cameraAccessLogService,
                incidentRecipientResolver, incidentEscalationRepository);

        IncidentDetailResponse second = incidentService.ingestIncident(alertEvent());

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(incident.getId(), second.getId());
        verify(incidentRepository).findByEventId("EVT-IE");
        verify(incidentRepository, never()).save(any());
        verify(incidentRepository, never()).flush();
        verifyNoInteractions(messagingTemplate, incidentEscalationRepository, incidentRecipientResolver,
                cameraRepository, cameraAccessLogService);
    }

    @Test
    @DisplayName("event_id null gửi 2 lần -> tạo 2 sự cố như cũ, không tra theo event_id")
    void ingest_nullEventIdTwice_createsTwoIncidents() {
        stubCameraAndSave();
        when(incidentRecipientResolver.findOnDutyGuards(any())).thenReturn(List.of(guard("A")));
        IncidentEventDto noEventId = alertEvent();
        noEventId.setEventId(null);

        incidentService.ingestIncident(noEventId);
        incidentService.ingestIncident(noEventId);

        verify(incidentRepository, never()).findByEventId(any());
        verify(incidentRepository, times(2)).save(any());
        verify(incidentEscalationRepository, times(2)).save(any());
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/security-alerts"), any(Object.class));
    }

    @Test
    @DisplayName("event_id rỗng (chỉ khoảng trắng) -> chạy như cũ, không tra theo event_id")
    void ingest_blankEventId_runsAsBefore() {
        stubCameraAndSave();
        IncidentEventDto blankEventId = alertEvent();
        blankEventId.setEventId("   ");

        IncidentDetailResponse result = incidentService.ingestIncident(blankEventId);

        assertNotNull(result);
        verify(incidentRepository, never()).findByEventId(any());
        verify(incidentRepository).save(any());
    }

    @Test
    @DisplayName("event_id mới -> lưu và flush ngay sau save, trước khi gửi WebSocket")
    void ingest_newEventId_flushesBeforeDispatch() {
        stubCameraAndSave();

        incidentService.ingestIncident(alertEvent());

        org.mockito.InOrder inOrder = inOrder(incidentRepository, messagingTemplate);
        inOrder.verify(incidentRepository).findByEventId("EVT-IE");
        inOrder.verify(incidentRepository).save(any());
        inOrder.verify(incidentRepository).flush();
        inOrder.verify(messagingTemplate).convertAndSend(eq("/topic/security-alerts"), any(Object.class));
    }

    @Test
    @DisplayName("Tiếp nhận (Claim) sự cố thành công khi còn trạng thái NEW")
    void testClaimIncident_Success() {
        when(incidentRepository.findById(incident.getId())).thenReturn(Optional.of(incident));
        when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));
        when(incidentRepository.saveAndFlush(any())).thenReturn(incident);

        IncidentClaimResponse result = incidentService.claimIncident(incident.getId(), guardUser.getEmail());

        assertNotNull(result);
        assertEquals(IncidentStatus.CLAIMED.name(), result.getStatus());
        assertEquals("Trần Văn Bảo", result.getClaimantName());
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/incidents/updates"), any(Object.class));
    }

    @Test
    @DisplayName("Tiếp nhận (Claim) thất bại nếu sự việc đã được người khác claim trước")
    void testClaimIncident_Fail_AlreadyClaimed() {
        User firstGuard = User.builder().id(UUID.randomUUID()).fullName("Lê Văn Nhất").build();
        incident.setStatus(IncidentStatus.CLAIMED);
        incident.setClaimedBy(firstGuard);

        when(incidentRepository.findById(incident.getId())).thenReturn(Optional.of(incident));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> incidentService.claimIncident(incident.getId(), guardUser.getEmail()));

        assertTrue(ex.getMessage().contains("Lê Văn Nhất"));
        verify(incidentRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("Giải quyết sự cố với kết quả VERIFIED (Xác thực vi phạm)")
    void testResolveIncident_Verified_Success() {
        incident.setStatus(IncidentStatus.CLAIMED);
        incident.setClaimedBy(guardUser);

        when(incidentRepository.findById(incident.getId())).thenReturn(Optional.of(incident));
        when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));
        when(incidentRepository.saveAndFlush(any())).thenReturn(incident);

        IncidentResolveRequest req = IncidentResolveRequest.builder()
                .outcome(IncidentOutcome.VERIFIED)
                .resolutionCategory(IncidentResolutionCategory.REMINDED_DISPERSED)
                .resolutionNotes("Đã nhắc nhở và giải tán sinh viên")
                .build();

        IncidentDetailResponse result = incidentService.resolveIncident(incident.getId(), req, guardUser.getEmail());

        assertNotNull(result);
        assertEquals(IncidentStatus.RESOLVED_VERIFIED, incident.getStatus());
        assertEquals(IncidentOutcome.VERIFIED, incident.getOutcome());
        assertEquals(IncidentResolutionCategory.REMINDED_DISPERSED, incident.getResolutionCategory());
        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/incidents/updates"), any(Object.class));
    }

    @Test
    @DisplayName("Giải quyết sự cố với kết quả DISMISSED (Bác bỏ / Báo động giả)")
    void testResolveIncident_Dismissed_Success() {
        incident.setStatus(IncidentStatus.CLAIMED);
        incident.setClaimedBy(guardUser);

        when(incidentRepository.findById(incident.getId())).thenReturn(Optional.of(incident));
        when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));
        when(incidentRepository.saveAndFlush(any())).thenReturn(incident);

        IncidentResolveRequest req = IncidentResolveRequest.builder()
                .outcome(IncidentOutcome.DISMISSED)
                .resolutionCategory(IncidentResolutionCategory.FALSE_ALARM)
                .resolutionNotes("Camera bị lá cây che tạo bóng")
                .build();

        IncidentDetailResponse result = incidentService.resolveIncident(incident.getId(), req, guardUser.getEmail());

        assertNotNull(result);
        assertEquals(IncidentStatus.RESOLVED_DISMISSED, incident.getStatus());
        assertEquals(IncidentOutcome.DISMISSED, incident.getOutcome());
        assertEquals(IncidentResolutionCategory.FALSE_ALARM, incident.getResolutionCategory());
    }

    @Test
    @DisplayName("Nhiều bảo vệ cùng bấm tiếp nhận 1 sự cố tại cùng thời điểm (Concurrent Race Condition)")
    void testClaimIncident_ConcurrentRaceCondition_OnlyOneGuardSucceeds() throws Exception {
        User guard2 = User.builder()
                .id(UUID.randomUUID())
                .fullName("Nguyễn Văn Hai")
                .email("guard.hai@fpt.edu.vn")
                .role(Role.GUARD)
                .build();

        lenient().when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));
        lenient().when(userRepository.findByEmail(guard2.getEmail())).thenReturn(Optional.of(guard2));

        when(incidentRepository.findById(incident.getId()))
                .thenReturn(Optional.of(incident));

        java.util.concurrent.atomic.AtomicBoolean claimed = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(incidentRepository.saveAndFlush(any())).thenAnswer(inv -> {
            if (!claimed.compareAndSet(false, true)) {
                throw new OptimisticLockingFailureException("Row was updated by another transaction");
            }
            return inv.getArgument(0);
        });

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger conflictCount = new java.util.concurrent.atomic.AtomicInteger(0);

        java.util.concurrent.Future<?> f1 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                incidentService.claimIncident(incident.getId(), guardUser.getEmail());
                successCount.incrementAndGet();
            } catch (IllegalStateException e) {
                conflictCount.incrementAndGet();
            } catch (Exception ignored) {}
        });

        java.util.concurrent.Future<?> f2 = executor.submit(() -> {
            readyLatch.countDown();
            try {
                startLatch.await();
                incidentService.claimIncident(incident.getId(), guard2.getEmail());
                successCount.incrementAndGet();
            } catch (IllegalStateException e) {
                conflictCount.incrementAndGet();
            } catch (Exception ignored) {}
        });

        readyLatch.await();
        startLatch.countDown(); // Bắn 2 luồng đồng thời
        f1.get();
        f2.get();
        executor.shutdown();

        assertEquals(1, successCount.get(), "Chỉ duy nhất 1 bảo vệ được phép tiếp nhận thành công");
        assertEquals(1, conflictCount.get(), "Bảo vệ thứ hai phải nhận lỗi Conflict vì sự cố đã có người nhận");
        assertEquals(IncidentStatus.CLAIMED, incident.getStatus());
        assertNotNull(incident.getClaimedBy());
    }

    @Test
    @DisplayName("OptimisticLockingFailureException khi 2 transaction ghi đè đồng thời -> Báo lỗi người tiếp nhận trước")
    void testClaimIncident_OptimisticLockingConflict() {
        when(userRepository.findByEmail(guardUser.getEmail())).thenReturn(Optional.of(guardUser));

        User firstWinner = User.builder().id(UUID.randomUUID()).fullName("Bảo vệ Đi Trước").build();
        SecurityIncident freshInDb = SecurityIncident.builder()
                .id(incident.getId())
                .status(IncidentStatus.CLAIMED)
                .claimedBy(firstWinner)
                .build();

        when(incidentRepository.findById(incident.getId()))
                .thenReturn(Optional.of(incident))
                .thenReturn(Optional.of(freshInDb));

        when(incidentRepository.saveAndFlush(any()))
                .thenThrow(new org.springframework.dao.OptimisticLockingFailureException("Row was updated by another transaction"));

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> {
            incidentService.claimIncident(incident.getId(), guardUser.getEmail());
        });

        assertTrue(ex.getMessage().contains("Bảo vệ Đi Trước"), "Thông điệp lỗi phải chỉ rõ ai vừa tiếp nhận trước");
    }

    // ================================================================== 5 nhãn (quyết định 03/11)

    @Test
    @DisplayName("5 nhãn: event_type cũ ACCESS_OUT_OF_TIME từ AI bản cũ được lưu thành UNAUTHORIZED_PERSON")
    void ingestIncident_legacyAccessOutOfTime_savedAsUnauthorizedPerson() {
        IncidentEventDto eventDto = IncidentEventDto.builder()
                .eventId("EVT-OOT-1")
                .cameraCode("CAM-001")
                .eventType("ACCESS_OUT_OF_TIME")
                .build();
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-001"))
                .thenReturn(Optional.of(Camera.builder().cameraCode("CAM-001").area(area).build()));
        when(incidentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        incidentService.ingestIncident(eventDto);

        ArgumentCaptor<SecurityIncident> saved = ArgumentCaptor.forClass(SecurityIncident.class);
        verify(incidentRepository, atLeastOnce()).save(saved.capture());
        assertEquals("UNAUTHORIZED_PERSON", saved.getAllValues().get(0).getEventType());
    }

    @Test
    @DisplayName("5 nhãn: AUTHORIZED_PERSON / AUTHORIZED_ACCESS chỉ dùng cho lịch sử ra vào, không tạo sự cố")
    void ingestIncident_authorizedPerson_doesNotCreateIncident() {
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-001"))
                .thenReturn(Optional.of(Camera.builder().cameraCode("CAM-001").area(area).build()));

        for (String type : List.of("AUTHORIZED_PERSON", "AUTHORIZED_ACCESS")) {
            IncidentEventDto eventDto = IncidentEventDto.builder()
                    .eventId("EVT-AUTH-" + type)
                    .cameraCode("CAM-001")
                    .eventType(type)
                    .build();
            assertNull(incidentService.ingestIncident(eventDto), type);
        }
        verify(incidentRepository, never()).save(any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("5 nhãn: bảng map tên cũ -> mới đúng quyết định 03/11; loại khác giữ nguyên")
    void normalizeEventType_mapsLegacyNames() {
        for (String t : List.of("INTRUSION", "AFTER_HOURS", "AFTER_HOURS_PRESENCE", "AFTER_HOURS_ACCESS", "PERSON_INTRUSION")) {
            assertEquals("PERSON_INTRUSION", SecurityIncidentService.normalizeEventType(t), t);
        }
        for (String t : List.of("UNAUTHORIZED_ACCESS", "UNAUTHORIZED", "ACCESS_OUT_OF_TIME", "OUT_OF_TIME", "UNAUTHORIZED_PERSON")) {
            assertEquals("UNAUTHORIZED_PERSON", SecurityIncidentService.normalizeEventType(t), t);
        }
        assertEquals("UNKNOWN_PERSON", SecurityIncidentService.normalizeEventType("UNKNOWN"));
        assertEquals("UNKNOWN_PERSON", SecurityIncidentService.normalizeEventType("UNKNOWN_PERSON"));
        for (String t : List.of("SUSPICIOUS_MOTION", "CAMOUFLAGE", "MOVING_OBJECT", "SUSPICIOUS_MOVING_OBJECT")) {
            assertEquals("SUSPICIOUS_MOVING_OBJECT", SecurityIncidentService.normalizeEventType(t), t);
        }
        assertEquals("AUTHORIZED_PERSON", SecurityIncidentService.normalizeEventType("AUTHORIZED_ACCESS"));
        assertEquals("CAMERA_TAMPERING", SecurityIncidentService.normalizeEventType("CAMERA_TAMPERING"));
    }

    // ===== M5: Parse detected_at chính xác đến mili giây =====

    @Test
    @DisplayName("M5: parseDetectedAt parse đúng chuẩn ISO UTC có ms, không ms, offset +07:00")
    void parseDetectedAt_validFormats() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-11T12:00:00Z");

        // 1. UTC có ms
        OffsetDateTime r1 = incidentService.parseDetectedAt("2026-11-11T11:59:30.123Z", now);
        assertEquals(OffsetDateTime.parse("2026-11-11T11:59:30.123Z"), r1);

        // 2. UTC không ms
        OffsetDateTime r2 = incidentService.parseDetectedAt("2026-11-11T11:59:30Z", now);
        assertEquals(OffsetDateTime.parse("2026-11-11T11:59:30Z"), r2);

        // 3. Offset +07:00 có ms (18:59:30.123+07:00 tương đương 11:59:30.123Z)
        OffsetDateTime r3 = incidentService.parseDetectedAt("2026-11-11T18:59:30.123+07:00", now);
        assertEquals(OffsetDateTime.parse("2026-11-11T18:59:30.123+07:00"), r3);
        assertTrue(r3.isEqual(OffsetDateTime.parse("2026-11-11T11:59:30.123Z")));

        // 4. Offset +07:00 không ms
        OffsetDateTime r4 = incidentService.parseDetectedAt("2026-11-11T18:59:30+07:00", now);
        assertEquals(OffsetDateTime.parse("2026-11-11T18:59:30+07:00"), r4);
    }

    @Test
    @DisplayName("M5: parseDetectedAt fallback về now() khi rỗng, null, hoặc rác")
    void parseDetectedAt_fallbackOnInvalid() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-11T12:00:00Z");

        assertEquals(now, incidentService.parseDetectedAt(null, now));
        assertEquals(now, incidentService.parseDetectedAt("", now));
        assertEquals(now, incidentService.parseDetectedAt("   ", now));
        assertEquals(now, incidentService.parseDetectedAt("invalid-timestamp-string", now));
        assertEquals(now, incidentService.parseDetectedAt("2026/11/11 12:00:00", now));
    }

    @Test
    @DisplayName("M5: parseDetectedAt fallback về now() khi lệch quá xa (> 5 phút tương lai hoặc > 24 giờ quá khứ)")
    void parseDetectedAt_fallbackOnDrift() {
        OffsetDateTime now = OffsetDateTime.parse("2026-11-11T12:00:00Z");

        // Tương lai > 5 phút (ví dụ 6 phút)
        OffsetDateTime future = now.plusMinutes(6);
        assertEquals(now, incidentService.parseDetectedAt(future.toString(), now));

        // Tương lai <= 5 phút (hợp lệ)
        OffsetDateTime futureValid = now.plusMinutes(3);
        assertEquals(futureValid, incidentService.parseDetectedAt(futureValid.toString(), now));

        // Quá khứ > 24 giờ (ví dụ 25 giờ trước)
        OffsetDateTime old = now.minusHours(25);
        assertEquals(now, incidentService.parseDetectedAt(old.toString(), now));

        // Quá khứ trong vòng 24 giờ (hợp lệ - outbox gửi bù sau mất mạng)
        OffsetDateTime oldValid = now.minusHours(12);
        assertEquals(oldValid, incidentService.parseDetectedAt(oldValid.toString(), now));
    }

    @Test
    @DisplayName("M5: Ingest sử dụng detected_at đã parse từ payload")
    void ingestIncident_usesParsedDetectedAt() {
        OffsetDateTime expectedTs = OffsetDateTime.now().minusHours(2).withNano(123_000_000);
        String eventTs = expectedTs.toString();

        IncidentEventDto eventDto = IncidentEventDto.builder()
                .eventId("EVT-M5-001")
                .cameraCode("CAM-001")
                .eventType("UNAUTHORIZED_ENTRY")
                .detectedAt(eventTs)
                .imageUrl("http://minio:9000/security-evidence/snap.jpg")
                .build();

        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-001"))
                .thenReturn(Optional.of(Camera.builder().cameraCode("CAM-001").area(area).build()));
        when(incidentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        IncidentDetailResponse result = incidentService.ingestIncident(eventDto);

        assertNotNull(result);
        assertEquals(expectedTs, result.getDetectedAt());
    }
}
