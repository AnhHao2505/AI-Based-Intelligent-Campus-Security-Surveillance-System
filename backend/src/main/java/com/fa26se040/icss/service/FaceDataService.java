package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.AiFaceRegistrationResponseDto;
import com.fa26se040.icss.dto.FaceDataResponseDto;
import com.fa26se040.icss.dto.accesscontrol.snapshot.FaceRegistrationAuditSnapshot;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.ConfigKey;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.dto.edge.EdgeRpcReplyDto;
import com.fa26se040.icss.entity.FaceData;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.exception.AiServiceUnavailableException;
import com.fa26se040.icss.exception.CameraException;
import com.fa26se040.icss.exception.FaceDetectionException;
import com.fa26se040.icss.exception.FaceDuplicateException;
import com.fa26se040.icss.exception.FaceErrorCode;
import com.fa26se040.icss.exception.FaceImageException;
import com.fa26se040.icss.repository.FaceDataRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import com.fa26se040.icss.config.AiServiceRestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceDataService {

    /** Ảnh không có khuôn mặt: face_count = 0, hoặc ai-service trả 400 "không phát hiện khuôn mặt". */
    static final String NO_FACE_MESSAGE = "Không phát hiện thấy khuôn mặt trong ảnh.";
    /**
     * ai-service từ chối ảnh (400 / 422 không phải "không có mặt": ảnh rỗng, không giải mã được...). Câu sẵn có trước đây đi
     * kèm nguyên body của ai-service; nay chỉ còn câu, body ghi log WARN.
     */
    static final String IMAGE_UNPROCESSABLE_MESSAGE = "Không thể xử lý khuôn mặt trong ảnh";
    /** ai-service trả lỗi không do ảnh (5xx...); chế độ Cloud-Edge dùng chung cho mọi mã lỗi edge không phải lỗi ảnh. */
    static final String AI_SERVICE_ERROR_MESSAGE = "Dịch vụ AI gặp lỗi khi xử lý ảnh. Vui lòng thử lại sau.";
    /**
     * Không kết nối được ai-service (HTTP); chế độ Cloud-Edge: RPC tới edge quá hạn / mất kết nối (CameraException) hoặc
     * không tải được ảnh staging lên MinIO.
     */
    static final String AI_SERVICE_UNAVAILABLE_MESSAGE = "Dịch vụ AI hiện không khả dụng. Vui lòng thử lại sau.";

    private final FaceDataRepository faceDataRepository;
    private final UserRepository userRepository;
    private final MinioStorageService minioStorageService;
    private final AiServiceRestTemplate restTemplate;
    /** Kiểm trùng khuôn mặt: bắt buộc (face-gate 1), thiếu bean thì ứng dụng không khởi động thay vì bỏ qua im lặng. */
    private final FaceDuplicateCheckService faceDuplicateCheckService;
    /** V140: USER_FACE_IMAGE_MAX_KB đọc mỗi lần đăng ký (sửa trên màn System Configuration có hiệu lực ngay). */
    private final SystemConfigService systemConfigService;

    /** V140: dung lượng tối đa ảnh khuôn mặt tài khoản (KB), kẹp theo min_value / max_value ở DB. */
    public int getFaceImageMaxKb() {
        return systemConfigService.getIntWithinBounds(ConfigKey.USER_FACE_IMAGE_MAX_KB);
    }

    /** Câu báo ảnh quá dung lượng, dùng chung cho đăng ký đơn lẻ và nạp theo lô (nội suy giá trị cấu hình). */
    static String faceImageTooLargeMessage(int maxKb, long sizeBytes) {
        return String.format("Kích thước ảnh vượt quá giới hạn tối đa %dKB (dung lượng file: %.1f KB)", maxKb, sizeBytes / 1024.0);
    }

    @Autowired(required = false)
    private EdgeMqttControlService edgeMqttControlService;

    /** PR-AUDIT: field injection để giữ constructor (test dựng service bằng tay); null thì không ghi audit. */
    @Autowired(required = false)
    private AuditService auditService;

    /** PR-AUDIT: mã đã có khuôn mặt chưa (dùng cho snapshot hasFaceData của audit tài khoản). */
    @Transactional(readOnly = true)
    public boolean hasFaceData(String code) {
        return code != null && faceDataRepository.findByCode(code).isPresent();
    }

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    /**
     * S4: gắn user_id cho face_data theo mã người dùng, dùng khi khuôn mặt được đăng ký trước khi lưu User
     * (tạo tài khoản đơn lẻ). Chạy trong transaction của bên tạo tài khoản.
     */
    @Transactional
    public void linkToUser(String code, User user) {
        faceDataRepository.findByCode(code).ifPresent(faceData -> {
            if (faceData.getUser() == null || !user.getId().equals(faceData.getUser().getId())) {
                faceData.setUser(user);
                faceDataRepository.save(faceData);
            }
        });
    }

    @Transactional
    public FaceDataResponseDto registerFace(
            String code,
            MultipartFile frontImage
    ) {
        try {
            log.info("Bắt đầu xử lý đăng ký khuôn mặt cho [{}]", code);

            if (frontImage == null || frontImage.isEmpty()) {
                throw new FaceImageException(FaceErrorCode.ERR_FACE_002, "Ảnh khuôn mặt không được để trống.");
            }

            int maxKb = getFaceImageMaxKb();
            if (frontImage.getSize() > (long) maxKb * 1024) {
                throw new FaceImageException(FaceErrorCode.ERR_FACE_003, faceImageTooLargeMessage(maxKb, frontImage.getSize()));
            }

            String contentType = frontImage.getContentType();
            String filename = frontImage.getOriginalFilename() != null ? frontImage.getOriginalFilename().toLowerCase() : "";
            boolean isValidFormat = (contentType != null && (contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/jpg") || contentType.equalsIgnoreCase("image/png")))
                    || (filename.endsWith(".jpg") || filename.endsWith(".jpeg") || filename.endsWith(".png"));

            if (!isValidFormat) {
                throw new FaceImageException(FaceErrorCode.ERR_FACE_004, "Định dạng file không hợp lệ. Chỉ chấp nhận file ảnh định dạng JPG hoặc PNG.");
            }

            byte[] imageBytes = frontImage.getBytes();
            AiFaceRegistrationResponseDto aiResult;

            boolean useCloudEdgeRpc = edgeMqttControlService != null
                    && edgeMqttControlService.isConnected()
                    && (aiServiceUrl == null || aiServiceUrl.isBlank());

            if (useCloudEdgeRpc) {
                aiResult = extractEmbeddingViaEdgeMqtt(code, imageBytes, contentType);
            } else {
                String aiEndpoint = aiServiceUrl + "/api/v1/faces/process-registration";
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.MULTIPART_FORM_DATA);

                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("code", code);
                body.add("front_image", new NamedByteArrayResource(imageBytes, frontImage.getOriginalFilename() != null ? frontImage.getOriginalFilename() : "front.jpg"));

                HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

                ResponseEntity<AiFaceRegistrationResponseDto> response = restTemplate.postForEntity(
                        aiEndpoint, requestEntity, AiFaceRegistrationResponseDto.class
                );
                aiResult = response.getBody();
            }

            if (aiResult == null || !aiResult.isSuccess()) {
                throw new FaceDetectionException(FaceErrorCode.ERR_FACE_007, "AI Service không thể trích xuất vector khuôn mặt cho mã: " + code);
            }

            // face-gate 1: thiếu số khuôn mặt hoặc vector không đủ 512 chiều -> chặn như luồng ảnh khách (ERR_GUEST_035),
            // không coi là "không trùng"
            if (aiResult.getFaceCount() == null || aiResult.getEmbeddingFront() == null
                    || aiResult.getEmbeddingFront().size() != FaceDuplicateCheckService.EMBEDDING_DIM) {
                log.warn("AI Service trả kết quả thiếu cho [{}]: faceCount={}, số chiều vector={}", code,
                        aiResult.getFaceCount(), aiResult.getEmbeddingFront() == null ? null : aiResult.getEmbeddingFront().size());
                throw new AiServiceUnavailableException("Dịch vụ AI không trích xuất được khuôn mặt, vui lòng thử lại sau.");
            }
            if (aiResult.getFaceCount() > 1) {
                throw new FaceDetectionException(FaceErrorCode.ERR_FACE_006, "Ảnh chứa nhiều hơn 1 khuôn mặt");
            }
            if (aiResult.getFaceCount() == 0) {
                throw new FaceDetectionException(FaceErrorCode.ERR_FACE_005, NO_FACE_MESSAGE);
            }

            // Không đăng ký khuôn mặt đã thuộc về người khác: người dùng chưa xoá mềm, kể cả đang bị vô hiệu hoá (trừ chính
            // mã này khi thay ảnh), hoặc khách của lượt còn hiệu lực / chưa bắt đầu. Kiểm trước khi tải ảnh lên kho; khoá
            // advisory giữ tới hết transaction nên hai lượt đăng ký cùng khuôn mặt không cùng lọt (face-gate 4, 5).
            faceDuplicateCheckService.findDuplicateForUser(aiResult.getEmbeddingFront(), code).ifPresent(match -> {
                throw new FaceDuplicateException(match.adminMessage());
            });

            // Tra trước khi tải ảnh: biết object sắp ghi có đang được face_data đã commit tham chiếu hay không
            Optional<FaceData> existingOpt = faceDataRepository.findByCode(code);
            String imageUrl = minioStorageService.uploadFaceImage(imageBytes, code);
            deleteUploadedImageOnRollback(code, imageUrl, existingOpt.isPresent());
            String vecFront = formatVectorString(aiResult.getEmbeddingFront());

            User matchedUser = userRepository.findByUserCodeAndDeletedAtIsNull(code).orElse(null);

            FaceData faceData;
            if (existingOpt.isPresent()) {
                faceData = existingOpt.get();
                faceData.setImageFrontUrl(imageUrl);
                faceData.setEmbeddingFront(vecFront);
                if (matchedUser != null) {
                    faceData.setUser(matchedUser);
                }
            } else {
                faceData = FaceData.builder()
                        .user(matchedUser)
                        .code(code)
                        .imageFrontUrl(imageUrl)
                        .embeddingFront(vecFront)
                        .build();
            }

            FaceData saved = faceDataRepository.save(faceData);
            log.info("Lưu thành công dữ liệu khuôn mặt cho [{}] vào Database (ID: {})", code, saved.getId());

            // PR-AUDIT (B3): ai đăng ký khuôn mặt cho mã nào, lần đầu hay thay thế. KHÔNG ghi embedding, URL, object key.
            // Cùng transaction: lỗi ghi audit -> AuditWriteException ném ra ngoài, tài khoản / dòng import rollback.
            if (auditService != null) {
                auditService.record(AuditTargetType.FACE_REGISTRATION, AuditAction.REGISTER_FACE, code, null, matchedUser,
                        null, new FaceRegistrationAuditSnapshot(code, existingOpt.isPresent()), null,
                        auditService.resolveCurrentActor("FACE_REGISTRATION"));
            }

            // Không báo làm mới kho khuôn mặt tại đây (trước đây publish MQTT ngay, trước commit): bên gọi báo
            // AiFaceCacheRefreshNotifier sau khi commit (tạo tay: mỗi tài khoản; import ZIP: một lần cho cả lô)

            return toDto(saved);

        } catch (ResourceAccessException | CameraException e) {
            log.error("Không thể kết nối đến AI Service cho [{}]: {}", code, e.getMessage());
            throw new AiServiceUnavailableException(AI_SERVICE_UNAVAILABLE_MESSAGE);
        } catch (HttpStatusCodeException e) {
            // Lỗi do ảnh: người dùng chỉ nhận câu chuẩn; body / detail của ai-service chỉ ghi log WARN (không có ảnh, embedding)
            // ai-service báo 400 khi ảnh không có khuôn mặt -> cùng câu với nhánh face_count = 0 ở trên
            if (AiFaceRegistrationErrors.isNoFaceDetected(e)) {
                log.warn("AI Service không phát hiện khuôn mặt trong ảnh của [{}] ({}): {}", code, e.getStatusCode(),
                        AiFaceRegistrationErrors.bodyForLog(e));
                throw new FaceDetectionException(FaceErrorCode.ERR_FACE_005, NO_FACE_MESSAGE);
            }
            if (e.getStatusCode().value() == 422 || e.getStatusCode().value() == 400) {
                log.warn("AI Service từ chối ảnh của [{}] ({}): {}", code, e.getStatusCode(),
                        AiFaceRegistrationErrors.bodyForLog(e));
                throw new FaceDetectionException(FaceErrorCode.ERR_FACE_007, IMAGE_UNPROCESSABLE_MESSAGE);
            }
            log.error("AI Service trả về lỗi [{}] cho [{}]: {}", e.getStatusCode(), code, AiFaceRegistrationErrors.bodyForLog(e));
            throw new AiServiceUnavailableException(AI_SERVICE_ERROR_MESSAGE);
        } catch (FaceDetectionException | FaceDuplicateException | AiServiceUnavailableException
                 | com.fa26se040.icss.exception.AuditWriteException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi khi đăng ký khuôn mặt cho [{}]: {}", code, e.getMessage(), e);
            throw new RuntimeException("Lỗi xử lý khuôn mặt: " + e.getMessage(), e);
        }
    }

    /**
     * Không để ảnh khuôn mặt mồ côi trên MinIO (phương án b): ảnh lên kho TRƯỚC khi face_data / audit / tài khoản được lưu,
     * nên transaction rollback sau lúc tải ảnh thì xoá ảnh vừa tải trong afterCompletion(ROLLED_BACK). Commit thì không
     * làm gì. Cùng kiểu đăng ký synchronization với mail tài khoản và AiFaceCacheRefreshNotifier. Đăng ký vào transaction
     * đang chạy: tạo tay là transaction của UserService.createStaffAccount, import ZIP là transaction REQUIRES_NEW của
     * từng dòng (UserBulkImportHelper) nên dòng nào rollback chỉ xoá ảnh của dòng đó. Không có transaction: giữ hành vi cũ.
     * <p>
     * Key ảnh cố định theo mã ({@code <mã>/<mã>_front.jpg}, MinioStorageService). Nếu face_data của mã đã có từ trước
     * (đăng ký lại mã của tài khoản đã xoá mềm) thì object vừa ghi đè chính ảnh face_data đang tham chiếu: không xoá
     * (xoá sẽ để lại face_data trỏ tới object không tồn tại), chỉ ghi WARN. Xoá lỗi chỉ ghi ERROR, không ném ra ngoài
     * (afterCompletion chạy sau rollback; lỗi gốc vẫn là lỗi trả cho người dùng). Không ghi ảnh / URL ký vào log.
     */
    private void deleteUploadedImageOnRollback(String code, String imageUrl, boolean referencedBefore) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        String bucket = minioStorageService.getFacesBucket();
        String key = objectKeyOf(imageUrl, bucket, code);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                if (referencedBefore) {
                    log.warn("Rollback sau khi tải ảnh khuôn mặt của [{}]: object {}/{} đã ghi đè ảnh face_data cũ đang tham chiếu,"
                            + " giữ lại, không xoá", code, bucket, key);
                    return;
                }
                log.warn("Rollback sau khi tải ảnh khuôn mặt của [{}]: xoá object {}/{} vừa tải lên", code, bucket, key);
                try {
                    if (!minioStorageService.deleteFaceImage(code)) {
                        log.error("Không xoá được ảnh khuôn mặt mồ côi của [{}] ({}/{})", code, bucket, key);
                    }
                } catch (RuntimeException e) {
                    log.error("Không xoá được ảnh khuôn mặt mồ côi của [{}] ({}/{}): {}", code, bucket, key, e.getMessage());
                }
            }
        });
    }

    /** Object key lấy từ URL MinioStorageService.uploadFaceImage trả về ({@code <endpoint>/<bucket>/<key>}); chỉ dùng để ghi log. */
    static String objectKeyOf(String imageUrl, String bucket, String code) {
        if (imageUrl != null && bucket != null && !bucket.isBlank()) {
            String marker = "/" + bucket + "/";
            int idx = imageUrl.indexOf(marker);
            if (idx >= 0) {
                return imageUrl.substring(idx + marker.length());
            }
        }
        return code + "/";
    }

    private AiFaceRegistrationResponseDto extractEmbeddingViaEdgeMqtt(String code, byte[] imageBytes, String contentType) {
        String bucket = minioStorageService.getFacesBucket();
        String stagingKey = "staging/" + UUID.randomUUID() + ".jpg";
        try {
            minioStorageService.uploadStagingObject(
                    bucket,
                    stagingKey,
                    imageBytes,
                    contentType != null ? contentType : "image/jpeg"
            );
        } catch (RuntimeException e) {
            // Ảnh chưa tới được thiết bị biên, như nhánh HTTP không kết nối được ai-service -> 503 câu dùng chung (trước đây
            // rơi vào lỗi chung 500). Chi tiết MinIO chỉ ghi log WARN (không có ảnh); không có object staging để gỡ.
            log.warn("Không tải được ảnh staging lên MinIO cho [{}] (chế độ Cloud-Edge): {}", code, e.getMessage());
            throw new AiServiceUnavailableException(AI_SERVICE_UNAVAILABLE_MESSAGE);
        }
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
            if (reply == null || !reply.isSuccess() || reply.getData() == null) {
                String errorCode = reply != null ? reply.getErrorCode() : null;
                // Câu của thiết bị biên chỉ ghi log (WARN, không có ảnh / embedding), không bao giờ trả cho người dùng
                log.warn("Edge RPC EXTRACT_FACE_EMBEDDING thất bại cho [{}]: errorCode={}, errorMessage={}, correlationId={}",
                        code, errorCode, reply != null ? reply.getErrorMessage() : null,
                        reply != null ? reply.getCorrelationId() : null);
                throw edgeExtractionFailure(errorCode);
            }

            Map<String, Object> data = reply.getData();
            Integer faceCount = data.get("face_count") instanceof Number n ? n.intValue() : null;
            List<Float> vector = new ArrayList<>();
            if (data.get("embedding_front") instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Number n) {
                        vector.add(n.floatValue());
                    }
                }
            }

            AiFaceRegistrationResponseDto dto = new AiFaceRegistrationResponseDto();
            dto.setSuccess(true);
            dto.setCode(code);
            dto.setFaceCount(faceCount);
            dto.setEmbeddingFront(vector);
            return dto;
        } finally {
            minioStorageService.removeObjectSafely(bucket, stagingKey);
        }
    }

    /**
     * Lỗi RPC EXTRACT_FACE_EMBEDDING của thiết bị biên (ai-service/src/integration/edge_agent.py) -> cùng kết quả với nhánh HTTP:
     * <ul>
     *   <li>lỗi do ảnh:
     *       <ul>
     *         <li>{@code ERR_NO_FACE_DETECTED} -> 422 {@link #NO_FACE_MESSAGE} (như HTTP 400 errorCode ERR_NO_FACE_DETECTED).
     *             Nhiều hơn 1 mặt không phải lỗi RPC (edge trả success, face_count &gt; 1, registerFace kiểm chung như nhánh HTTP);</li>
     *         <li>{@code ERR_IMAGE_DECODE} -> 422 {@link #IMAGE_UNPROCESSABLE_MESSAGE} (như HTTP 400 khi decode_image_safely lỗi).</li>
     *       </ul>
     *   </li>
     *   <li>mọi mã khác (ERR_MODEL_NOT_READY, ERR_FACE_STAGING_NOT_FOUND, ERR_EDGE_INTERNAL, ERR_RPC_UNKNOWN_ACTION), mã lạ,
     *       thiếu mã -> 503 {@link #AI_SERVICE_ERROR_MESSAGE} (như ai-service trả 5xx).</li>
     * </ul>
     * Quá hạn RPC / mất kết nối là CameraException, registerFace trả 503 {@link #AI_SERVICE_UNAVAILABLE_MESSAGE} như lỗi
     * kết nối HTTP.
     */
    static RuntimeException edgeExtractionFailure(String errorCode) {
        if (AiFaceRegistrationErrors.EDGE_NO_FACE_ERROR_CODE.equals(errorCode)) {
            return new FaceDetectionException(FaceErrorCode.ERR_FACE_005, NO_FACE_MESSAGE);
        }
        if (AiFaceRegistrationErrors.EDGE_IMAGE_DECODE_ERROR_CODE.equals(errorCode)) {
            return new FaceDetectionException(FaceErrorCode.ERR_FACE_007, IMAGE_UNPROCESSABLE_MESSAGE);
        }
        return new AiServiceUnavailableException(AI_SERVICE_ERROR_MESSAGE);
    }

    private String formatVectorString(List<Float> vector) {
        if (vector == null || vector.isEmpty()) {
            return "[0.0]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.size(); i++) {
            sb.append(vector.get(i));
            if (i < vector.size() - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private FaceDataResponseDto toDto(FaceData entity) {
        User u = entity.getUser();
        return FaceDataResponseDto.builder()
                .id(entity.getId())
                .userId(u != null ? u.getId() : null)
                .code(entity.getCode())
                .imageFrontUrl(entity.getImageFrontUrl())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        public NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return this.filename;
        }
    }
}
