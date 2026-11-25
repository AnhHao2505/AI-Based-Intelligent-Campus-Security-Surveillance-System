package com.fa26se040.icss.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.client.HttpStatusCodeException;

/**
 * Đọc phản hồi lỗi của ai-service /api/v1/faces/process-registration (dùng chung cho luồng tài khoản và luồng khách).
 */
final class AiFaceRegistrationErrors {

    /**
     * Dự phòng cho ai-service bản cũ (chưa trả errorCode): ảnh không có khuôn mặt nào -> HTTP 400, detail bắt đầu bằng câu
     * này. Các 400 khác (ảnh rỗng, không đọc được ảnh) có detail khác nên không bị coi là "0 khuôn mặt".
     */
    static final String NO_FACE_DETAIL_PREFIX = "Không phát hiện thấy khuôn mặt";

    /**
     * Mã cố định khi ảnh không có khuôn mặt, cùng giá trị ở hai đường của ai-service:
     * HTTP 400 /api/v1/faces/process-registration (khoá errorCode trong body, src/main.py) và
     * RPC EXTRACT_FACE_EMBEDDING (errorCode trong reply, src/integration/edge_agent.py).
     */
    static final String EDGE_NO_FACE_ERROR_CODE = "ERR_NO_FACE_DETECTED";

    /**
     * Mã lỗi cố định khi thiết bị biên (RPC EXTRACT_FACE_EMBEDDING, src/integration/edge_agent.py)
     * không giải mã được ảnh (ảnh hỏng, sai định dạng, dữ liệu rỗng).
     */
    static final String EDGE_IMAGE_DECODE_ERROR_CODE = "ERR_IMAGE_DECODE";

    /** Khoá chứa mã lỗi trong body HTTP của ai-service (cùng tên khoá với reply RPC của edge). */
    static final String ERROR_CODE_FIELD = "errorCode";

    private static final ObjectMapper JSON = new ObjectMapper();

    private AiFaceRegistrationErrors() {
    }

    /**
     * true khi ai-service trả 400 vì không thấy khuôn mặt trong ảnh. Body có errorCode (ai-service bản mới) thì chỉ dựa vào
     * mã: bằng ERR_NO_FACE_DETECTED mới là "0 khuôn mặt". Không có errorCode (bản cũ, body FastAPI {"detail": "..."}) thì
     * so tiền tố câu chữ của detail. Mã khác 400 (5xx, 503...) không bao giờ là "0 khuôn mặt".
     */
    static boolean isNoFaceDetected(HttpStatusCodeException e) {
        if (e.getStatusCode().value() != 400) {
            return false;
        }
        try {
            JsonNode body = JSON.readTree(e.getResponseBodyAsByteArray());
            String errorCode = body.path(ERROR_CODE_FIELD).asText("").trim();
            if (!errorCode.isEmpty()) {
                return EDGE_NO_FACE_ERROR_CODE.equals(errorCode);
            }
            return body.path("detail").asText("").startsWith(NO_FACE_DETAIL_PREFIX);
        } catch (Exception parseError) {
            return false;
        }
    }

    /** Số ký tự tối đa của body lỗi ai-service được ghi vào log (giới hạn kỹ thuật cho dòng log, không phải tham số nghiệp vụ). */
    static final int LOG_BODY_MAX_CHARS = 500;

    /**
     * Body lỗi của ai-service để GHI LOG, không bao giờ đưa vào câu báo cho người dùng. Body lỗi chỉ có detail / errorCode
     * (không có ảnh, không có embedding); vẫn cắt ở LOG_BODY_MAX_CHARS ký tự để dòng log không phình.
     */
    static String bodyForLog(HttpStatusCodeException e) {
        String body = e.getResponseBodyAsString();
        if (body == null) {
            return "";
        }
        return body.length() > LOG_BODY_MAX_CHARS ? body.substring(0, LOG_BODY_MAX_CHARS) + "...(cắt)" : body;
    }
}
