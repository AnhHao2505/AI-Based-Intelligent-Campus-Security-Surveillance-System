package com.fa26se040.icss.service;

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

/**
 * Gọi ai-service /api/v1/faces/process-registration để lấy embedding 512d cho ảnh khách.
 * Endpoint đó chỉ TRẢ embedding, không ghi face_data (A0.5) — backend lưu vào bảng riêng guest_face_embeddings.
 */
@Component
public class GuestFaceEmbeddingClient {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    /** Kết quả trích xuất: số khuôn mặt phát hiện + vector (512 phần tử). */
    public record Result(int faceCount, List<Float> embedding) {
    }

    /** code chỉ để ai-service ghi log; dùng id khách, không gửi họ tên. */
    public Result extract(byte[] image, String filename, String code) {
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
