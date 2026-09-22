package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelCreateRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelResponse;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelRevokeRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelUpdateRequest;
import com.fa26se040.icss.dto.assignedpersonnel.AssignedPersonnelUserInfo;
import com.fa26se040.icss.enums.AssignedPersonnelStatus;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.AssignedPersonnelErrorCode;
import com.fa26se040.icss.exception.AssignedPersonnelException;
import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.service.AreaAssignedPersonnelService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AreaAssignedPersonnelControllerTest {

    @Mock
    private AreaAssignedPersonnelService service;

    @InjectMocks
    private AreaAssignedPersonnelController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;
    private UsernamePasswordAuthenticationToken auth;

    private final UUID areaId = UUID.randomUUID();
    private final UUID recordId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final String fmEmail = "fm@fpt.edu.vn";

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();

        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        auth = new UsernamePasswordAuthenticationToken(
                fmEmail,
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_FACILITY_MANAGER"))
        );
    }

    private AssignedPersonnelResponse sampleResponse(AssignedPersonnelStatus status) {
        return new AssignedPersonnelResponse(
                recordId,
                areaId,
                new AssignedPersonnelUserInfo(userId, "GV001", "Nguyễn Văn Thầy", Role.NORMAL_USER),
                OffsetDateTime.now(),
                OffsetDateTime.now().plusMonths(3),
                "Phòng nghiên cứu",
                status,
                "Quản lý cơ sở",
                OffsetDateTime.now(),
                null,
                null,
                null
        );
    }

    @Test
    @DisplayName("GET ?status=ABC (sai enum) → 400 Bad Request thay vì 500")
    void getByArea_InvalidStatus_Returns400() throws Exception {
        mockMvc.perform(get("/api/areas/{areaId}/assigned-personnel", areaId)
                        .param("status", "ABC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET ?status=ACTIVE → 200 OK")
    void getByArea_ValidStatus_Returns200() throws Exception {
        when(service.getByArea(eq(areaId), eq(AssignedPersonnelStatus.ACTIVE)))
                .thenReturn(List.of(sampleResponse(AssignedPersonnelStatus.ACTIVE)));

        mockMvc.perform(get("/api/areas/{areaId}/assigned-personnel", areaId)
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(recordId.toString()))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"));
    }

    @Test
    @DisplayName("POST /assigned-personnel → 201 Created")
    void create_Valid_Returns201() throws Exception {
        AssignedPersonnelCreateRequest request = new AssignedPersonnelCreateRequest(
                userId,
                OffsetDateTime.now(),
                OffsetDateTime.now().plusMonths(6),
                "Gán giảng viên"
        );

        when(service.create(eq(areaId), any(AssignedPersonnelCreateRequest.class), eq(fmEmail)))
                .thenReturn(sampleResponse(AssignedPersonnelStatus.ACTIVE));

        mockMvc.perform(post("/api/areas/{areaId}/assigned-personnel", areaId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(recordId.toString()));
    }

    @Test
    @DisplayName("POST /assigned-personnel thiếu userId → 400 Bad Request")
    void create_MissingUserId_Returns400() throws Exception {
        AssignedPersonnelCreateRequest request = new AssignedPersonnelCreateRequest(
                null,
                OffsetDateTime.now(),
                null,
                "Gán thiếu userId"
        );

        mockMvc.perform(post("/api/areas/{areaId}/assigned-personnel", areaId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("PATCH /{id} cập nhật validTo → 200 OK")
    void updateValidTo_Valid_Returns200() throws Exception {
        AssignedPersonnelUpdateRequest request = new AssignedPersonnelUpdateRequest(
                OffsetDateTime.now().plusMonths(12)
        );

        when(service.updateValidTo(eq(areaId), eq(recordId), any(AssignedPersonnelUpdateRequest.class), eq(fmEmail)))
                .thenReturn(sampleResponse(AssignedPersonnelStatus.ACTIVE));

        mockMvc.perform(patch("/api/areas/{areaId}/assigned-personnel/{id}", areaId, recordId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(recordId.toString()));
    }

    @Test
    @DisplayName("PATCH /{id} khi {id} không thuộc {areaId} → 404 Not Found")
    void updateValidTo_RecordNotInArea_Returns404() throws Exception {
        AssignedPersonnelUpdateRequest request = new AssignedPersonnelUpdateRequest(
                OffsetDateTime.now().plusMonths(12)
        );

        when(service.updateValidTo(eq(areaId), eq(recordId), any(AssignedPersonnelUpdateRequest.class), eq(fmEmail)))
                .thenThrow(new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_001));

        mockMvc.perform(patch("/api/areas/{areaId}/assigned-personnel/{id}", areaId, recordId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR_AP_001"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("PATCH /{id}/revoke thu hồi hợp lệ → 200 OK")
    void revoke_Valid_Returns200() throws Exception {
        AssignedPersonnelRevokeRequest request = new AssignedPersonnelRevokeRequest("Chuyển công tác");

        when(service.revoke(eq(areaId), eq(recordId), any(AssignedPersonnelRevokeRequest.class), eq(fmEmail)))
                .thenReturn(sampleResponse(AssignedPersonnelStatus.REVOKED));

        mockMvc.perform(patch("/api/areas/{areaId}/assigned-personnel/{id}/revoke", areaId, recordId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("PATCH /{id}/revoke khi {id} không thuộc {areaId} → 404 Not Found")
    void revoke_RecordNotInArea_Returns404() throws Exception {
        AssignedPersonnelRevokeRequest request = new AssignedPersonnelRevokeRequest("Chuyển công tác");

        when(service.revoke(eq(areaId), eq(recordId), any(AssignedPersonnelRevokeRequest.class), eq(fmEmail)))
                .thenThrow(new AssignedPersonnelException(AssignedPersonnelErrorCode.ERR_AP_001));

        mockMvc.perform(patch("/api/areas/{areaId}/assigned-personnel/{id}/revoke", areaId, recordId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ERR_AP_001"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("PATCH /{id}/revoke lý do trống → 400 Bad Request (Bean Validation)")
    void revoke_BlankReason_Returns400() throws Exception {
        AssignedPersonnelRevokeRequest request = new AssignedPersonnelRevokeRequest("   ");

        mockMvc.perform(patch("/api/areas/{areaId}/assigned-personnel/{id}/revoke", areaId, recordId)
                        .principal(auth)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
