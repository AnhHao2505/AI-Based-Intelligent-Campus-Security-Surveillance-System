package com.fa26se040.icss.security;

import com.fa26se040.icss.config.SecurityConfig;
import com.fa26se040.icss.controller.CameraController;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.enums.CameraStatus;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.service.*;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CameraController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5173",
        "icss.internal.service-key=0123456789abcdef0123456789abcdef"
})
class CameraAnnotatedStreamTokenSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @MockBean
    private InternalServiceKeyFilter internalServiceKeyFilter;

    @MockBean
    private CameraService cameraService;

    @MockBean
    private CameraConnectionService cameraConnectionService;

    @MockBean
    private CameraSnapshotService cameraSnapshotService;

    @MockBean
    private CameraStreamLifecycleService cameraStreamLifecycleService;

    @MockBean
    private CameraRecordingService cameraRecordingService;

    @MockBean
    private CameraRepository cameraRepository;

    @MockBean
    private AnnotatedStreamTokenService annotatedStreamTokenService;

    private final String cameraCode = "CAM-001";

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(invocation -> {
            HttpServletRequest req = invocation.getArgument(0);
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String role = auth.substring(7).trim();
                Authentication authentication = new UsernamePasswordAuthenticationToken(
                        role.toLowerCase() + "@fpt.edu.vn",
                        "password",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))
                );
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(authentication);
                SecurityContextHolder.setContext(context);
            }
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        doAnswer(invocation -> {
            FilterChain chain = invocation.getArgument(2);
            chain.doFilter(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(internalServiceKeyFilter).doFilter(any(), any(), any());

        Camera mockCamera = Camera.builder()
                .id(UUID.randomUUID())
                .cameraCode(cameraCode)
                .name("Camera Cổng")
                .status(CameraStatus.ACTIVE)
                .build();

        when(cameraRepository.findByCameraCodeAndDeletedAtIsNull(eq(cameraCode)))
                .thenReturn(Optional.of(mockCamera));

        when(annotatedStreamTokenService.issue(eq(cameraCode)))
                .thenReturn(new AnnotatedStreamTokenService.TokenResponse("1893456000.sampletoken", 1893456000L));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("T5: Không có JWT token -> 401 Unauthorized")
    void getAnnotatedStreamToken_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/cameras/{cameraCode}/annotated-stream-token", cameraCode))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("T5: NORMAL_USER không có quyền xem luồng camera -> 403 Forbidden")
    void getAnnotatedStreamToken_normalUser_returns403() throws Exception {
        mockMvc.perform(post("/api/cameras/{cameraCode}/annotated-stream-token", cameraCode)
                        .header("Authorization", "Bearer NORMAL_USER"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("T5: GUARD có quyền lấy token xem luồng live-annotated -> 200 OK")
    void getAnnotatedStreamToken_guard_returns200() throws Exception {
        mockMvc.perform(post("/api/cameras/{cameraCode}/annotated-stream-token", cameraCode)
                        .header("Authorization", "Bearer GUARD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").value("1893456000.sampletoken"))
                .andExpect(jsonPath("$.data.expiresAt").value(1893456000L));
    }
}
