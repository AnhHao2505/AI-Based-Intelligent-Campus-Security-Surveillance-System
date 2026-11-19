package com.fa26se040.icss.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MediaMtxDvrServiceTest {

    private HttpServer mockPlaybackServer;
    private MediaMtxService mediaMtxService;
    private String baseUrl;
    private final AtomicReference<String> lastGetQuery = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws IOException {
        mockPlaybackServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = mockPlaybackServer.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;

        mockPlaybackServer.createContext("/list", exchange -> {
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null && query.contains("path=cam-006_main")) {
                String json = "[{\"start\":\"2026-11-19T10:00:00Z\",\"duration\":120.0}]";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                byte[] err = "{\"status\":\"error\",\"error\":\"no recording\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(400, err.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(err);
                }
            }
        });

        mockPlaybackServer.createContext("/get", exchange -> {
            lastGetQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] fakeFmp4 = new byte[]{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
            exchange.getResponseHeaders().add("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, fakeFmp4.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(fakeFmp4);
            }
        });

        mockPlaybackServer.start();

        mediaMtxService = new MediaMtxService();
        ReflectionTestUtils.setField(mediaMtxService, "mediaMtxPlaybackUrl", baseUrl);
    }

    @AfterEach
    void tearDown() {
        if (mockPlaybackServer != null) {
            mockPlaybackServer.stop(0);
        }
    }

    @Test
    @DisplayName("getDvrTimeline resolves _main fallback and parses segments cleanly")
    void getDvrTimeline_fallbackToMainPath() {
        MediaMtxService.DvrTimelineDto dto = mediaMtxService.getDvrTimeline("CAM-006", null, null);
        assertEquals("CAM-006", dto.cameraCode());
        assertEquals("cam-006_main", dto.streamPath());
        assertEquals(1, dto.segments().size());
        assertEquals(120.0, dto.segments().get(0).durationSeconds());
    }

    @Test
    @DisplayName("streamDvrPlayback streams fMP4 bytes from matched segment and clamps duration")
    void streamDvrPlayback_streamsFmp4() throws Exception {
        long requestedMs = Instant.parse("2026-11-19T10:00:30Z").toEpochMilli();
        ResponseEntity<StreamingResponseBody> resp = mediaMtxService.streamDvrPlayback("CAM-006", requestedMs, 1800.0, null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertNotNull(resp.getBody());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        resp.getBody().writeTo(out);
        assertTrue(out.size() > 0);
        assertTrue(lastGetQuery.get().contains("path=cam-006_main"));
        assertTrue(lastGetQuery.get().contains("format=fmp4"));
        assertTrue(lastGetQuery.get().contains("duration=90.000"));
    }

    @Test
    @DisplayName("streamDvrPlayback returns 404 when timestamp is outside recorded segments")
    void streamDvrPlayback_outsideRangeReturns404() {
        long oldMs = Instant.parse("2026-11-19T08:00:00Z").toEpochMilli();
        ResponseEntity<StreamingResponseBody> resp = mediaMtxService.streamDvrPlayback("CAM-006", oldMs, 60.0, null);
        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }
}
