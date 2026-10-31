package com.fa26se040.icss.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioConfig {

    @Value("${minio.endpoint:http://localhost:9000}")
    private String endpoint;

    @Value("${minio.access-key:minioadmin}")
    private String accessKey;

    @Value("${minio.secret-key:minioadmin123}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        String cleanEndpoint = endpoint;
        if (cleanEndpoint == null || cleanEndpoint.isBlank()) {
            cleanEndpoint = "http://localhost:9000";
        }
        cleanEndpoint = cleanEndpoint.trim();
        if (!cleanEndpoint.startsWith("http://") && !cleanEndpoint.startsWith("https://")) {
            cleanEndpoint = "http://" + cleanEndpoint;
        }

        String cleanAccessKey = (accessKey != null && !accessKey.isBlank()) ? accessKey.trim() : "minioadmin";
        String cleanSecretKey = (secretKey != null && !secretKey.isBlank()) ? secretKey.trim() : "minioadmin123";

        return MinioClient.builder()
                .endpoint(cleanEndpoint)
                .credentials(cleanAccessKey, cleanSecretKey)
                .build();
    }
}
