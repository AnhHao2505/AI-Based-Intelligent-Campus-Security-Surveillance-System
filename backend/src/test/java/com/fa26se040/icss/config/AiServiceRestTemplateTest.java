package com.fa26se040.icss.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AiServiceRestTemplateTest {

    @Test
    @DisplayName("T3: AiServiceRestTemplate tự động gắn header X-Service-Key cho mọi request")
    void interceptorAttachesXServiceKeyHeader() {
        String testKey = "0123456789abcdef0123456789abcdef";
        AiServiceRestTemplate restTemplate = new AiServiceRestTemplate(testKey);

        MockRestServiceServer mockServer = MockRestServiceServer.bindTo(restTemplate).build();
        mockServer.expect(requestTo("http://localhost:8000/api/v1/test"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("X-Service-Key", testKey))
                .andRespond(withSuccess("{\"status\":\"OK\"}", MediaType.APPLICATION_JSON));

        String response = restTemplate.getForObject("http://localhost:8000/api/v1/test", String.class);
        assertEquals("{\"status\":\"OK\"}", response);
        mockServer.verify();
    }

    @Test
    @DisplayName("HttpClient của AiServiceRestTemplate dùng HTTP/1.1 (uvicorn không hỗ trợ h2c)")
    void httpClientIsForcedToHttp11() {
        AiServiceRestTemplate restTemplate = new AiServiceRestTemplate("");

        Object factory = ReflectionTestUtils.getField(restTemplate, "requestFactory");
        JdkClientHttpRequestFactory jdkFactory = assertInstanceOf(JdkClientHttpRequestFactory.class, factory);
        HttpClient httpClient = (HttpClient) ReflectionTestUtils.getField(jdkFactory, "httpClient");

        assertEquals(HttpClient.Version.HTTP_1_1, httpClient.version());
    }

    @Test
    @DisplayName("Multipart tới server HTTP/1.1 đủ phần 'code' và 'front_image', không kèm Upgrade: h2c")
    void multipartReachesHttp11ServerWithAllPartsAndNoUpgradeHeader() throws Exception {
        AtomicReference<String> upgradeHeader = new AtomicReference<>();
        AtomicReference<String> protocol = new AtomicReference<>();
        AtomicReference<String> receivedBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/faces/register", exchange -> {
            upgradeHeader.set(exchange.getRequestHeaders().getFirst("Upgrade"));
            protocol.set(exchange.getProtocol());
            receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1));
            byte[] response = "{\"status\":\"OK\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            AiServiceRestTemplate restTemplate = new AiServiceRestTemplate("");

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("code", "SV-001");
            body.add("front_image", new ByteArrayResource(new byte[]{1, 2, 3, 4}) {
                @Override
                public String getFilename() {
                    return "front.jpg";
                }
            });
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            String response = restTemplate.postForObject(
                    "http://127.0.0.1:" + port + "/api/v1/faces/register",
                    new HttpEntity<>(body, headers), String.class);

            assertEquals("{\"status\":\"OK\"}", response);
            assertEquals("HTTP/1.1", protocol.get());
            assertNull(upgradeHeader.get(), "Không được gửi header Upgrade (h2c)");
            String received = receivedBody.get();
            assertTrue(received.contains("name=\"code\""), "Thiếu phần 'code'");
            assertTrue(received.contains("SV-001"), "Thiếu giá trị 'code'");
            assertTrue(received.contains("name=\"front_image\""), "Thiếu phần 'front_image'");
        } finally {
            server.stop(0);
        }
    }
}
