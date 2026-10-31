package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraStreamConfigRequest;
import com.fa26se040.icss.dto.camera.TestConnectionResponse;
import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CameraConnectionService {

    private final CameraRepository cameraRepository;
    private final CameraStreamConfigurationRepository streamConfigRepo;
    private final com.fa26se040.icss.util.RtspHandshakeChecker rtspHandshakeChecker;

    @Autowired(required = false)
    private EdgeMqttControlService edgeMqttControlService;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Value("${camera.rtsp.timeout-ms:10000}")
    private int defaultTimeoutMs;

    @Transactional(readOnly = true)
    public TestConnectionResponse testConnection(UUID cameraId) {
        return testConnection(cameraId, null);
    }

    @Transactional(readOnly = true)
    public TestConnectionResponse testConnection(UUID cameraId, CameraStreamConfigRequest customReq) {
        Camera camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new CameraException(CameraErrorCode.ERR_CAM_002));

        CameraStreamConfiguration config = streamConfigRepo.findByCameraId(cameraId).orElse(null);

        String host;
        int port = 554;
        String path = "/";
        int timeout = defaultTimeoutMs > 0 ? defaultTimeoutMs : 5000;

        if (customReq != null && customReq.getHost() != null && !customReq.getHost().isBlank()) {
            host = customReq.getHost().trim();
            if (customReq.getPort() != null && customReq.getPort() > 0) {
                port = customReq.getPort();
            }
            if (customReq.getMainStreamPath() != null && !customReq.getMainStreamPath().isBlank()) {
                path = customReq.getMainStreamPath().trim();
            }
        } else {
            if (config == null) {
                throw new CameraException(CameraErrorCode.ERR_STREAM_001);
            }
            host = config.getHost();
            if (config.getPort() != null && config.getPort() > 0) {
                port = config.getPort();
            }
            if (config.getMainStreamPath() != null && !config.getMainStreamPath().isBlank()) {
                path = config.getMainStreamPath().trim();
            }
        }

        // Cloud-Edge Mode: Ủy quyền cho Edge Agent kiểm tra kết nối RTSP nội bộ (sau NAT)
        if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
            return testConnectionViaEdgeMqttRpc(camera.getCameraCode(), host, port, path, timeout);
        }

        try {
            com.fa26se040.icss.util.RtspCheckResult result = rtspHandshakeChecker.check(host, port, path, timeout);
            if (result.isReachable()) {
                return TestConnectionResponse.builder()
                        .success(true)
                        .operationalStatus(OperationalStatus.ONLINE)
                        .latencyMs(result.getLatencyMs())
                        .message("Kết nối thành công tới cổng RTSP (Độ trễ: " + result.getLatencyMs() + "ms)")
                        .build();
            } else {
                String errMsg = result.getErrorMessage() != null ? result.getErrorMessage() : "Không nhận được phản hồi từ camera";
                String errCode = result.getErrorCode() != null ? result.getErrorCode().getCode() : null;
                return TestConnectionResponse.builder()
                        .success(false)
                        .operationalStatus(OperationalStatus.OFFLINE)
                        .latencyMs(result.getLatencyMs() > 0 ? result.getLatencyMs() : null)
                        .errorCode(errCode)
                        .message(errMsg)
                        .build();
            }
        } catch (CameraException ce) {
            throw ce;
        } catch (Exception e) {
            log.error("Lỗi khi kiểm tra kết nối RTSP cho camera [{}]: {}", cameraId, e.getMessage());
            return TestConnectionResponse.builder()
                    .success(false)
                    .operationalStatus(OperationalStatus.OFFLINE)
                    .errorCode("ERR_CONN_UNKNOWN")
                    .message("Lỗi hệ thống khi kiểm tra kết nối: " + e.getMessage())
                    .build();
        }
    }

    private TestConnectionResponse testConnectionViaEdgeMqttRpc(
            String cameraCode,
            String host,
            int port,
            String path,
            int timeoutMs
    ) {
        try {
            long rpcTimeout = Math.min(Math.max(timeoutMs, 3000L), 8000L);
            EdgeRpcReplyDto reply = edgeMqttControlService.sendRpcRequest(
                    "TEST_CONNECTION",
                    cameraCode,
                    Map.of(
                            "host", host,
                            "port", port,
                            "path", path,
                            "timeout_ms", timeoutMs
                    ),
                    rpcTimeout
            );
            if (reply != null && reply.isSuccess()) {
                long lat = reply.getLatencyMs() != null ? reply.getLatencyMs() : 0L;
                return TestConnectionResponse.builder()
                        .success(true)
                        .operationalStatus(OperationalStatus.ONLINE)
                        .latencyMs(lat)
                        .message("Kết nối thành công tới camera qua trạm Edge (Độ trễ: " + lat + "ms)")
                        .build();
            }
            String errCode = reply != null && reply.getErrorCode() != null ? reply.getErrorCode() : "ERR_CONN_TIMEOUT";
            String errMsg = reply != null && reply.getErrorMessage() != null
                    ? reply.getErrorMessage()
                    : "Không nhận được phản hồi từ camera tại mạng nội bộ";
            return TestConnectionResponse.builder()
                    .success(false)
                    .operationalStatus(OperationalStatus.OFFLINE)
                    .latencyMs(reply != null ? reply.getLatencyMs() : null)
                    .errorCode(errCode)
                    .message(errMsg)
                    .build();
        } catch (CameraException ce) {
            return TestConnectionResponse.builder()
                    .success(false)
                    .operationalStatus(OperationalStatus.OFFLINE)
                    .errorCode(ce.getErrorCode().getCode())
                    .message(ce.getMessage())
                    .build();
        }
    }
}
