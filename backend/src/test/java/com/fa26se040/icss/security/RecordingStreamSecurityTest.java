package com.fa26se040.icss.security;

import com.fa26se040.icss.config.SecurityConfig;
import com.fa26se040.icss.controller.CameraController;
import com.fa26se040.icss.service.AnnotatedStreamTokenService;
import com.fa26se040.icss.service.CameraConnectionService;
import com.fa26se040.icss.service.CameraRecordingService;
import com.fa26se040.icss.service.CameraService;
import com.fa26se040.icss.service.CameraSnapshotService;
import com.fa26se040.icss.service.CameraStreamLifecycleService;
import com.fa26se040.icss.service.RecordingStreamTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-03: GET /api/cameras/recordings/stream không còn mở cho người chưa đăng nhập. Thẻ &lt;video&gt; không gửi header
 * Authorization, nên trình duyệt xin token ngắn hạn qua POST /recordings/stream-token (JWT ADMIN / GUARD).
 */
@WebMvcTest(controllers = CameraController.class)
@Import({SecurityConfig.class, RecordingStreamTokenService.class})
@TestPropertySource(properties = {
        "app.cors.allowed-origins=http://localhost:5173",
        "icss.internal.service-key=" + RecordingStreamSecurityTest.KEY,
        "icss.camera.recording-stream-token-seconds=300"
})
class RecordingStreamSecurityTest {

    static final String KEY = "test-only-recording-stream-key-not-secret";
    private static final String PATH = "CAM-001/2026/11/12/10-00-00.mp4";
    private static final String STREAM = "/api/cameras/recordings/stream";
    private static final String TOKEN = "/api/cameras/recordings/stream-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RecordingStreamTokenService recordingStreamTokenService;

    private final ObjectMapper objectMapper = new ObjectMapper();

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
    private AnnotatedStreamTokenService annotatedStreamTokenService;

    @BeforeEach
    void setUp() throws Exception {
        // Header "Bearer <ROLE>" -> đăng nhập giả với role đó (như CameraAnnotatedStreamTokenSecurityTest)
        doAnswer(invocation -> {
            HttpServletRequest req = invocation.getArgument(0);
            String auth = req.getHeader("Authorization");
            if (auth != null && auth.startsWith("Bearer ")) {
                String role = auth.substring(7).trim();
                SecurityContext context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(new UsernamePasswordAuthenticationToken(
                        role.toLowerCase() + "@fpt.edu.vn", "password",
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
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

        when(cameraRecordingService.streamVideo(anyString(), anyString(), any(), anyBoolean()))
                .thenReturn(ResponseEntity.ok().build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private String body(String path, String source) throws Exception {
        return objectMapper.writeValueAsString(new java.util.LinkedHashMap<>(java.util.Map.of(
                "path", path, "source", source)));
    }

    @Test
    @DisplayName("Không token -> 401, không mở file")
    void stream_withoutToken_returns401() throws Exception {
        mockMvc.perform(get(STREAM).param("path", PATH).param("source", "recording"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.httpCode").value(401));

        verify(cameraRecordingService, never()).streamVideo(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Chỉ có JWT, không có token xem video -> 401 (stream chỉ nhận token query)")
    void stream_withJwtButNoStreamToken_returns401() throws Exception {
        mockMvc.perform(get(STREAM).param("path", PATH).header("Authorization", "Bearer ADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Token hết hạn -> 401 'đã hết hạn'")
    void stream_withExpiredToken_returns401() throws Exception {
        String expired = new RecordingStreamTokenService(KEY, -10).issue(PATH, "recording").token();

        mockMvc.perform(get(STREAM).param("path", PATH).param("source", "recording").param("token", expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        "Liên kết xem video đã hết hạn. Vui lòng mở lại video từ danh sách ghi hình."));

        verify(cameraRecordingService, never()).streamVideo(anyString(), anyString(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Token của file khác -> 401")
    void stream_withTokenOfOtherFile_returns401() throws Exception {
        String token = recordingStreamTokenService.issue("CAM-002/2026/11/12/10-00-00.mp4", "recording").token();

        mockMvc.perform(get(STREAM).param("path", PATH).param("source", "recording").param("token", token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Xin token: không JWT -> 401; NORMAL_USER / FACILITY_MANAGER -> 403 (kể cả body rỗng)")
    void issueToken_unauthorizedRoles() throws Exception {
        mockMvc.perform(post(TOKEN).contentType(MediaType.APPLICATION_JSON).content(body(PATH, "recording")))
                .andExpect(status().isUnauthorized());
        for (String role : List.of("NORMAL_USER", "FACILITY_MANAGER")) {
            mockMvc.perform(post(TOKEN).header("Authorization", "Bearer " + role)
                            .contentType(MediaType.APPLICATION_JSON).content(body(PATH, "recording")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.httpCode").value(403));
            mockMvc.perform(post(TOKEN).header("Authorization", "Bearer " + role)
                            .contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("ADMIN / GUARD xin token rồi xem được video; GUARD gửi body rỗng -> 400")
    void issueToken_thenStream_succeeds() throws Exception {
        for (String role : List.of("ADMIN", "GUARD")) {
            String json = mockMvc.perform(post(TOKEN).header("Authorization", "Bearer " + role)
                            .contentType(MediaType.APPLICATION_JSON).content(body(PATH, "minio_recording")))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode data = objectMapper.readTree(json).path("data");
            String token = data.path("token").asText();

            mockMvc.perform(get(STREAM).param("path", PATH).param("source", "minio_recording")
                            .param("download", "true").param("token", token))
                    .andExpect(status().isOk());
        }
        verify(cameraRecordingService, org.mockito.Mockito.times(2))
                .streamVideo(eq(PATH), eq("minio_recording"), any(), eq(true));

        mockMvc.perform(post(TOKEN).header("Authorization", "Bearer GUARD")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }
}
