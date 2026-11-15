package com.fa26se040.icss.util;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class RtspHandshakeCheckerTest {

    @Test
    void options200ButDescribe404_mustReportUnreachable() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            executor.submit(() -> {
                try (Socket client = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = client.getOutputStream();
                    while (true) {
                        String line = in.readLine();
                        if (line == null) break;
                        String methodLine = line;
                        String cseq = "1";
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("cseq:")) {
                                cseq = line.substring(5).trim();
                            }
                        }
                        if (methodLine.startsWith("OPTIONS ")) {
                            String resp = "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\nPublic: OPTIONS, DESCRIBE\r\n\r\n";
                            out.write(resp.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } else if (methodLine.startsWith("DESCRIBE ")) {
                            String resp = "RTSP/1.0 404 Stream Not Found\r\nCSeq: " + cseq + "\r\n\r\n";
                            out.write(resp.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            });

            RtspHandshakeChecker checker = new RtspHandshakeChecker();
            RtspCheckResult result = checker.check("127.0.0.1", port, "/wrong_path", 2000);

            assertFalse(result.isReachable(), "DESCRIBE trả 404 thì không được báo kết nối thành công");
            assertEquals(404, result.getStatusCode());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void options200AndDescribe200H264_reportsReachableWithCodec() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            executor.submit(() -> {
                try (Socket client = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = client.getOutputStream();
                    while (true) {
                        String line = in.readLine();
                        if (line == null) break;
                        String methodLine = line;
                        String cseq = "1";
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("cseq:")) {
                                cseq = line.substring(5).trim();
                            }
                        }
                        if (methodLine.startsWith("OPTIONS ")) {
                            String resp = "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n";
                            out.write(resp.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } else if (methodLine.startsWith("DESCRIBE ")) {
                            String sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n";
                            String resp = "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\nContent-Type: application/sdp\r\nContent-Length: " + sdp.length() + "\r\n\r\n" + sdp;
                            out.write(resp.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            });

            RtspHandshakeChecker checker = new RtspHandshakeChecker();
            RtspCheckResult result = checker.check("127.0.0.1", port, "/stream", 2000);

            assertTrue(result.isReachable());
            assertEquals(200, result.getStatusCode());
            assertEquals("H264", result.getVideoCodec());
            assertNull(result.getWarningMessage());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void describe401WithoutCredentials_mustReportUnreachable() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            executor.submit(() -> {
                try (Socket client = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = client.getOutputStream();
                    while (true) {
                        String line = in.readLine();
                        if (line == null) break;
                        String methodLine = line;
                        String cseq = "1";
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("cseq:")) {
                                cseq = line.substring(5).trim();
                            }
                        }
                        if (methodLine.startsWith("OPTIONS ")) {
                            out.write(("RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } else if (methodLine.startsWith("DESCRIBE ")) {
                            out.write(("RTSP/1.0 401 Unauthorized\r\nCSeq: " + cseq + "\r\nWWW-Authenticate: Basic realm=\"cam\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            });

            RtspHandshakeChecker checker = new RtspHandshakeChecker();
            RtspCheckResult result = checker.check("127.0.0.1", port, "/stream", 2000);

            assertFalse(result.isReachable());
            assertEquals(401, result.getStatusCode());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void describe200WithH265Codec_reportsReachableWithCodecWarning() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            executor.submit(() -> {
                try (Socket client = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = client.getOutputStream();
                    while (true) {
                        String line = in.readLine();
                        if (line == null) break;
                        String methodLine = line;
                        String cseq = "1";
                        while ((line = in.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("cseq:")) {
                                cseq = line.substring(5).trim();
                            }
                        }
                        if (methodLine.startsWith("OPTIONS ")) {
                            out.write(("RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } else if (methodLine.startsWith("DESCRIBE ")) {
                            String sdp = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H265/90000\r\n";
                            String resp = "RTSP/1.0 200 OK\r\nCSeq: " + cseq + "\r\nContent-Length: " + sdp.length() + "\r\n\r\n" + sdp;
                            out.write(resp.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                            break;
                        }
                    }
                } catch (Exception ignored) {}
            });

            RtspHandshakeChecker checker = new RtspHandshakeChecker();
            RtspCheckResult result = checker.check("127.0.0.1", port, "/stream", 2000);

            assertTrue(result.isReachable());
            assertEquals("H265", result.getVideoCodec());
            assertNotNull(result.getWarningMessage());
            assertTrue(result.getWarningMessage().contains("H265"));
        } finally {
            executor.shutdownNow();
        }
    }
}
