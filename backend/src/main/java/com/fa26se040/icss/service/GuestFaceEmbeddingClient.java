package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gọi ai-service /api/v1/faces/process-registration (hoặc MQTT RPC qua MinIO Staging trên Cloud-Edge)
 * để lấy embedding 512d cho ảnh khách.
 */
@Component
public class GuestFaceEmbeddingClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Autowired(required = false)
    private EdgeMqttControlService edgeMqttControlService;

    @Autowired(required = false)
    private MinioStorageService minioStorageService;

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    /** Kết quả trích xuất: số khuôn mặt phát hiện + vector (512 phần tử). */
    public record Result(int faceCount, List<Float> embedding) {
    }

    /** code chỉ để ai-service ghi log; dùng id khách, không gửi họ tên. */
    public Result extract(byte[] image, String filename, String code) {
        boolean useCloudEdgeRpc = edgeMqttControlService != null
                && edgeMqttControlService.isConnected()
                && minioStorageService != null
                && (aiServiceUrl == null || aiServiceUrl.isBlank());

        if (useCloudEdgeRpc) {
            String bucket = minioStorageService.getFacesBucket();
            String stagingKey = "staging/guest_" + UUID.randomUUID() + ".jpg";
            minioStorageService.uploadStagingObject(bucket, stagingKey, image, "image/jpeg");
            try {
                EdgeRpcReplyDto reply = edgeMqttControlService.sendRpcRequest(
                        "EXTRACT_FACE_EMBEDDING",
                        null,
                        Map.of(
                                "bucket", bucket,
                                "object_key", stagingKey,
                                "code", code != null ? code : ""
                        ),
                        8000L
                );
                if (!reply.isSuccess() || reply.getData() == null) {
                    throw new IllegalStateException(reply.getErrorMessage() != null
                            ? reply.getErrorMessage()
                            : "ai-service không trả kết quả thành công qua Edge MQTT");
                }
                Map<String, Object> data = reply.getData();
                int faceCount = data.get("face_count") instanceof Number n ? n.intValue() : -1;
                List<Float> vector = new ArrayList<>();
                if (data.get("embedding_front") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Number n) {
                            vector.add(n.floatValue());
                        }
                    }
                }
                return new Result(faceCount, vector);
            } finally {
                minioStorageService.removeObjectSafely(bucket, stagingKey);
            }
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("code", code);
        body.add("front_image", new ByteArrayResource(image) {
            @Override
            public String getFilename() {
                return filename != null ? filename : "guest.jpg";
            }
        });
        @SuppressWarnings("unchecked")
        Map<String, Object> res = restTemplate.postForObject(aiServiceUrl + "/api/v1/faces/process-registration",
                new HttpEntity<>(body, headers), Map.class);
        if (res == null || !Boolean.TRUE.equals(res.get("success"))) {
            throw new IllegalStateException("ai-service không trả kết quả thành công");
        }
        int faceCount = res.get("face_count") instanceof Number n ? n.intValue() : -1;
        List<Float> vector = new ArrayList<>();
        if (res.get("embedding_front") instanceof List<?> list) {
            for (Object o : list) {
                vector.add(((Number) o).floatValue());
            }
        }
        return new Result(faceCount, vector);
    }
}
