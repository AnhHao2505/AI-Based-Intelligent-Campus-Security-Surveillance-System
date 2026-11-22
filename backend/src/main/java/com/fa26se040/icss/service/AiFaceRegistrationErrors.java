package com.fa26se040.icss.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Đọc phản hồi lỗi của ai-service /api/v1/faces/process-registration (dùng chung cho luồng tài khoản và luồng khách).
 */
final class AiFaceRegistrationErrors {

    /**
     * ai-service/src/main.py: ảnh không có khuôn mặt nào -> HTTP 400, detail bắt đầu bằng câu này. Các 400 khác (ảnh rỗng,
     * không đọc được ảnh) có detail khác nên không bị coi là "0 khuôn mặt".
     */
    static final String NO_FACE_DETAIL_PREFIX = "Không phát hiện thấy khuôn mặt";

    /** ai-service/src/integration/edge_agent.py: mã lỗi RPC EXTRACT_FACE_EMBEDDING khi ảnh không có khuôn mặt (Edge + Cloud). */
    static final String EDGE_NO_FACE_ERROR_CODE = "ERR_NO_FACE_DETECTED";

    private static final ObjectMapper JSON = new ObjectMapper();

    private AiFaceRegistrationErrors() {
    }

    /** true khi ai-service trả 400 vì không thấy khuôn mặt trong ảnh (body FastAPI dạng {"detail": "..."}). */
    static boolean isNoFaceDetected(HttpStatusCodeException e) {
        if (e.getStatusCode().value() != 400) {
            return false;
        }
        try {
            String detail = JSON.readTree(e.getResponseBodyAsByteArray()).path("detail").asText("");
            return detail.startsWith(NO_FACE_DETAIL_PREFIX);
        } catch (Exception parseError) {
            return false;
        }
    }
}
