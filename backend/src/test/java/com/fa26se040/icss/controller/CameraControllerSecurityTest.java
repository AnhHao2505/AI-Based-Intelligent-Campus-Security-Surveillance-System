package com.fa26se040.icss.controller;

import com.fa26se040.icss.exception.GlobalExceptionHandler;
import com.fa26se040.icss.service.CameraConnectionService;
import com.fa26se040.icss.service.CameraRecordingService;
import com.fa26se040.icss.service.CameraService;
import com.fa26se040.icss.service.CameraSnapshotService;
import com.fa26se040.icss.service.CameraStreamLifecycleService;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringJUnitWebConfig(CameraControllerSecurityTest.TestSecurityConfig.class)
class CameraControllerSecurityTest {

    @Configuration
    @EnableWebMvc
    @EnableMethodSecurity
    static class TestSecurityConfig {
        @Bean
        CameraService cameraService() { return mock(CameraService.class); }

        @Bean
        CameraConnectionService cameraConnectionService() { return mock(CameraConnectionService.class); }

        @Bean
        CameraSnapshotService cameraSnapshotService() { return mock(CameraSnapshotService.class); }

        @Bean
        CameraStreamLifecycleService cameraStreamLifecycleService() { return mock(CameraStreamLifecycleService.class); }

        @Bean
        CameraRecordingService cameraRecordingService() { return mock(CameraRecordingService.class); }

        @Bean
        CameraController cameraController(
                CameraService cameraService,
                CameraConnectionService cameraConnectionService,
                CameraSnapshotService cameraSnapshotService,
                CameraStreamLifecycleService cameraStreamLifecycleService,
                CameraRecordingService cameraRecordingService
        ) {
            return new CameraController(
                    cameraService,
                    cameraConnectionService,
                    cameraSnapshotService,
                    cameraStreamLifecycleService,
                    cameraRecordingService
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
}
