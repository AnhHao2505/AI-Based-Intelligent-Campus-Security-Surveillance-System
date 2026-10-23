package com.fa26se040.icss.util;

import com.fa26se040.icss.enums.RtspConnectionErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class RtspHandshakeChecker {

    /**
     * Mở socket TCP kết nối trực tiếp đến camera RTSP port và gửi lệnh OPTIONS.
     * Hoàn toàn không phụ thuộc AI-service, không giải mã video, phản hồi chỉ mất từ 10ms - 50ms.
     */
    public RtspCheckResult check(String host, int port, String path, int timeoutMs) {
        if (host == null || host.isBlank()) {
            return RtspCheckResult.builder()
                    .reachable(false)
                    .errorCode(RtspConnectionErrorCode.ERR_CONN_HOST_EMPTY)
                    .errorMessage(RtspConnectionErrorCode.ERR_CONN_HOST_EMPTY.getTemplate())
                    .build();
        }

        int effectivePort = port > 0 ? port : 554;
        int effectiveTimeout = timeoutMs > 0 ? timeoutMs : 3000;
        String effectivePath = (path != null && !path.isBlank()) ? path : "/";
        if (!effectivePath.startsWith("/")) {
            effectivePath = "/" + effectivePath;
        }

        long startTime = System.currentTimeMillis();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, effectivePort), effectiveTimeout);
            socket.setSoTimeout(effectiveTimeout);

            String rtspUrl = "rtsp://" + host + ":" + effectivePort + effectivePath;
            String request = "OPTIONS " + rtspUrl + " RTSP/1.0\r\n" +
                    "CSeq: 1\r\n" +
                    "User-Agent: ICSS-Camera-Tester/1.0\r\n\r\n";

            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String statusLine = reader.readLine();

            long latencyMs = System.currentTimeMillis() - startTime;

            if (statusLine != null && statusLine.startsWith("RTSP/")) {
                String[] parts = statusLine.split(" ", 3);
                int statusCode = 200;
                String statusText = "OK";
                if (parts.length >= 2) {
                    try {
                        statusCode = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException ignored) {}
                }
                if (parts.length >= 3) {
                    statusText = parts[2];
                }

                // Cả 200 OK và 401 Unauthorized đều chứng minh camera đang mở cổng RTSP thành công
                boolean reachable = (statusCode == 200 || statusCode == 401);

                return RtspCheckResult.builder()
                        .reachable(reachable)
                        .latencyMs(latencyMs)
                        .statusCode(statusCode)
                        .statusText(statusText)
                        .errorCode(reachable ? null : RtspConnectionErrorCode.ERR_CONN_RTSP_ERROR)
                        .errorMessage(reachable ? null : RtspConnectionErrorCode.ERR_CONN_RTSP_ERROR.format("statusCode", statusCode, "statusText", statusText))
                        .build();
            } else {
                return RtspCheckResult.builder()
                        .reachable(false)
                        .latencyMs(latencyMs)
                        .errorCode(RtspConnectionErrorCode.ERR_CONN_INVALID_PROTOCOL)
                        .errorMessage(RtspConnectionErrorCode.ERR_CONN_INVALID_PROTOCOL.format("port", effectivePort))
                        .build();
            }

        } catch (SocketTimeoutException ste) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.warn("RTSP Handshake timeout to {}:{} after {}ms", host, effectivePort, latencyMs);
            return RtspCheckResult.builder()
                    .reachable(false)
                    .latencyMs(latencyMs)
                    .errorCode(RtspConnectionErrorCode.ERR_CONN_TIMEOUT)
                    .errorMessage(RtspConnectionErrorCode.ERR_CONN_TIMEOUT.format("timeout", effectiveTimeout))
                    .build();
        } catch (ConnectException ce) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.warn("RTSP connection refused to {}:{}: {}", host, effectivePort, ce.getMessage());
            return RtspCheckResult.builder()
                    .reachable(false)
                    .latencyMs(latencyMs)
                    .errorCode(RtspConnectionErrorCode.ERR_CONN_PORT_REFUSED)
                    .errorMessage(RtspConnectionErrorCode.ERR_CONN_PORT_REFUSED.format("port", effectivePort))
                    .build();
        } catch (UnknownHostException | NoRouteToHostException nre) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.warn("RTSP host unreachable {}:{}: {}", host, effectivePort, nre.getMessage());
            return RtspCheckResult.builder()
                    .reachable(false)
                    .latencyMs(latencyMs)
                    .errorCode(RtspConnectionErrorCode.ERR_CONN_HOST_UNREACHABLE)
                    .errorMessage(RtspConnectionErrorCode.ERR_CONN_HOST_UNREACHABLE.format("host", host))
                    .build();
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.warn("RTSP Handshake failed to {}:{}: {}", host, effectivePort, e.getMessage());
            String rawMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();

            RtspConnectionErrorCode errCode;
            String formattedMsg;
            if (rawMsg.contains("Connection refused") || rawMsg.contains("refused")) {
                errCode = RtspConnectionErrorCode.ERR_CONN_PORT_REFUSED;
                formattedMsg = errCode.format("port", effectivePort);
            } else if (rawMsg.contains("No route to host") || rawMsg.contains("unreachable")) {
                errCode = RtspConnectionErrorCode.ERR_CONN_HOST_UNREACHABLE;
                formattedMsg = errCode.format("host", host);
            } else {
                errCode = RtspConnectionErrorCode.ERR_CONN_UNKNOWN;
                formattedMsg = errCode.format("detail", rawMsg);
            }

            return RtspCheckResult.builder()
                    .reachable(false)
                    .latencyMs(latencyMs)
                    .errorCode(errCode)
                    .errorMessage(formattedMsg)
                    .build();
        }
    }
}
