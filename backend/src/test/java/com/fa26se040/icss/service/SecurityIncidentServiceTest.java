package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.incident.IncidentClaimResponse;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.dto.incident.IncidentResolveRequest;
import com.fa26se040.icss.entity.Area;
import com.fa26se040.icss.entity.SecurityIncident;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.AreaRepository;
import com.fa26se040.icss.repository.SecurityIncidentRepository;
import com.fa26se040.icss.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.OffsetDateTime;
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
    private AreaRepository areaRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

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

        when(areaRepository.findAreasByCameraCode("CAM-001")).thenReturn(List.of(area));
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
}
