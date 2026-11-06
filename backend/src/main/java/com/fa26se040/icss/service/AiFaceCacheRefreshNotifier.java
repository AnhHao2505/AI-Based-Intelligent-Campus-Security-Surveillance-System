package com.fa26se040.icss.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Báo AI Service làm mới kho khuôn mặt (POST /api/v1/faces/refresh-cache) sau khi dữ liệu nhận diện đổi:
 * tài khoản bị khoá / xoá (UserService), ảnh khách được gắn / gắn lại, lượt khách bị huỷ / thu hồi / kết thúc.
 * Trong transaction: chỉ gửi SAU KHI commit (rollback thì không gửi), mỗi transaction gửi tối đa một lần.
 * Ngoài transaction: gửi ngay. Chạy nền; lỗi mạng chỉ ghi WARN, không làm hỏng nghiệp vụ.
 */
@Slf4j
@Component
public class AiFaceCacheRefreshNotifier {

    static final String REFRESH_PATH = "/api/v1/faces/refresh-cache";

    @Autowired(required = false)
    private EdgeMqttControlService edgeMqttControlService;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl = "http://localhost:8000";

    @Value("${icss.internal.service-key:}")
    private String internalServiceKey;

    // Cho phép mock/override HTTP caller trong unit test
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

    void setAiServiceUrl(String aiServiceUrl) {
        this.aiServiceUrl = aiServiceUrl;
    }

    void setInternalServiceKey(String internalServiceKey) {
        this.internalServiceKey = internalServiceKey;
    }

    void setRefreshHttpCaller(Consumer<String> refreshHttpCaller) {
        this.refreshHttpCaller = refreshHttpCaller;
    }

    /**
     * Đang trong transaction: đăng ký gửi sau commit (một lần cho cả transaction). Không có transaction: gửi ngay.
     */
    public void notifyAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendAsync();
            return;
        }
        boolean alreadyScheduled = TransactionSynchronizationManager.getSynchronizations().stream()
                .anyMatch(s -> s instanceof RefreshAfterCommit);
        if (alreadyScheduled) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new RefreshAfterCommit());
    }

    /** Đánh dấu riêng để nhận ra đã đăng ký trong transaction hiện tại (chống gửi trùng). */
    private final class RefreshAfterCommit implements TransactionSynchronization {
        @Override
        public void afterCommit() {
            sendAsync();
        }
    }

    private void sendAsync() {
        CompletableFuture.runAsync(() -> {
            if (edgeMqttControlService != null && edgeMqttControlService.isConnected()) {
                edgeMqttControlService.publishFaceCacheEvent("REFRESH", null);
                if (aiServiceUrl == null || aiServiceUrl.isBlank()) {
                    return;
                }
            }
            String baseUrl = (aiServiceUrl != null && !aiServiceUrl.isBlank()) ? aiServiceUrl.trim() : "http://localhost:8000";
            if (baseUrl.endsWith("/")) {
                baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
            }
            String targetUrl = baseUrl + REFRESH_PATH;
            if (refreshHttpCaller != null) {
                refreshHttpCaller.accept(targetUrl);
            }
        });
    }
}
