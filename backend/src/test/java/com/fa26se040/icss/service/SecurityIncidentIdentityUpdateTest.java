package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.IncidentEscalationRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * V129: AI chốt cảnh báo người lạ rồi mới nhận ra mặt -> gửi IDENTITY_UPDATE cùng roi_session_id.
 * Backend cập nhật / tự đóng đúng sự cố cũ, không tạo sự cố mới.
 */
@ExtendWith(MockitoExtension.class)
class SecurityIncidentIdentityUpdateTest {

    private static final String SESSION = "6f1c2a52-0d55-4a5e-9a3e-1f7e0c1d2b3a";
    private static final String CAMERA = "CAM-001";
    private static final String UPDATES_TOPIC = "/topic/incidents/updates";

    @Mock private SecurityIncidentRepository incidentRepository;
    @Mock private CameraRepository cameraRepository;
    @Mock private UserRepository userRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private CameraAccessLogService cameraAccessLogService;
    @Mock private GuardShiftRepository guardShiftRepository;
    @Mock private IncidentRecipientResolver incidentRecipientResolver;
    @Mock private IncidentEscalationRepository incidentEscalationRepository;
    @Mock private com.fa26se040.icss.config.AiServiceRestTemplate restTemplate;
    @Mock private IncidentDispatchService incidentDispatchService;

    @InjectMocks
    private SecurityIncidentService incidentService;

    private Area area;
    private SecurityIncident incident;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(incidentService, "incidentDispatchService", incidentDispatchService);
        ReflectionTestUtils.setField(incidentService, "aiServiceUrl", null);
        area = Area.builder().id(UUID.randomUUID()).name("Phòng Lab 1").building("TOA_ALPHA").build();
        incident = SecurityIncident.builder()
                .id(UUID.randomUUID())
                .cameraCode(CAMERA)
                .area(area)
                .building("TOA_ALPHA")
                .eventType("UNKNOWN_PERSON")
                .resolutionNotes("Vị trí: Phòng Lab 1")
                .status(IncidentStatus.NEW)
                .aiSessionId(SESSION)
                .version(0)
                .detectedAt(OffsetDateTime.now())
                .build();
    }

    private static IncidentEventDto update(String outcome) {
        return IncidentEventDto.builder()
                .eventId(UUID.randomUUID().toString())
                .cameraCode(CAMERA)
                .eventType("IDENTITY_UPDATE")
                .roiSessionId(SESSION)
                .identityOutcome(outcome)
                .userCode("SE194249")
                .userName("Nguyễn Hoàng Minh")
                .imageUrl("security-evidence/2026-11-17/CAM-001/IDENTITY_UPDATE_Track1403_122335.jpg")
                .details("[UNAUTHORIZED_PERSON] Tên: Nguyễn Hoàng Minh (SE194249)\nLý do: Cấp độ không đủ\nVị trí: Phòng Lab 1")
                .build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentUpdate() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSend(eq(UPDATES_TOPIC), captor.capture());
        return (Map<String, Object>) captor.getValue();
    }

    @Test
    @DisplayName("AUTHORIZED + sự cố NEW: tự đóng FALSE_ALARM, không người xử lý, huỷ lời mời, báo kèm identityNotice")
    void authorized_newIncident_autoClosed() {
        when(incidentRepository.findFirstByAiSessionIdAndCameraCodeOrderByDetectedAtDesc(SESSION, CAMERA))
                .thenReturn(Optional.of(incident));
        when(incidentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        IncidentDetailResponse res = incidentService.ingestIncident(update("AUTHORIZED"));

        assertNotNull(res);
        assertEquals(IncidentStatus.RESOLVED_DISMISSED, incident.getStatus());
        assertEquals(IncidentOutcome.DISMISSED, incident.getOutcome());
        assertEquals(IncidentResolutionCategory.FALSE_ALARM, incident.getResolutionCategory());
        assertNull(incident.getResolvedBy());
        assertNotNull(incident.getResolvedAt());
        assertTrue(incident.getResolutionNotes().contains("Tự đóng: AI xác minh danh tính muộn, Nguyễn Hoàng Minh (SE194249)"));
        verify(incidentDispatchService).cancelPendingOffersForClosedIncident(eq(incident.getId()), any());
        verifyNoInteractions(cameraRepository);
        verify(incidentRepository, never()).save(any());

        Map<String, Object> payload = sentUpdate();
        assertEquals("RESOLVED_DISMISSED", payload.get("status"));
        assertEquals(true, payload.get("autoResolved"));
        assertNull(payload.get("resolvedByName"));
        assertEquals("security-evidence/2026-11-17/CAM-001/IDENTITY_UPDATE_Track1403_122335.jpg", incident.getImageUrl());
        assertEquals("security-evidence/2026-11-17/CAM-001/IDENTITY_UPDATE_Track1403_122335.jpg", payload.get("imageUrl"));
        assertTrue(String.valueOf(payload.get("identityNotice")).contains("đã tự đóng"));
    }

    @Test
    @DisplayName("AUTHORIZED + sự cố đã CLAIMED: giữ nguyên trạng thái, chỉ gửi thông báo cho bảo vệ")
    void authorized_claimedIncident_onlyNotified() {
        incident.setStatus(IncidentStatus.CLAIMED);
        incident.setClaimedBy(User.builder().id(UUID.randomUUID()).fullName("Bảo vệ A").build());
        when(incidentRepository.findFirstByAiSessionIdAndCameraCodeOrderByDetectedAtDesc(SESSION, CAMERA))
                .thenReturn(Optional.of(incident));
        when(incidentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        incidentService.ingestIncident(update("AUTHORIZED"));

        assertEquals(IncidentStatus.CLAIMED, incident.getStatus());
        assertEquals("security-evidence/2026-11-17/CAM-001/IDENTITY_UPDATE_Track1403_122335.jpg", incident.getImageUrl());
        verifyNoInteractions(incidentDispatchService);
        Map<String, Object> payload = sentUpdate();
        assertFalse(payload.containsKey("status"), "Không gửi status để client không chạy lại luồng claim");
        assertEquals("security-evidence/2026-11-17/CAM-001/IDENTITY_UPDATE_Track1403_122335.jpg", payload.get("imageUrl"));
        assertTrue(String.valueOf(payload.get("identityNotice")).contains("Bạn có thể đóng sự cố"));
    }

    @Test
    @DisplayName("UNAUTHORIZED: UNKNOWN_PERSON -> UNAUTHORIZED_PERSON, ghi tên và lý do, không tạo sự cố mới")
    void unauthorized_updatesExistingIncident() {
        when(incidentRepository.findFirstByAiSessionIdAndCameraCodeOrderByDetectedAtDesc(SESSION, CAMERA))
                .thenReturn(Optional.of(incident));
        when(incidentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        incidentService.ingestIncident(update("UNAUTHORIZED"));

        assertEquals("UNAUTHORIZED_PERSON", incident.getEventType());
        assertEquals(IncidentStatus.NEW, incident.getStatus());
        assertEquals("SE194249", incident.getSubjectUserCode());
        assertEquals("Tên: Nguyễn Hoàng Minh (SE194249)\nLý do: Cấp độ không đủ\nVị trí: Phòng Lab 1", incident.getResolutionNotes());
        assertEquals("security-evidence/2026-11-17/CAM-001/IDENTITY_UPDATE_Track1403_122335.jpg", incident.getImageUrl());
        verify(incidentRepository, never()).save(any());
        verifyNoInteractions(incidentDispatchService);
        Map<String, Object> payload = sentUpdate();
        assertEquals("UNAUTHORIZED_PERSON", payload.get("eventType"));
        assertEquals("security-evidence/2026-11-17/CAM-001/IDENTITY_UPDATE_Track1403_122335.jpg", payload.get("imageUrl"));
        assertFalse(payload.containsKey("status"));
    }

    @Test
    @DisplayName("UNAUTHORIZED trên sự cố PERSON_INTRUSION: giữ loại PERSON_INTRUSION")
    void unauthorized_keepsIntrusionType() {
        incident.setEventType("PERSON_INTRUSION");
        when(incidentRepository.findFirstByAiSessionIdAndCameraCodeOrderByDetectedAtDesc(SESSION, CAMERA))
                .thenReturn(Optional.of(incident));
        when(incidentRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        incidentService.ingestIncident(update("UNAUTHORIZED"));

        assertEquals("PERSON_INTRUSION", incident.getEventType());
    }

    @Test
    @DisplayName("Không tìm thấy sự cố của phiên: bỏ qua, không tạo sự cố, không gửi gì")
    void noIncidentForSession_ignored() {
        when(incidentRepository.findFirstByAiSessionIdAndCameraCodeOrderByDetectedAtDesc(SESSION, CAMERA))
                .thenReturn(Optional.empty());

        assertNull(incidentService.ingestIncident(update("AUTHORIZED")));

        verify(incidentRepository, never()).save(any());
        verify(incidentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(messagingTemplate, cameraRepository);
    }

    @Test
    @DisplayName("Sự cố đã đóng: bỏ qua, không đổi gì")
    void resolvedIncident_ignored() {
        incident.setStatus(IncidentStatus.RESOLVED_VERIFIED);
        when(incidentRepository.findFirstByAiSessionIdAndCameraCodeOrderByDetectedAtDesc(SESSION, CAMERA))
                .thenReturn(Optional.of(incident));

        incidentService.ingestIncident(update("AUTHORIZED"));

        assertEquals(IncidentStatus.RESOLVED_VERIFIED, incident.getStatus());
        verify(incidentRepository, never()).saveAndFlush(any());
        verifyNoInteractions(messagingTemplate);
    }

    @Test
    @DisplayName("Thiếu roi_session_id: bỏ qua")
    void missingSession_ignored() {
        IncidentEventDto dto = update("AUTHORIZED");
        dto.setRoiSessionId(null);

        assertNull(incidentService.ingestIncident(dto));

        verifyNoInteractions(messagingTemplate, cameraRepository);
        verify(incidentRepository, never()).findFirstByAiSessionIdAndCameraCodeOrderByDetectedAtDesc(any(), any());
    }

    @Test
    @DisplayName("Cảnh báo người lạ mới: lưu roi_session_id vào ai_session_id của sự cố")
    void newUnknownIncident_storesAiSessionId() {
        IncidentEventDto dto = IncidentEventDto.builder()
                .eventId("EVT-SESSION-1")
                .cameraCode(CAMERA)
                .eventType("UNKNOWN_PERSON")
                .details("[UNKNOWN_PERSON] Vị trí: Phòng Lab 1")
                .roiSessionId(SESSION)
                .build();
        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull(CAMERA))
                .thenReturn(Optional.of(Camera.builder().cameraCode(CAMERA).area(area).build()));
        when(incidentDispatchService.isEnabled()).thenReturn(false);
        when(incidentRepository.save(any())).thenAnswer(inv -> {
            SecurityIncident saved = inv.getArgument(0);
            saved.setId(UUID.randomUUID());
            return saved;
        });

        incidentService.ingestIncident(dto);

        ArgumentCaptor<SecurityIncident> captor = ArgumentCaptor.forClass(SecurityIncident.class);
        verify(incidentRepository).save(captor.capture());
        assertEquals(SESSION, captor.getValue().getAiSessionId());
    }
}
