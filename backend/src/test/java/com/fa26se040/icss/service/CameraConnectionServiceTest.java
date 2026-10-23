package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraStreamConfigRequest;
import com.fa26se040.icss.dto.camera.TestConnectionResponse;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraHealthLogRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import com.fa26se040.icss.util.RtspCheckResult;
import com.fa26se040.icss.util.RtspHandshakeChecker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CameraConnectionServiceTest {

    @Mock
    private CameraRepository cameraRepository;

    @Mock
    private CameraStreamConfigurationRepository streamConfigRepo;

    @Mock
    private CameraHealthLogRepository healthLogRepo;

    @Mock
    private CameraService cameraService;

    @Mock
    private RtspHandshakeChecker rtspHandshakeChecker;

    @InjectMocks
    private CameraConnectionService cameraConnectionService;

    private UUID cameraId;
    private Camera camera;
    private CameraStreamConfiguration streamConfig;

    @BeforeEach
    void setUp() {
        cameraId = UUID.randomUUID();
        camera = Camera.builder()
                .id(cameraId)
                .cameraCode("CAM-01")
                .name("Camera Entrance")
                .operationalStatus(OperationalStatus.OFFLINE)
                .build();

        streamConfig = CameraStreamConfiguration.builder()
                .camera(camera)
                .host("192.168.1.100")
                .port(554)
                .mainStreamPath("/live")
                .timeoutMs(3000)
                .build();
    }

    @Test
    @DisplayName("Phân tầng 1 (Happy Path): Native RTSP Handshake thành công (200 OK) - Trả về success=true, latencyMs, KHÔNG đổi operationalStatus và KHÔNG ghi health log")
    void testConnection_Success_ShouldNotMutateCameraOrWriteHealthLog() {
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));

        RtspCheckResult checkResult = RtspCheckResult.builder()
                .reachable(true)
                .statusCode(200)
                .statusText("OK")
                .latencyMs(25L)
                .build();
        when(rtspHandshakeChecker.check("192.168.1.100", 554, "/live", 3000)).thenReturn(checkResult);

        TestConnectionResponse response = cameraConnectionService.testConnection(cameraId);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(25L, response.getLatencyMs());
        assertEquals(OperationalStatus.ONLINE, response.getOperationalStatus());

        // Kiểm tra tính idempotent/read-only: Không được gọi cameraRepository.save hoặc healthLogRepo.save
        verify(cameraRepository, never()).save(any());
        verify(healthLogRepo, never()).save(any());
        assertEquals(OperationalStatus.OFFLINE, camera.getOperationalStatus(), "Trạng thái camera không được thay đổi khi chỉ test kết nối");
    }

    @Test
    @DisplayName("Phân tầng 1 (Happy Path with Auth): Camera phản hồi 401 Unauthorized -> Vẫn xác nhận camera online và sẵn sàng nhận thông tin xác thực")
    void testConnection_401Unauthorized_ShouldReportOnlinePortAvailable() {
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));

        RtspCheckResult checkResult = RtspCheckResult.builder()
                .reachable(true)
                .statusCode(401)
                .statusText("Unauthorized")
                .latencyMs(30L)
                .build();
        when(rtspHandshakeChecker.check("192.168.1.100", 554, "/live", 3000)).thenReturn(checkResult);

        TestConnectionResponse response = cameraConnectionService.testConnection(cameraId);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(30L, response.getLatencyMs());
        assertEquals(OperationalStatus.ONLINE, response.getOperationalStatus());
        verify(cameraRepository, never()).save(any());
        verify(healthLogRepo, never()).save(any());
    }

    @Test
    @DisplayName("Phân tầng 2 (Boundary / Custom Request): testConnection với customReq dùng thông số gửi lên để test, KHÔNG lưu DB")
    void testConnection_WithCustomReq_ShouldUseCustomParamsWithoutMutatingDb() {
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));

        CameraStreamConfigRequest customReq = new CameraStreamConfigRequest();
        customReq.setHost("10.0.0.99");
        customReq.setPort(8554);
        customReq.setUsername("custom_user");
        customReq.setPassword("custom_pass");
        customReq.setMainStreamPath("/stream1");
        customReq.setTimeoutMs(4000);

        RtspCheckResult checkResult = RtspCheckResult.builder()
                .reachable(true)
                .statusCode(200)
                .statusText("OK")
                .latencyMs(15L)
                .build();
        when(rtspHandshakeChecker.check("10.0.0.99", 8554, "/stream1", 4000)).thenReturn(checkResult);

        TestConnectionResponse response = cameraConnectionService.testConnection(cameraId, customReq);

        assertNotNull(response);
        assertTrue(response.isSuccess());
        assertEquals(15L, response.getLatencyMs());

        verify(cameraRepository, never()).save(any());
        verify(healthLogRepo, never()).save(any());
    }

    @Test
    @DisplayName("Phân tầng 3 (Domain / Network Error): Camera mất nguồn / Timeout -> Trả về success=false, errorCode và thông điệp chuẩn không lặp tiền tố")
    void testConnection_TimeoutOrRefused_ShouldReturnFailureMessage() {
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.of(camera));
        when(streamConfigRepo.findByCameraId(cameraId)).thenReturn(Optional.of(streamConfig));

        RtspCheckResult checkResult = RtspCheckResult.builder()
                .reachable(false)
                .latencyMs(3000L)
                .errorCode(com.fa26se040.icss.enums.RtspConnectionErrorCode.ERR_CONN_TIMEOUT)
                .errorMessage("Quá thời gian chờ phản hồi từ camera (Timeout sau 3000ms). Vui lòng kiểm tra IP và nguồn điện camera.")
                .build();
        when(rtspHandshakeChecker.check("192.168.1.100", 554, "/live", 3000)).thenReturn(checkResult);

        TestConnectionResponse response = cameraConnectionService.testConnection(cameraId);

        assertNotNull(response);
        assertFalse(response.isSuccess());
        assertEquals(OperationalStatus.OFFLINE, response.getOperationalStatus());
        assertEquals("ERR_CONN_TIMEOUT", response.getErrorCode());
        assertEquals("Quá thời gian chờ phản hồi từ camera (Timeout sau 3000ms). Vui lòng kiểm tra IP và nguồn điện camera.", response.getMessage());
        assertFalse(response.getMessage().contains("Kiểm tra kết nối thất bại:"));

        verify(cameraRepository, never()).save(any());
        verify(healthLogRepo, never()).save(any());
    }

    @Test
    @DisplayName("Phân tầng 3 (Domain Error): Camera không tồn tại -> Ném CameraException ERR_CAM_002")
    void testConnection_CameraNotFound_ShouldThrowException() {
        when(cameraRepository.findById(cameraId)).thenReturn(Optional.empty());

        CameraException ex = assertThrows(CameraException.class, () -> cameraConnectionService.testConnection(cameraId));
        assertEquals(CameraErrorCode.ERR_CAM_002, ex.getErrorCode());
    }
}
