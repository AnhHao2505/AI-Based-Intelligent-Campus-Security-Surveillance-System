package com.fa26se040.icss.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

@Slf4j
@Service
public class AiSyncNotificationService {

    @Autowired(required = false)
    private EdgeMqttControlService edgeMqttControlService;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl = "http://localhost:8000";

    @Value("${icss.internal.service-key:}")
    private String internalServiceKey;

    private Consumer<String> refreshHttpCaller = url -> {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(1000))
                    .build();
            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder();
            if (internalServiceKey != null && !internalServiceKey.isBlank()) {
                reqBuilder.header("X-Service-Key", internalServiceKey);
            }
            HttpRequest request = reqBuilder
                    .uri(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofMillis(2000))
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
            log.info("Notified AI Service to refresh face cache via {}", url);
        } catch (Exception e) {
            log.warn("Could not notify AI Service to refresh face cache: {}", e.getMessage());
        }
    };

    public void setRefreshHttpCaller(Consumer<String> caller) {
        this.refreshHttpCaller = caller;
    }

    public void setAiServiceUrl(String url) {
        this.aiServiceUrl = url;
    }

    public void notifyFaceCacheRefreshAfterCommit() {
        notifyFaceCacheRefreshAfterCommit(null);
    }

    public void notifyFaceCacheRefreshAfterCommit(String userCode) {
        Runnable task = () -> {
            if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
                edgeMqttControlService.publishFaceCacheEvent("REFRESH", userCode);
                if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
                    return;
                }
            }
            String baseUrl = (aiServiceUrl != null && !aiServiceUrl.isBlank()) ? aiServiceUrl.trim() : "http://localhost:8000";
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            String targetUrl = baseUrl + "/api/v1/faces/refresh-cache";
            if (refreshHttpCaller != null) {
                refreshHttpCaller.accept(targetUrl);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }
}
