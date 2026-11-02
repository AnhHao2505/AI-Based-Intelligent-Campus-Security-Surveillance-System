package com.fa26se040.icss.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
