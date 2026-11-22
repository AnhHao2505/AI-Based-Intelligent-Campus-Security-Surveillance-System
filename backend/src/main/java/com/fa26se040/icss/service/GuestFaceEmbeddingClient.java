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
import org.springframework.web.client.HttpStatusCodeException;
import com.fa26se040.icss.config.AiServiceRestTemplate;

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

    private final AiServiceRestTemplate restTemplate;

    public GuestFaceEmbeddingClient(AiServiceRestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

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
                    // Edge báo không thấy khuôn mặt (edge_agent.py) -> 0 khuôn mặt, như nhánh HTTP 400 bên dưới
                    if (AiFaceRegistrationErrors.EDGE_NO_FACE_ERROR_CODE.equals(reply.getErrorCode())) {
                        return new Result(0, List.of());
                    }
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
        Map<String, Object> res;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> ok = restTemplate.postForObject(aiServiceUrl + "/api/v1/faces/process-registration",
                    new HttpEntity<>(body, headers), Map.class);
            res = ok;
        } catch (HttpStatusCodeException ex) {
            // ai-service báo 400 khi ảnh không có khuôn mặt: đó là lỗi của ảnh (0 khuôn mặt), không phải dịch vụ hỏng.
            // Lỗi kết nối / timeout / 5xx / 400 khác vẫn ném ra để GuestPhotoService báo ERR_GUEST_035.
            if (AiFaceRegistrationErrors.isNoFaceDetected(ex)) {
                return new Result(0, List.of());
            }
            throw ex;
        }
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
