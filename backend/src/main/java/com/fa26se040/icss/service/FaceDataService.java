package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.AiFaceRegistrationResponseDto;
import com.fa26se040.icss.dto.FaceDataResponseDto;
import com.fa26se040.icss.entity.FaceData;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.exception.AiServiceUnavailableException;
import com.fa26se040.icss.exception.FaceDetectionException;
import com.fa26se040.icss.repository.FaceDataRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceDataService {

    private final FaceDataRepository faceDataRepository;
    private final UserRepository userRepository;
    private final MinioStorageService minioStorageService;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Transactional
    public FaceDataResponseDto registerFace(
            String code,
            MultipartFile frontImage
    ) {
        try {
            log.info("Bắt đầu xử lý đăng ký khuôn mặt cho [{}]", code);

            if (frontImage == null || frontImage.isEmpty()) {
                throw new IllegalArgumentException("Ảnh khuôn mặt không được để trống.");
            }

            // VAL-05: Định dạng JPG/PNG, kích thước tối đa 350kB
            long maxSizeBytes = 350 * 1024; // 350 KB
            if (frontImage.getSize() > maxSizeBytes) {
                throw new IllegalArgumentException(String.format("Kích thước ảnh vượt quá giới hạn tối đa 350KB (dung lượng file: %.1f KB)", frontImage.getSize() / 1024.0));
            }

            String contentType = frontImage.getContentType();
            String filename = frontImage.getOriginalFilename() != null ? frontImage.getOriginalFilename().toLowerCase() : "";
            boolean isValidFormat = (contentType != null && (contentType.equalsIgnoreCase("image/jpeg") || contentType.equalsIgnoreCase("image/jpg") || contentType.equalsIgnoreCase("image/png")))
                    || (filename.endsWith(".jpg") || filename.endsWith(".jpeg") || filename.endsWith(".png"));

            if (!isValidFormat) {
                throw new IllegalArgumentException("Định dạng file không hợp lệ. Chỉ chấp nhận file ảnh định dạng JPG hoặc PNG.");
            }

            byte[] imageBytes = frontImage.getBytes();

            // 1. Chuẩn bị request multipart gửi sang AI Service để detect khuôn mặt & trích xuất vector embedding
            String aiEndpoint = aiServiceUrl + "/api/v1/faces/process-registration";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("code", code);
            body.add("front_image", new NamedByteArrayResource(imageBytes, frontImage.getOriginalFilename() != null ? frontImage.getOriginalFilename() : "front.jpg"));

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            // 2. Gọi AI Service trích xuất Vector 512d
            ResponseEntity<AiFaceRegistrationResponseDto> response = restTemplate.postForEntity(
                    aiEndpoint, requestEntity, AiFaceRegistrationResponseDto.class
            );

            AiFaceRegistrationResponseDto aiResult = response.getBody();
            if (aiResult == null || !aiResult.isSuccess()) {
                throw new FaceDetectionException("AI Service không thể trích xuất vector khuôn mặt cho mã: " + code);
            }

            // Kiểm tra số lượng khuôn mặt phát hiện (BR-04)
            if (aiResult.getFaceCount() != null) {
                if (aiResult.getFaceCount() > 1) {
                    throw new FaceDetectionException("Ảnh chứa nhiều hơn 1 khuôn mặt");
                }
                if (aiResult.getFaceCount() == 0) {
                    throw new FaceDetectionException("Không phát hiện thấy khuôn mặt trong ảnh.");
                }
            }

            // 3. Upload ảnh hồ sơ lên MinIO từ phía Backend
            String imageUrl = minioStorageService.uploadFaceImage(imageBytes, code);

            // 4. Chuyển đổi mảng List<Float> sang chuỗi định dạng PostgreSQL pgvector "[0.1,0.2,...]"
            String vecFront = formatVectorString(aiResult.getEmbeddingFront());

            // 4. Lưu hoặc cập nhật vào CSDL
            Optional<FaceData> existingOpt = faceDataRepository.findByCode(code);
            User matchedUser = userRepository.findByUserCode(code).orElse(null);

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

            return toDto(saved);

        } catch (ResourceAccessException e) {
            log.error("Không thể kết nối đến AI Service cho [{}]: {}", code, e.getMessage());
            throw new AiServiceUnavailableException("Dịch vụ AI hiện không khả dụng. Vui lòng thử lại sau.");
        } catch (HttpStatusCodeException e) {
            log.error("AI Service trả về lỗi [{}] cho [{}]: {}", e.getStatusCode(), code, e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 422 || e.getStatusCode().value() == 400) {
                throw new FaceDetectionException("Không thể xử lý khuôn mặt trong ảnh: " + e.getResponseBodyAsString());
            }
            throw new AiServiceUnavailableException("Dịch vụ AI gặp lỗi khi xử lý ảnh. Vui lòng thử lại sau.");
        } catch (FaceDetectionException | AiServiceUnavailableException e) {
            throw e;
        } catch (Exception e) {
            log.error("Lỗi khi đăng ký khuôn mặt cho [{}]: {}", code, e.getMessage(), e);
            throw new RuntimeException("Lỗi xử lý khuôn mặt: " + e.getMessage(), e);
        }
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
