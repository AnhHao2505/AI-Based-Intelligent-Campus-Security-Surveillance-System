package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.incident.IncidentClaimResponse;
import com.fa26se040.icss.dto.incident.IncidentDetailResponse;
import com.fa26se040.icss.dto.incident.IncidentEventDto;
import com.fa26se040.icss.dto.incident.IncidentResolveRequest;
import com.fa26se040.icss.enums.IncidentOutcome;
import com.fa26se040.icss.enums.IncidentResolutionCategory;
import com.fa26se040.icss.enums.IncidentStatus;
import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.service.SecurityIncidentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SecurityIncidentControllerTest {

    @Mock
    private SecurityIncidentService securityIncidentService;

    @InjectMocks
    private SecurityIncidentController securityIncidentController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private Authentication mockGuardAuth;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(securityIncidentController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        objectMapper = new ObjectMapper();
        mockGuardAuth = new UsernamePasswordAuthenticationToken("guard@fpt.edu.vn", "password");
    }

    @Test
    @DisplayName("POST /api/incidents/test-alert - Kích hoạt 1 cảnh báo test thành công")
    void triggerTestAlert_Success() throws Exception {
        UUID incidentId = UUID.randomUUID();
        IncidentEventDto eventDto = IncidentEventDto.builder()
                .cameraCode("CAM-001")
                .eventType("UNAUTHORIZED_ACCESS")
                .eventId("EVT-100")
                .build();

        IncidentDetailResponse detailResponse = IncidentDetailResponse.builder()
                .id(incidentId)
                .cameraCode("CAM-001")
                .eventType("UNAUTHORIZED_ACCESS")
                .status(IncidentStatus.NEW)
                .detectedAt(OffsetDateTime.now())
                .build();

        when(securityIncidentService.ingestIncident(any(IncidentEventDto.class))).thenReturn(detailResponse);

        mockMvc.perform(post("/api/incidents/test-alert")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(eventDto)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(incidentId.toString()))
                .andExpect(jsonPath("$.data.cameraCode").value("CAM-001"))
                .andExpect(jsonPath("$.data.status").value("NEW"));
    }

    @Test
    @DisplayName("POST /api/incidents/test-alert/batch - Kích hoạt batch đa sự cố thành công")
    void triggerBatchTestAlerts_Success() throws Exception {
        IncidentEventDto evt1 = IncidentEventDto.builder().cameraCode("CAM-001").eventType("UNAUTHORIZED_ACCESS").build();
        IncidentEventDto evt2 = IncidentEventDto.builder().cameraCode("CAM-002").eventType("UNKNOWN_PERSON").build();

        IncidentDetailResponse res1 = IncidentDetailResponse.builder().id(UUID.randomUUID()).cameraCode("CAM-001").eventType("UNAUTHORIZED_ACCESS").status(IncidentStatus.NEW).build();
        IncidentDetailResponse res2 = IncidentDetailResponse.builder().id(UUID.randomUUID()).cameraCode("CAM-002").eventType("UNKNOWN_PERSON").status(IncidentStatus.NEW).build();

        when(securityIncidentService.ingestIncident(any(IncidentEventDto.class)))
                .thenReturn(res1)
                .thenReturn(res2);

        mockMvc.perform(post("/api/incidents/test-alert/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(List.of(evt1, evt2))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("POST /api/incidents/{id}/claim - 409 Conflict khi sự cố đã có người tiếp nhận")
    void claimIncident_ConflictAlreadyClaimed() throws Exception {
        UUID incidentId = UUID.randomUUID();
        when(securityIncidentService.claimIncident(eq(incidentId), eq("guard@fpt.edu.vn")))
                .thenThrow(new IllegalStateException("Sự việc vừa được tiếp nhận bởi Bảo vệ Nguyễn Văn A"));

        mockMvc.perform(post("/api/incidents/" + incidentId + "/claim")
                        .principal(mockGuardAuth))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Sự việc vừa được tiếp nhận bởi Bảo vệ Nguyễn Văn A"));
    }

    @Test
    @DisplayName("POST /api/incidents/{id}/resolve - 409 Conflict khi sự cố đã được xử lý trước đó")
    void resolveIncident_ConflictAlreadyResolved() throws Exception {
        UUID incidentId = UUID.randomUUID();
        IncidentResolveRequest req = IncidentResolveRequest.builder()
                .outcome(IncidentOutcome.VERIFIED)
                .resolutionCategory(IncidentResolutionCategory.REMINDED_DISPERSED)
                .resolutionNotes("Đã xử lý")
                .build();

        when(securityIncidentService.resolveIncident(eq(incidentId), any(), eq("guard@fpt.edu.vn")))
                .thenThrow(new IllegalStateException("Sự cố này đã được xử lý"));

        mockMvc.perform(post("/api/incidents/" + incidentId + "/resolve")
                        .principal(mockGuardAuth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Sự cố này đã được xử lý"));
    }
}
