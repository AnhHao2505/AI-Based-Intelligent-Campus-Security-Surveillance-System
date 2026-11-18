package com.fa26se040.icss.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * RestTemplate chuyên biệt để Backend gọi sang AI Service (/api/v1/*).
 * Tự động gắn header X-Service-Key cho mọi HTTP request.
 * Không log giá trị khoá.
 */
@Component
public class AiServiceRestTemplate extends RestTemplate {

    public static final String SERVICE_KEY_HEADER = "X-Service-Key";
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);

    public AiServiceRestTemplate(@Value("${icss.internal.service-key:}") String serviceKey) {
        super(createRequestFactory());
        ClientHttpRequestInterceptor interceptor = (request, body, execution) -> {
            if (serviceKey != null && !serviceKey.isBlank()) {
                request.getHeaders().set(SERVICE_KEY_HEADER, serviceKey);
            }
            return execution.execute(request, body);
        };
        List<ClientHttpRequestInterceptor> interceptors = new ArrayList<>(getInterceptors());
        interceptors.add(interceptor);
        setInterceptors(interceptors);
    }

    private static JdkClientHttpRequestFactory createRequestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
