package com.fa26se040.icss.controller;

import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.service.CameraConnectionService;
import com.fa26se040.icss.service.CameraRecordingService;
import com.fa26se040.icss.service.CameraService;
import com.fa26se040.icss.service.CameraStreamLifecycleService;
import com.fa26se040.icss.service.AnnotatedStreamTokenService;
import com.fa26se040.icss.service.RecordingStreamTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.web.SpringJUnitWebConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(CameraControllerSecurityTest.TestSecurityConfig.class)
class CameraControllerSecurityTest {

    @Configuration
    @EnableWebMvc
    @EnableMethodSecurity
    @org.springframework.data.web.config.EnableSpringDataWebSupport
    static class TestSecurityConfig {
        @Bean
        CameraService cameraService() { return mock(CameraService.class); }

        @Bean
        CameraConnectionService cameraConnectionService() { return mock(CameraConnectionService.class); }

        @Bean
        CameraStreamLifecycleService cameraStreamLifecycleService() { return mock(CameraStreamLifecycleService.class); }

        @Bean
        CameraRecordingService cameraRecordingService() { return mock(CameraRecordingService.class); }

        @Bean
        AnnotatedStreamTokenService annotatedStreamTokenService() { return mock(AnnotatedStreamTokenService.class); }

        @Bean
        RecordingStreamTokenService recordingStreamTokenService() { return mock(RecordingStreamTokenService.class); }

        @Bean
        CameraController cameraController(
                CameraService cameraService,
                CameraConnectionService cameraConnectionService,
                CameraStreamLifecycleService cameraStreamLifecycleService,
                CameraRecordingService cameraRecordingService,
                AnnotatedStreamTokenService annotatedStreamTokenService,
                RecordingStreamTokenService recordingStreamTokenService
        ) {
            return new CameraController(
                    cameraService,
                    cameraConnectionService,
                    cameraStreamLifecycleService,
                    cameraRecordingService,
                    annotatedStreamTokenService,
                    recordingStreamTokenService
            );
        }

        @Bean
        GlobalExceptionHandler globalExceptionHandler() {
            return new GlobalExceptionHandler();
        }
    }

    @Autowired
    private WebApplicationContext wac;

    @Autowired
    private CameraStreamLifecycleService lifecycleService;

    @Autowired
    private CameraRecordingService recordingService;

    @Autowired
    private AnnotatedStreamTokenService annotatedStreamTokenService;

    @Autowired
    private CameraService cameraService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(wac).build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWithRole(String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(
                        "user@fpt.edu.vn",
                        "N/A",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                )
        );
    }

    @Test
    @DisplayName("1. Security Access (200 OK): GUARD được phép kích hoạt Cloud Stream Relay và xem danh sách Recordings")
    void guardRole_canToggleCloudRelayAndListRecordings() throws Exception {
        authenticateWithRole("GUARD");
        when(recordingService.listRecordings(any(), any(), any(), any())).thenReturn(List.of());

        mockMvc.perform(post("/api/cameras/stream/cloud-relay")
                        .param("cameraCode", "CAM-001")
                        .param("active", "true"))
                .andExpect(status().isOk());

        verify(lifecycleService).requestCloudRelay("CAM-001", true);

        mockMvc.perform(get("/api/cameras/recordings"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("2. Security Access (403 Forbidden): NORMAL_USER bị từ chối truy cập Cloud Stream Relay và Recordings")
    void normalUserRole_isForbiddenFromCloudRelayAndRecordings() throws Exception {
        authenticateWithRole("NORMAL_USER");

        mockMvc.perform(post("/api/cameras/stream/cloud-relay")
                        .param("cameraCode", "CAM-001")
                        .param("active", "true"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/cameras/recordings"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("3. Security Access (403 vs 200): GET /api/cameras/{id} bị chặn với FACILITY_MANAGER (403) và cho phép ADMIN (200)")
    void getCameraById_facilityManagerForbidden_adminAllowed() throws Exception {
        java.util.UUID cameraId = java.util.UUID.randomUUID();
        when(cameraService.getCameraDetail(cameraId)).thenReturn(
                com.fa26se040.icss.dto.camera.CameraDetailResponse.builder().id(cameraId).cameraCode("CAM-001").build()
        );

        authenticateWithRole("FACILITY_MANAGER");
        mockMvc.perform(get("/api/cameras/{id}", cameraId))
                .andExpect(status().isForbidden());

        authenticateWithRole("ADMIN");
        mockMvc.perform(get("/api/cameras/{id}", cameraId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("4. Map Location Security: FACILITY_MANAGER bị chặn PATCH/DELETE (403) nhưng vẫn xem được tọa độ qua GET /api/cameras (200); ADMIN được phép PATCH/DELETE (200)")
    void mapLocationEndpoints_rbacVerification() throws Exception {
        java.util.UUID cameraId = java.util.UUID.randomUUID();
        com.fa26se040.icss.dto.camera.CameraDetailResponse detail =
                com.fa26se040.icss.dto.camera.CameraDetailResponse.builder()
                        .id(cameraId)
                        .cameraCode("CAM-019")
                        .mapLongitude(108.26105)
                        .mapLatitude(15.96885)
                        .mapBuilding("Tòa Epsilon")
                        .mapFloorOrder(2)
                        .build();
        when(cameraService.updateMapLocation(eq(cameraId), any())).thenReturn(detail);
        when(cameraService.clearMapLocation(cameraId)).thenReturn(
                com.fa26se040.icss.dto.camera.CameraDetailResponse.builder()
                        .id(cameraId)
                        .cameraCode("CAM-019")
                        .build()
        );
        com.fa26se040.icss.dto.camera.CameraSimpleResponse simpleItem =
                com.fa26se040.icss.dto.camera.CameraSimpleResponse.builder()
                        .id(cameraId)
                        .cameraCode("CAM-019")
                        .name("ADAD")
                        .mapLongitude(108.26105)
                        .mapLatitude(15.96885)
                        .mapBuilding("Tòa Epsilon")
                        .mapFloorOrder(2)
                        .build();
        when(cameraService.getAllActiveSimple()).thenReturn(List.of(simpleItem));

        String body = """
                {
                  "longitude": 108.26105,
                  "latitude": 15.96885,
                  "building": "Tòa Epsilon",
                  "floorOrder": 2
                }
                """;

        authenticateWithRole("FACILITY_MANAGER");
        mockMvc.perform(patch("/api/cameras/{id}/map-location", cameraId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/cameras/{id}/map-location", cameraId))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/cameras/all-simple"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].mapLongitude").value(108.26105))
                .andExpect(jsonPath("$.data[0].mapFloorOrder").value(2));

        authenticateWithRole("ADMIN");
        mockMvc.perform(patch("/api/cameras/{id}/map-location", cameraId)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mapLongitude").value(108.26105));

        mockMvc.perform(delete("/api/cameras/{id}/map-location", cameraId))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("WHEP/RTSP/API Auth (F-01): Chặn RTSP ẩn danh hoặc playback/api trái phép, chỉ cho phép RTSP read/publish hoặc API nội bộ có HMAC hợp lệ và WebRTC có token")
    void authenticateWhepStream_ShouldRejectAnonymousRtspAndAllowValidCredentials() throws Exception {
        when(annotatedStreamTokenService.verifyToken("cam-001", "valid-whep-token")).thenReturn(true);
        when(annotatedStreamTokenService.verifyInternalRtspSecret("cam-001", "valid-hmac")).thenReturn(true);
        when(annotatedStreamTokenService.verifyInternalApiSecret("valid-api-hmac")).thenReturn(true);

        // 1. RTSP ẩn danh (VLC / FFmpeg bên ngoài) -> 401 Unauthorized
        mockMvc.perform(post("/api/cameras/whep-auth")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"protocol":"rtsp","action":"read","path":"cam-001","user":"","password":""}
                                """))
                .andExpect(status().isUnauthorized());

        // 2. RTSP publish ẩn danh -> 401 Unauthorized
        mockMvc.perform(post("/api/cameras/whep-auth")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"protocol":"rtsp","action":"publish","path":"cam-001","user":"","password":""}
                                """))
                .andExpect(status().isUnauthorized());

        // 3. RTSP nội bộ đúng user + action=read/publish + HMAC hợp lệ -> 200 OK
        mockMvc.perform(post("/api/cameras/whep-auth")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"protocol":"rtsp","action":"read","path":"cam-001","user":"icss-internal","password":"valid-hmac"}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/cameras/whep-auth")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"protocol":"rtsp","action":"publish","path":"cam-001","user":"icss-internal","password":"valid-hmac"}
                                """))
                .andExpect(status().isOk());

        // 4. MediaMTX Control API (action=api): chặn ẩn danh, cho phép icss-internal với API HMAC
        mockMvc.perform(post("/api/cameras/whep-auth")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"protocol":"http","action":"api","path":"","user":"","password":""}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/cameras/whep-auth")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"protocol":"http","action":"api","path":"","user":"icss-internal","password":"valid-api-hmac"}
                                """))
                .andExpect(status().isOk());

        // 5. Playback bị chặn -> 401 Unauthorized
        mockMvc.perform(post("/api/cameras/whep-auth")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"protocol":"http","action":"playback","path":"cam-001","user":"","password":""}
                                """))
                .andExpect(status().isUnauthorized());

        // 6. WebRTC WHEP có stream token hợp lệ -> 200 OK
        mockMvc.perform(post("/api/cameras/whep-auth")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("""
                                {"protocol":"webrtc","action":"read","path":"cam-001","query":"token=valid-whep-token"}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("F-06: GUARD/FACILITY_MANAGER gọi GET /api/cameras?forceSync=true bị hạ về forceSync=false; chỉ ADMIN được forceSync=true")
    void listCameras_forceSyncOnlyEffectiveForAdmin() throws Exception {
        when(cameraService.listCameras(any(), any(), any(), any(), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(
                        List.of(), org.springframework.data.domain.PageRequest.of(0, 10), 0));

        authenticateWithRole("GUARD");
        mockMvc.perform(get("/api/cameras")
                        .principal(SecurityContextHolder.getContext().getAuthentication())
                        .param("forceSync", "true"))
                .andExpect(status().isOk());
        verify(cameraService).listCameras(any(), any(), any(), eq(false), any());

        authenticateWithRole("ADMIN");
        mockMvc.perform(get("/api/cameras")
                        .principal(SecurityContextHolder.getContext().getAuthentication())
                        .param("forceSync", "true"))
                .andExpect(status().isOk());
        verify(cameraService).listCameras(any(), any(), any(), eq(true), any());
    }
}
