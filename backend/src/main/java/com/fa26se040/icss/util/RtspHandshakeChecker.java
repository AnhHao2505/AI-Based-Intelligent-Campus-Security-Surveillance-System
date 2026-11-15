package com.fa26se040.icss.util;

import com.fa26se040.icss.enums.RtspConnectionErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class RtspHandshakeChecker {

    private static final int MAX_SDP_BYTES = 16384;
    private static final Pattern REALM_PATTERN = Pattern.compile("realm=\"([^\"]+)\"");
    private static final Pattern NONCE_PATTERN = Pattern.compile("nonce=\"([^\"]+)\"");
    private static final Pattern RTPMAP_PATTERN = Pattern.compile("a=rtpmap:\\d+\\s+([A-Za-z0-9_-]+)/\\d+", Pattern.CASE_INSENSITIVE);

    @Value("${camera.rtsp.timeout-ms:10000}")
    private int defaultTimeoutMs;

    /**
     * Mở socket TCP kết nối trực tiếp đến camera RTSP port, kiểm tra OPTIONS và DESCRIBE.
     */
    public RtspCheckResult check(String host, int port, String path, int timeoutMs) {
        return check(host, port, path, null, null, timeoutMs);
    }

    /**
     * Mở socket TCP kết nối trực tiếp đến camera RTSP port, gửi OPTIONS + DESCRIBE (kèm Basic/Digest Auth nếu có).
     */
    public RtspCheckResult check(String host, int port, String path, String username, String password, int timeoutMs) {
        if (host == null || host.isBlank()) {
            return RtspCheckResult.builder()
                    .reachable(false)
                    .errorCode(RtspConnectionErrorCode.ERR_CONN_HOST_EMPTY)
                    .errorMessage(RtspConnectionErrorCode.ERR_CONN_HOST_EMPTY.getTemplate())
                    .build();
        }

        int effectivePort = port > 0 ? port : 554;
        int effectiveTimeout = timeoutMs > 0 ? timeoutMs : defaultTimeoutMs;
        String effectivePath = (path != null && !path.isBlank()) ? path.trim() : "/";
        if (!effectivePath.startsWith("/") && !effectivePath.startsWith("rtsp://") && !effectivePath.startsWith("rtsps://")) {
            effectivePath = "/" + effectivePath;
        }

        long startTime = System.currentTimeMillis();

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, effectivePort), effectiveTimeout);
            socket.setSoTimeout(effectiveTimeout);

            String rtspUrl = effectivePath.startsWith("rtsp://") || effectivePath.startsWith("rtsps://")
                    ? effectivePath
                    : "rtsp://" + host + ":" + effectivePort + effectivePath;

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            OutputStream out = socket.getOutputStream();

            int cseq = 1;
            RtspResponse optionsResp = sendRequest(out, reader, "OPTIONS", rtspUrl, cseq++, null, null);
            long latencyMs = System.currentTimeMillis() - startTime;

            if (optionsResp == null || optionsResp.statusLine == null || !optionsResp.statusLine.startsWith("RTSP/")) {
                return RtspCheckResult.builder()
                        .reachable(false)
                        .latencyMs(latencyMs)
                        .errorCode(RtspConnectionErrorCode.ERR_CONN_INVALID_PROTOCOL)
                        .errorMessage(RtspConnectionErrorCode.ERR_CONN_INVALID_PROTOCOL.format("port", effectivePort))
                        .build();
            }

            if (optionsResp.statusCode != 200 && optionsResp.statusCode != 401) {
                return RtspCheckResult.builder()
                        .reachable(false)
                        .latencyMs(latencyMs)
                        .statusCode(optionsResp.statusCode)
                        .statusText(optionsResp.statusText)
                        .errorCode(RtspConnectionErrorCode.ERR_CONN_RTSP_ERROR)
                        .errorMessage(RtspConnectionErrorCode.ERR_CONN_RTSP_ERROR.format(
                                "statusCode", optionsResp.statusCode, "statusText", optionsResp.statusText))
                        .build();
            }

            // Gửi DESCRIBE để xác thực đường dẫn stream, thông tin đăng nhập và codec video trong SDP
            String authHeader = null;
            if (optionsResp.statusCode == 401 && hasCredentials(username)) {
                authHeader = buildAuthHeader(optionsResp.headers.get("www-authenticate"), "DESCRIBE", rtspUrl, username, password);
            }

            RtspResponse describeResp = sendRequest(out, reader, "DESCRIBE", rtspUrl, cseq++, "Accept: application/sdp\r\n", authHeader);
            if (describeResp != null && describeResp.statusCode == 401 && hasCredentials(username) && authHeader == null) {
                authHeader = buildAuthHeader(describeResp.headers.get("www-authenticate"), "DESCRIBE", rtspUrl, username, password);
                if (authHeader != null) {
                    describeResp = sendRequest(out, reader, "DESCRIBE", rtspUrl, cseq++, "Accept: application/sdp\r\n", authHeader);
                }
            }

            latencyMs = System.currentTimeMillis() - startTime;

            if (describeResp == null || describeResp.statusLine == null || !describeResp.statusLine.startsWith("RTSP/")) {
                // Fallback an toàn nếu server không hỗ trợ DESCRIBE trên cùng kết nối sau OPTIONS 200 OK
                boolean ok = optionsResp.statusCode == 200;
                return RtspCheckResult.builder()
                        .reachable(ok)
                        .latencyMs(latencyMs)
                        .statusCode(optionsResp.statusCode)
                        .statusText(optionsResp.statusText)
                        .errorCode(ok ? null : RtspConnectionErrorCode.ERR_CONN_RTSP_ERROR)
                        .errorMessage(ok ? null : "Camera yêu cầu xác thực RTSP (401 Unauthorized). Vui lòng nhập tài khoản/mật khẩu.")
                        .build();
            }

            if (describeResp.statusCode == 401) {
                String msg = hasCredentials(username)
                        ? "Sai tài khoản hoặc mật khẩu RTSP (401 Unauthorized)."
                        : "Camera yêu cầu tài khoản/mật khẩu RTSP (401 Unauthorized). Vui lòng nhập thông tin xác thực.";
                return RtspCheckResult.builder()
                        .reachable(false)
                        .latencyMs(latencyMs)
                        .statusCode(401)
                        .statusText(describeResp.statusText)
                        .errorCode(RtspConnectionErrorCode.ERR_CONN_RTSP_ERROR)
                        .errorMessage(msg)
                        .build();
            }

            if (describeResp.statusCode != 200) {
                String detail = (describeResp.statusCode == 404 || describeResp.statusCode == 454)
                        ? "Không tìm thấy luồng video tại đường dẫn '" + effectivePath + "' (" + describeResp.statusCode + " " + describeResp.statusText + "). Kiểm tra lại Main Stream Path."
                        : RtspConnectionErrorCode.ERR_CONN_RTSP_ERROR.format("statusCode", describeResp.statusCode, "statusText", describeResp.statusText);
                return RtspCheckResult.builder()
                        .reachable(false)
                        .latencyMs(latencyMs)
                        .statusCode(describeResp.statusCode)
                        .statusText(describeResp.statusText)
                        .errorCode(RtspConnectionErrorCode.ERR_CONN_RTSP_ERROR)
                        .errorMessage(detail)
                        .build();
            }

            String videoCodec = parseVideoCodecFromSdp(describeResp.body);
            String warningMessage = buildCodecWarning(describeResp.body, videoCodec);

            return RtspCheckResult.builder()
                    .reachable(true)
                    .latencyMs(latencyMs)
                    .statusCode(describeResp.statusCode)
                    .statusText(describeResp.statusText)
                    .videoCodec(videoCodec)
                    .warningMessage(warningMessage)
                    .build();

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

    private RtspResponse sendRequest(
            OutputStream out,
            BufferedReader reader,
            String method,
            String rtspUrl,
            int cseq,
            String extraHeaders,
            String authHeader
    ) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(rtspUrl).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq).append("\r\n");
        sb.append("User-Agent: ICSS-Camera-Tester/1.0\r\n");
        if (extraHeaders != null) {
            sb.append(extraHeaders);
        }
        if (authHeader != null) {
            sb.append("Authorization: ").append(authHeader).append("\r\n");
        }
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();

        String statusLine = reader.readLine();
        if (statusLine == null) {
            return null;
        }
        int statusCode = 200;
        String statusText = "OK";
        if (statusLine.startsWith("RTSP/")) {
            String[] parts = statusLine.split(" ", 3);
            if (parts.length >= 2) {
                try {
                    statusCode = Integer.parseInt(parts[1]);
                } catch (NumberFormatException ignored) {}
            }
            if (parts.length >= 3) {
                statusText = parts[2];
            }
        }

        Map<String, String> headers = new HashMap<>();
        String line;
        while ((line = reader.readLine()) != null && !line.isEmpty()) {
            int colonIdx = line.indexOf(':');
            if (colonIdx > 0) {
                String key = line.substring(0, colonIdx).trim().toLowerCase(Locale.ROOT);
                String val = line.substring(colonIdx + 1).trim();
                headers.put(key, val);
            }
        }

        String body = "";
        int contentLength = parseContentLength(headers.get("content-length"));
        if (contentLength > 0 && contentLength <= MAX_SDP_BYTES) {
            char[] buf = new char[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int r = reader.read(buf, totalRead, contentLength - totalRead);
                if (r == -1) break;
                totalRead += r;
            }
            body = new String(buf, 0, totalRead);
        }

        return new RtspResponse(statusLine, statusCode, statusText, headers, body);
    }

    private static int parseContentLength(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean hasCredentials(String username) {
        return username != null && !username.isBlank();
    }

    private static String buildAuthHeader(String challenge, String method, String uri, String username, String password) {
        if (username == null || username.isBlank()) {
            return null;
        }
        String safePass = password != null ? password : "";
        if (challenge != null && challenge.regionMatches(true, 0, "Digest", 0, 6)) {
            Matcher realmMatcher = REALM_PATTERN.matcher(challenge);
            Matcher nonceMatcher = NONCE_PATTERN.matcher(challenge);
            if (realmMatcher.find() && nonceMatcher.find()) {
                String realm = realmMatcher.group(1);
                String nonce = nonceMatcher.group(1);
                String ha1 = md5Hex(username + ":" + realm + ":" + safePass);
                String ha2 = md5Hex(method + ":" + uri);
                String response = md5Hex(ha1 + ":" + nonce + ":" + ha2);
                return "Digest username=\"" + username + "\", realm=\"" + realm + "\", nonce=\"" + nonce
                        + "\", uri=\"" + uri + "\", response=\"" + response + "\"";
            }
        }
        String raw = username + ":" + safePass;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    static String parseVideoCodecFromSdp(String sdp) {
        if (sdp == null || sdp.isBlank()) {
            return null;
        }
        boolean inVideoSection = false;
        for (String line : sdp.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("m=")) {
                inVideoSection = trimmed.toLowerCase(Locale.ROOT).startsWith("m=video");
                continue;
            }
            if (inVideoSection) {
                Matcher m = RTPMAP_PATTERN.matcher(trimmed);
                if (m.find()) {
                    return m.group(1).toUpperCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private static String buildCodecWarning(String sdp, String codec) {
        if (sdp != null && !sdp.isBlank() && !sdp.toLowerCase(Locale.ROOT).contains("m=video")) {
            return "Cảnh báo: Luồng RTSP không chứa track video (m=video).";
        }
        if (codec == null) {
            return null;
        }
        if ("H265".equals(codec) || "HEVC".equals(codec) || "JPEG".equals(codec) || "MJPEG".equals(codec)) {
            return "Cảnh báo: Camera đang phát codec " + codec + ", trình duyệt WebRTC có thể không giải mã được (khuyến nghị đổi sang H264).";
        }
        return null;
    }

    private record RtspResponse(
            String statusLine,
            int statusCode,
            String statusText,
            Map<String, String> headers,
            String body
    ) {}
}
