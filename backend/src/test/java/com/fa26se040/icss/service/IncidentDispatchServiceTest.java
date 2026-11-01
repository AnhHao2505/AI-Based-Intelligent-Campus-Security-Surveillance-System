package com.fa26se040.icss.service;

import com.fa26se040.icss.config.WebSocketSessionRegistry;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Building;
import com.fa26se040.icss.entity.DutyPostType;
import com.fa26se040.icss.entity.Floor;
import com.fa26se040.icss.entity.IncidentDispatchOffer;
import com.fa26se040.icss.entity.IncidentDispatchPolicy;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.DispatchOfferCancelReason;
import com.fa26se040.icss.enums.DispatchOfferResponse;
import com.fa26se040.icss.enums.IncidentSeverity;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.DutyPostTypeRepository;
import com.fa26se040.icss.repository.IncidentDispatchOfferRepository;
import com.fa26se040.icss.repository.IncidentDispatchPolicyRepository;
import com.fa26se040.icss.repository.IncidentDispatchQueryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** V93 (BR-DSP-02..08): IncidentDispatchService — không có transaction nên các lần gửi "sau commit" chạy ngay. */
@ExtendWith(MockitoExtension.class)
class IncidentDispatchServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 1, 10, 0, 0, 0, ZoneOffset.ofHours(7));

    @Mock private SystemConfigService systemConfigService;
    @Mock private IncidentDispatchPolicyRepository policyRepository;
    @Mock private IncidentDispatchOfferRepository offerRepository;
    @Mock private IncidentDispatchQueryRepository dispatchQueryRepository;
    @Mock private DutyPostTypeRepository dutyPostTypeRepository;
    @Mock private IncidentRecipientResolver incidentRecipientResolver;
    @Mock private WebSocketSessionRegistry sessionRegistry;
    @Mock private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private IncidentDispatchService service;

    private static User guard(String name) {
        return User.builder().id(UUID.randomUUID()).fullName(name).role(Role.GUARD).build();
    }

    private static IncidentDispatchPolicy policy(int size, int timeoutSeconds, boolean notifyFm) {
        return IncidentDispatchPolicy.builder().severity(IncidentSeverity.HIGH).step1Size((short) size)
                .step1TimeoutSeconds(timeoutSeconds).escalateFmMinutes(5).notifyFmImmediately(notifyFm).build();
    }

    private static SecurityIncident incidentInBuilding(UUID buildingId) {
        Building building = Building.builder().id(buildingId).name("Tòa Alpha").build();
        Floor floor = Floor.builder().id(UUID.randomUUID()).building(building).name("Tầng 1").build();
        Area area = Area.builder().id(UUID.randomUUID()).name("Phòng server").building("Tòa Alpha").floorEntity(floor).build();
        return SecurityIncident.builder().id(UUID.randomUUID()).area(area).building("Tòa Alpha")
                .eventType("UNAUTHORIZED_ACCESS").severity(IncidentSeverity.HIGH).status(IncidentStatus.NEW)
                .subjectUserCode("NV001").build();
    }

    @Test
    @DisplayName("BR-DSP-01: DISPATCH_ENABLED đọc lỗi -> coi là tắt")
    void isEnabled_errorMeansDisabled() {
        when(systemConfigService.getBoolean(ConfigKey.DISPATCH_ENABLED)).thenThrow(new IllegalStateException("cache lỗi"));
        assertFalse(service.isEnabled());
    }

    @Test
    @DisplayName("BR-SEV-03: severity null -> chính sách của INCIDENT_SEVERITY_DEFAULT")
    void policyFor_nullSeverityUsesDefault() {
        when(systemConfigService.getString(ConfigKey.INCIDENT_SEVERITY_DEFAULT)).thenReturn("medium");
        IncidentDispatchPolicy medium = policy(1, 60, false);
        when(policyRepository.findById(IncidentSeverity.MEDIUM)).thenReturn(Optional.of(medium));

        assertSame(medium, service.policyFor(null).orElseThrow());
    }

    @Test
    @DisplayName("BR-DSP-03: xếp theo số claim trong cửa sổ, rồi lần claim gần nhất (chưa claim đứng trước), rồi ID")
    void rankByLoadScore() {
        User busy = guard("Đã nhận 2 vụ");
        User recent = guard("Nhận 1 vụ gần đây");
        User older = guard("Nhận 1 vụ lâu hơn");
        User never = guard("Chưa nhận vụ nào");
        when(systemConfigService.getInt(ConfigKey.DISPATCH_LOAD_WINDOW_MINUTES)).thenReturn(60);
        when(dispatchQueryRepository.countClaimsSince(anyCollection(), eq(NOW.minusMinutes(60)))).thenReturn(List.of(
                new Object[]{busy.getId(), 2L}, new Object[]{recent.getId(), 1L}, new Object[]{older.getId(), 1L}));
        when(dispatchQueryRepository.findLastClaimTimes(anyCollection())).thenReturn(List.of(
                new Object[]{busy.getId(), NOW.minusMinutes(5)},
                new Object[]{recent.getId(), NOW.minusMinutes(10)},
                new Object[]{older.getId(), NOW.minusMinutes(50)}));

        List<User> ranked = service.rankByLoadScore(List.of(busy, recent, older, never), NOW);

        assertEquals(List.of(never, older, recent, busy), ranked);
    }

    @Test
    @DisplayName("BR-DSP-04: lấy tối đa step1_size người; truyền đúng toà, đối tượng, người bận, vị trí được mời")
    void selectStep1Guards_limitsToStep1Size() {
        UUID buildingId = UUID.randomUUID();
        SecurityIncident incident = incidentInBuilding(buildingId);
        User a = guard("A");
        User b = guard("B");
        User c = guard("C");
        UUID holdingClaimed = UUID.randomUUID();
        UUID hasPendingOffer = UUID.randomUUID();
        when(dispatchQueryRepository.findGuardIdsHoldingClaimed()).thenReturn(List.of(holdingClaimed));
        when(offerRepository.findGuardIdsWithPendingOffer()).thenReturn(List.of(hasPendingOffer));
        when(dutyPostTypeRepository.findByIsActiveTrueOrderBySortOrderAscCodeAsc()).thenReturn(List.of(
                DutyPostType.builder().code("PATROL").dispatchable(true).build(),
                DutyPostType.builder().code("GATE").dispatchable(false).build()));
        when(incidentRecipientResolver.findDispatchCandidates(eq(NOW), eq(buildingId), eq("NV001"),
                eq(Set.of(holdingClaimed, hasPendingOffer)), any(), eq(Set.of("PATROL"))))
                .thenReturn(List.of(a, b, c));
        when(systemConfigService.getInt(ConfigKey.DISPATCH_LOAD_WINDOW_MINUTES)).thenReturn(60);
        when(dispatchQueryRepository.countClaimsSince(anyCollection(), any())).thenReturn(List.of());
        when(dispatchQueryRepository.findLastClaimTimes(anyCollection())).thenReturn(List.of());

        List<User> invited = service.selectStep1Guards(incident, policy(2, 30, false), NOW);

        assertEquals(2, invited.size());
    }

    @Test
    @DisplayName("step1_size = 0 hoặc khu không gắn toà -> không mời, không truy vấn ứng viên")
    void selectStep1Guards_zeroSizeOrNoBuilding() {
        SecurityIncident noFloor = SecurityIncident.builder().id(UUID.randomUUID())
                .area(Area.builder().id(UUID.randomUUID()).name("Khu chưa gắn tầng").build()).build();

        assertTrue(service.selectStep1Guards(incidentInBuilding(UUID.randomUUID()), policy(0, 30, false), NOW).isEmpty());
        assertTrue(service.selectStep1Guards(noFloor, policy(2, 30, false), NOW).isEmpty());
        verifyNoInteractions(incidentRecipientResolver);
    }

    @Test
    @DisplayName("INCIDENT_OFFER: gửi tới /topic/guards/{id}/offers với type, offerId, incidentId, offeredAt, expiresAt, incident")
    void sendOffers_payload() {
        User a = guard("A");
        IncidentDispatchOffer offer = IncidentDispatchOffer.builder().id(UUID.randomUUID()).guard(a)
                .offeredAt(NOW).expiresAt(NOW.plusSeconds(30)).build();
        IncidentDetailResponse incident = IncidentDetailResponse.builder().id(UUID.randomUUID()).build();

        service.sendOffersAfterCommit(List.of(offer), incident);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/guards/" + a.getId() + "/offers"), payload.capture());
        assertEquals(IncidentDispatchService.TYPE_OFFER, payload.getValue().get("type"));
        assertEquals(offer.getId(), payload.getValue().get("offerId"));
        assertEquals(incident.getId(), payload.getValue().get("incidentId"));
        assertEquals(NOW, payload.getValue().get("offeredAt"));
        assertEquals(NOW.plusSeconds(30), payload.getValue().get("expiresAt"));
        assertSame(incident, payload.getValue().get("incident"));
    }

    @Test
    @DisplayName("BR-DSP-07: claim -> lời mời của người nhận ACCEPTED, người khác CANCELLED + INCIDENT_OFFER_CANCELLED")
    void onClaimed_cancelsOthers() {
        UUID incidentId = UUID.randomUUID();
        User claimer = guard("Người nhận");
        User other = guard("Người còn lại");
        IncidentDispatchOffer otherOffer = IncidentDispatchOffer.builder().id(UUID.randomUUID()).guard(other).build();
        when(offerRepository.findPendingByIncidentId(incidentId)).thenReturn(List.of(otherOffer));
        when(offerRepository.respondIfPending(otherOffer.getId(), DispatchOfferResponse.CANCELLED,
                DispatchOfferCancelReason.CLAIMED_BY_OTHER, NOW)).thenReturn(1);

        service.onClaimed(incidentId, claimer.getId(), NOW);

        verify(offerRepository).acceptIfPending(incidentId, claimer.getId(), NOW);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/guards/" + other.getId() + "/offers"), payload.capture());
        assertEquals(IncidentDispatchService.TYPE_OFFER_CANCELLED, payload.getValue().get("type"));
        assertEquals("CLAIMED_BY_OTHER", payload.getValue().get("reason"));
    }

    @Test
    @DisplayName("K4: lời mời quá hạn -> EXPIRED và báo người được mời; lời mời đã bị bên khác đóng thì không báo")
    void expireDueOffers() {
        SecurityIncident incident = incidentInBuilding(UUID.randomUUID());
        User a = guard("A");
        User b = guard("B");
        IncidentDispatchOffer due = IncidentDispatchOffer.builder().id(UUID.randomUUID()).guard(a).incident(incident).build();
        IncidentDispatchOffer raced = IncidentDispatchOffer.builder().id(UUID.randomUUID()).guard(b).incident(incident).build();
        when(offerRepository.findDuePending(NOW)).thenReturn(List.of(due, raced));
        when(offerRepository.respondIfPending(due.getId(), DispatchOfferResponse.EXPIRED, null, NOW)).thenReturn(1);
        when(offerRepository.respondIfPending(raced.getId(), DispatchOfferResponse.EXPIRED, null, NOW)).thenReturn(0);

        assertEquals(1, service.expireDueOffers(NOW));

        verify(messagingTemplate, times(1)).convertAndSend(eq("/topic/guards/" + a.getId() + "/offers"), any(Object.class));
        verify(messagingTemplate, never()).convertAndSend(eq("/topic/guards/" + b.getId() + "/offers"), any(Object.class));
    }

    @Test
    @DisplayName("BR-DSP-08: huỷ lời mời khi sự cố đã đóng, bảo vệ hết ca hoặc mất phiên mobile")
    void cancelStaleOffers_reasons() {
        SecurityIncident open = incidentInBuilding(UUID.randomUUID());
        SecurityIncident closed = incidentInBuilding(UUID.randomUUID());
        closed.setStatus(IncidentStatus.CLAIMED);
        User onDutyMobile = guard("Còn trực, có mobile");
        User offShift = guard("Hết ca");
        User disconnected = guard("Mất mobile");
        User closedIncidentGuard = guard("Sự cố đã đóng");
        List<IncidentDispatchOffer> pending = new ArrayList<>(List.of(
                IncidentDispatchOffer.builder().id(UUID.randomUUID()).guard(onDutyMobile).incident(open).build(),
                IncidentDispatchOffer.builder().id(UUID.randomUUID()).guard(offShift).incident(open).build(),
                IncidentDispatchOffer.builder().id(UUID.randomUUID()).guard(disconnected).incident(open).build(),
                IncidentDispatchOffer.builder().id(UUID.randomUUID()).guard(closedIncidentGuard).incident(closed).build()));
        when(offerRepository.findAllPending()).thenReturn(pending);
        when(incidentRecipientResolver.findOnDutyGuards(NOW)).thenReturn(List.of(onDutyMobile, disconnected, closedIncidentGuard));
        when(sessionRegistry.hasMobileSession(onDutyMobile.getId())).thenReturn(true);
        when(sessionRegistry.hasMobileSession(disconnected.getId())).thenReturn(false);
        when(offerRepository.respondIfPending(any(), eq(DispatchOfferResponse.CANCELLED), any(), eq(NOW))).thenReturn(1);

        assertEquals(3, service.cancelStaleOffers(NOW));

        verify(offerRepository).respondIfPending(pending.get(1).getId(), DispatchOfferResponse.CANCELLED,
                DispatchOfferCancelReason.SHIFT_ENDED, NOW);
        verify(offerRepository).respondIfPending(pending.get(2).getId(), DispatchOfferResponse.CANCELLED,
                DispatchOfferCancelReason.DISCONNECTED, NOW);
        verify(offerRepository).respondIfPending(pending.get(3).getId(), DispatchOfferResponse.CANCELLED,
                DispatchOfferCancelReason.INCIDENT_CLOSED, NOW);
        verify(offerRepository, never()).respondIfPending(eq(pending.get(0).getId()), any(), any(), any());
    }
}
