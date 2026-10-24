package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.camera.CameraStreamConfigRequest;
import com.fa26se040.icss.dto.camera.ConnectStreamResponse;
import com.fa26se040.icss.dto.camera.TestConnectionResponse;
import com.fa26se040.icss.entity.Camera;
import com.fa26se040.icss.entity.CameraHealthLog;
import com.fa26se040.icss.entity.CameraStreamConfiguration;
import com.fa26se040.icss.enums.OperationalStatus;
import com.fa26se040.icss.exception.CameraErrorCode;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.repository.CameraHealthLogRepository;
import com.fa26se040.icss.repository.CameraRepository;
import com.fa26se040.icss.repository.CameraStreamConfigurationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class CameraConnectionService {

    private final CameraRepository cameraRepository;
    private final CameraStreamConfigurationRepository streamConfigRepo;
    private final com.fa26se040.icss.util.RtspHandshakeChecker rtspHandshakeChecker;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Value("${camera.rtsp.timeout-ms:10000}")
    private int defaultTimeoutMs;

    /**
     * "Thử kết nối": Chỉ kiểm tra kết nối luồng RTSP qua AI-service.
     * KHÔNG thay đổi operationalStatus, KHÔNG ghi health log, KHÔNG lưu snapshot.
     */
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
        int timeout = defaultTimeoutMs;

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
                    .errorCode("ERR_CONN_SYSTEM_ERROR")
                    .message(cleanErrorMessage(e))
                    .build();
        }
    }

    private String cleanErrorMessage(Exception e) {
        if (e instanceof org.springframework.web.client.HttpStatusCodeException hs) {
            int code = hs.getStatusCode().value();
            return switch (code) {
                case 504 -> "Quá thời gian chờ kết nối (504 Timeout). Vui lòng kiểm tra lại địa chỉ IP, cổng mạng và nguồn camera.";
                case 502 -> "Không thể kết nối đến cổng AI hoặc MediaMTX Gateway (502 Bad Gateway). Dịch vụ nền có thể chưa sẵn sàng.";
                case 401, 403 -> "Sai thông tin đăng nhập camera (401/403). Vui lòng kiểm tra lại tài khoản hoặc mật khẩu RTSP.";
                case 404 -> "Không tìm thấy luồng RTSP (404 Not Found). Vui lòng kiểm tra lại đuôi đường dẫn Main Stream Path.";
                case 500 -> "Lỗi nội bộ khi khởi tạo luồng giải mã video qua AI-service.";
                default -> hs.getStatusCode().value() + " " + hs.getStatusText();
            };
        }
        if (e instanceof org.springframework.web.client.ResourceAccessException rae) {
            String raeMsg = rae.getMessage() != null ? rae.getMessage().toLowerCase() : "";
            if (raeMsg.contains("timed out") || raeMsg.contains("timeout")) {
                return "Quá thời gian chờ phản hồi từ camera (Timeout). Vui lòng kiểm tra lại địa chỉ IP và nguồn điện camera.";
            }
            if (raeMsg.contains("refused") || raeMsg.contains("connect")) {
                return "Không thể kết nối đến AI-service. Vui lòng kiểm tra dịch vụ AI trên cổng cấu hình.";
            }
            return "Không thể thiết lập kết nối mạng tới camera hoặc dịch vụ AI.";
        }
        String msg = e.getMessage();
        if (msg != null) {
            msg = msg.replaceAll("([a-zA-Z][a-zA-Z0-9+.-]*://)[^/\\s@]+@", "$1****@");
            if (msg.contains("504") || msg.toLowerCase().contains("gateway timeout")) {
                return "Quá thời gian chờ kết nối (504 Timeout). Vui lòng kiểm tra lại địa chỉ IP, cổng mạng và nguồn camera.";
            }
            if (msg.contains("502") || msg.toLowerCase().contains("bad gateway")) {
                return "Không thể kết nối đến AI-service hoặc MediaMTX Gateway (502 Bad Gateway).";
            }
            return msg.replaceAll("(?i):\\s*\"Internal Server Error\"", "")
                      .replaceAll("(?i)500 Internal Server Error: \"Internal Server Error\"", "500 Internal Server Error");
        }
        return "Lỗi kết nối không xác định";
    }
}
