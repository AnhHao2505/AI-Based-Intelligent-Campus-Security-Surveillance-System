package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.IncidentDispatchOffer;
import com.fa26se040.icss.entity.IncidentDispatchPolicy;
import com.fa26se040.icss.entity.IncidentEscalation;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AreaLevel;
import com.fa26se040.icss.enums.IncidentDispatchStage;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.IncidentNotOfferedException;
import com.fa26se040.icss.exception.IncidentOfferNotPendingException;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.IncidentDispatchQueryRepository;
import com.fa26se040.icss.repository.IncidentEscalationRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V93 (K5, K6, BR-DSP-01, BR-DSP-05, BR-DSP-06): SecurityIncidentService với phân phối theo đợt.
 * Không có transaction nên các lần gửi "sau commit" chạy ngay.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SecurityIncidentServiceDispatchTest {

    @Mock private SecurityIncidentRepository incidentRepository;
    @Mock private CameraRepository cameraRepository;
    @Mock private UserRepository userRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private CameraAccessLogService cameraAccessLogService;
    @Mock private GuardShiftRepository guardShiftRepository;
    @Mock private IncidentRecipientResolver incidentRecipientResolver;
    @Mock private IncidentEscalationRepository incidentEscalationRepository;
    @Mock private IncidentSeverityResolver incidentSeverityResolver;
    @Mock private IncidentDispatchService incidentDispatchService;
    @Mock private IncidentDispatchQueryRepository incidentDispatchQueryRepository;

    private SecurityIncidentService service;
    private Area area;
    private SecurityIncident saved;
    private User onDutyGuard;

    @BeforeEach
    void setUp() {
        service = new SecurityIncidentService(incidentRepository, cameraRepository, userRepository, messagingTemplate,
                cameraAccessLogService, guardShiftRepository, incidentRecipientResolver, incidentEscalationRepository,
                incidentSeverityResolver);
        ReflectionTestUtils.setField(service, "incidentDispatchService", incidentDispatchService);
        ReflectionTestUtils.setField(service, "incidentDispatchQueryRepository", incidentDispatchQueryRepository);
        ReflectionTestUtils.setField(service, "aiServiceUrl", "");

        area = Area.builder().id(UUID.randomUUID()).name("Phòng server").building("TOA_ALPHA")
                .areaLevel(AreaLevel.CONFIDENTIAL).build();
        saved = SecurityIncident.builder().id(UUID.randomUUID()).cameraCode("CAM-001").area(area).building("TOA_ALPHA")
                .eventType("UNAUTHORIZED_ACCESS").status(IncidentStatus.NEW).severity(IncidentSeverity.HIGH).build();
        onDutyGuard = User.builder().id(UUID.randomUUID()).fullName("Bảo vệ trực").email("g@fpt.edu.vn").role(Role.GUARD).build();

        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull("CAM-001"))
                .thenReturn(Optional.of(Camera.builder().cameraCode("CAM-001").area(area).build()));
        when(incidentRepository.save(any())).thenReturn(saved);
        when(incidentSeverityResolver.resolve(any(), any(), any())).thenReturn(IncidentSeverity.HIGH);
        when(incidentRecipientResolver.findOnDutyGuards(any())).thenReturn(List.of(onDutyGuard));
    }

    private static IncidentEventDto event() {
        return IncidentEventDto.builder().eventId("EVT-DSP").cameraCode("CAM-001").eventType("UNAUTHORIZED_ACCESS").build();
    }

    private static IncidentDispatchPolicy policy(int size, boolean notifyFm) {
        return IncidentDispatchPolicy.builder().severity(IncidentSeverity.HIGH).step1Size((short) size)
                .step1TimeoutSeconds(30).escalateFmMinutes(5).notifyFmImmediately(notifyFm).build();
    }

    private void verifyOldBroadcast(IncidentDetailResponse result) {
        verify(messagingTemplate).convertAndSend(eq("/topic/guards/" + onDutyGuard.getId() + "/alerts"), same((Object) result));
        verify(messagingTemplate).convertAndSend(eq("/topic/buildings/TOA_ALPHA/alerts"), same((Object) result));
        verify(messagingTemplate).convertAndSend(eq("/topic/security-alerts"), same((Object) result));
        ArgumentCaptor<IncidentEscalation> escalation = ArgumentCaptor.forClass(IncidentEscalation.class);
        verify(incidentEscalationRepository).save(escalation.capture());
        assertEquals(1, escalation.getValue().getGuardRecipientCount());
    }

    @Test
    @DisplayName("Hồi quy BR-DSP-01: DISPATCH_ENABLED = false -> broadcast như cũ, không chọn ứng viên, không lời mời")
    void dispatchDisabled_behavesExactlyAsBefore() {
        when(incidentDispatchService.isEnabled()).thenReturn(false);

        IncidentDetailResponse result = service.ingestIncident(event());

        verifyOldBroadcast(result);
        assertEquals(IncidentDispatchStage.BROADCAST, result.getDispatchStage());
        verify(incidentDispatchService, never()).policyFor(any());
        verify(incidentDispatchService, never()).selectStep1Guards(any(), any(), any());
        verify(incidentDispatchService, never()).createOffers(any(), any(), any(), any());
        verify(incidentDispatchService, never()).sendOffersAfterCommit(any(), any());
        verify(incidentDispatchService, never()).sendCriticalNoticeIfConfigured(any());
    }

    @Test
    @DisplayName("Hồi quy: service chưa inject dispatch (null) -> broadcast như cũ")
    void dispatchNotInjected_behavesAsBefore() {
        ReflectionTestUtils.setField(service, "incidentDispatchService", null);

        IncidentDetailResponse result = service.ingestIncident(event());

        verifyOldBroadcast(result);
        verifyNoInteractions(incidentDispatchService);
    }

    @Test
    @DisplayName("E1: bật dispatch nhưng không có ứng viên -> broadcast như cũ + critical notice nếu cấu hình")
    void dispatchEnabled_noCandidates_broadcasts() {
        when(incidentDispatchService.isEnabled()).thenReturn(true);
        when(incidentDispatchService.policyFor(IncidentSeverity.HIGH)).thenReturn(Optional.of(policy(2, false)));
        when(incidentDispatchService.selectStep1Guards(any(), any(), any())).thenReturn(List.of());

        IncidentDetailResponse result = service.ingestIncident(event());

        verifyOldBroadcast(result);
        verify(incidentDispatchService, never()).createOffers(any(), any(), any(), any());
        verify(incidentDispatchService).sendCriticalNoticeIfConfigured(saved);
    }

    @Test
    @DisplayName("K6: có ứng viên -> STEP1, KHÔNG gửi 3 kênh broadcast, guard_recipient_count = số người được mời")
    void dispatchEnabled_step1() {
        User a = User.builder().id(UUID.randomUUID()).fullName("A").role(Role.GUARD).build();
        User b = User.builder().id(UUID.randomUUID()).fullName("B").role(Role.GUARD).build();
        IncidentDispatchPolicy policy = policy(2, false);
        List<IncidentDispatchOffer> offers = List.of(
                IncidentDispatchOffer.builder().id(UUID.randomUUID()).guard(a).build(),
                IncidentDispatchOffer.builder().id(UUID.randomUUID()).guard(b).build());
        when(incidentDispatchService.isEnabled()).thenReturn(true);
        when(incidentDispatchService.policyFor(IncidentSeverity.HIGH)).thenReturn(Optional.of(policy));
        when(incidentDispatchService.selectStep1Guards(eq(saved), eq(policy), any())).thenReturn(List.of(a, b));
        when(incidentDispatchService.createOffers(eq(saved), eq(List.of(a, b)), eq(policy), any())).thenReturn(offers);

        IncidentDetailResponse result = service.ingestIncident(event());

        assertEquals(IncidentDispatchStage.STEP1, result.getDispatchStage());
        assertEquals(IncidentDispatchStage.STEP1, saved.getDispatchStage());
        assertNull(saved.getBroadcastAt());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
        ArgumentCaptor<IncidentEscalation> escalation = ArgumentCaptor.forClass(IncidentEscalation.class);
        verify(incidentEscalationRepository).save(escalation.capture());
        assertEquals(2, escalation.getValue().getGuardRecipientCount());
        verify(incidentDispatchService).sendOffersAfterCommit(offers, result);
        verify(incidentDispatchService).sendCriticalNoticeIfConfigured(saved);
    }

    @Test
    @DisplayName("BR-DSP-06: claim lúc STEP1 mà không có lời mời -> IncidentNotOfferedException, không ghi")
    void claimStep1_withoutOffer_rejected() {
        saved.setDispatchStage(IncidentDispatchStage.STEP1);
        when(incidentRepository.findById(saved.getId())).thenReturn(Optional.of(saved));
        when(userRepository.findByEmail("g@fpt.edu.vn")).thenReturn(Optional.of(onDutyGuard));
        when(incidentDispatchService.hasActiveOffer(eq(saved.getId()), eq(onDutyGuard.getId()), any())).thenReturn(false);

        assertThrows(IncidentNotOfferedException.class, () -> service.claimIncident(saved.getId(), "g@fpt.edu.vn"));

        verify(incidentRepository, never()).saveAndFlush(any());
        verify(incidentDispatchService, never()).onClaimed(any(), any(), any());
    }

    @Test
    @DisplayName("BR-DSP-07: claim lúc STEP1 có lời mời -> thành công, đóng các lời mời khác, payload updates có dispatchStage")
    void claimStep1_withOffer_succeeds() {
        saved.setDispatchStage(IncidentDispatchStage.STEP1);
        when(incidentRepository.findById(saved.getId())).thenReturn(Optional.of(saved));
        when(userRepository.findByEmail("g@fpt.edu.vn")).thenReturn(Optional.of(onDutyGuard));
        when(incidentDispatchService.hasActiveOffer(eq(saved.getId()), eq(onDutyGuard.getId()), any())).thenReturn(true);
        when(incidentRepository.saveAndFlush(saved)).thenReturn(saved);

        service.claimIncident(saved.getId(), "g@fpt.edu.vn");

        verify(incidentDispatchService).onClaimed(eq(saved.getId()), eq(onDutyGuard.getId()), any());
        verify(messagingTemplate).convertAndSend(eq("/topic/incidents/updates"),
                argThat((Object p) -> p instanceof java.util.Map<?, ?> m && "STEP1".equals(m.get("dispatchStage"))));
    }

    @Test
    @DisplayName("Claim ở BROADCAST: luật cũ, không kiểm lời mời")
    void claimBroadcast_unchangedRule() {
        when(incidentRepository.findById(saved.getId())).thenReturn(Optional.of(saved));
        when(userRepository.findByEmail("g@fpt.edu.vn")).thenReturn(Optional.of(onDutyGuard));
        when(incidentRepository.saveAndFlush(saved)).thenReturn(saved);

        service.claimIncident(saved.getId(), "g@fpt.edu.vn");

        verify(incidentDispatchService, never()).hasActiveOffer(any(), any(), any());
        assertEquals(IncidentStatus.CLAIMED, saved.getStatus());
    }

    @Test
    @DisplayName("E6: người cuối cùng từ chối -> chuyển BROADCAST và phát 3 kênh như cũ, count = số bảo vệ đang trực")
    void declineLastOffer_promotesToBroadcast() {
        saved.setDispatchStage(IncidentDispatchStage.STEP1);
        when(userRepository.findByEmail("g@fpt.edu.vn")).thenReturn(Optional.of(onDutyGuard));
        when(incidentDispatchService.decline(eq(saved.getId()), eq(onDutyGuard.getId()), any())).thenReturn(true);
        when(incidentDispatchService.hasPendingOffers(saved.getId())).thenReturn(false);
        when(incidentRepository.findById(saved.getId())).thenReturn(Optional.of(saved));
        when(incidentDispatchQueryRepository.promoteToBroadcast(eq(saved.getId()), any())).thenReturn(1);

        service.declineOffer(saved.getId(), "g@fpt.edu.vn");

        verify(incidentEscalationRepository).updateGuardRecipientCountForIncident(saved.getId(), 1);
        verify(messagingTemplate).convertAndSend(eq("/topic/guards/" + onDutyGuard.getId() + "/alerts"),
                argThat((Object p) -> p instanceof IncidentDetailResponse r && r.getDispatchStage() == IncidentDispatchStage.BROADCAST));
        verify(messagingTemplate).convertAndSend(eq("/topic/buildings/TOA_ALPHA/alerts"), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq("/topic/security-alerts"), any(Object.class));
        // Không sửa entity sau UPDATE có điều kiện (tránh tăng version)
        assertEquals(IncidentDispatchStage.STEP1, saved.getDispatchStage());
    }

    @Test
    @DisplayName("Còn lời mời chờ sau khi từ chối -> không broadcast")
    void declineWithOthersPending_noBroadcast() {
        when(userRepository.findByEmail("g@fpt.edu.vn")).thenReturn(Optional.of(onDutyGuard));
        when(incidentDispatchService.decline(eq(saved.getId()), eq(onDutyGuard.getId()), any())).thenReturn(true);
        when(incidentDispatchService.hasPendingOffers(saved.getId())).thenReturn(true);

        service.declineOffer(saved.getId(), "g@fpt.edu.vn");

        verify(incidentDispatchQueryRepository, never()).promoteToBroadcast(any(), any());
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    @DisplayName("Từ chối khi không còn lời mời chờ -> IncidentOfferNotPendingException")
    void declineWithoutPendingOffer_rejected() {
        when(userRepository.findByEmail("g@fpt.edu.vn")).thenReturn(Optional.of(onDutyGuard));
        when(incidentDispatchService.decline(any(), any(), any())).thenReturn(false);

        assertThrows(IncidentOfferNotPendingException.class, () -> service.declineOffer(saved.getId(), "g@fpt.edu.vn"));
    }
}
