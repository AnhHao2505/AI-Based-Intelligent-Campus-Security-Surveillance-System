package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.AiFaceRegistrationResponseDto;
import com.fa26se040.icss.dto.BulkImportResponseDto;
import com.fa26se040.icss.dto.FaceDataResponseDto;
import com.fa26se040.icss.entity.FaceData;
import com.fa26se040.icss.repository.FaceDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaceDataService {

    private final FaceDataRepository faceDataRepository;
    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${ai.service.url:http://localhost:8000}")
    private String aiServiceUrl;

    @Transactional
    public FaceDataResponseDto registerFace(
            String code,
            String fullName,
            MultipartFile frontImage,
            MultipartFile leftImage,
            MultipartFile rightImage
    ) {
        try {
            log.info("Bắt đầu xử lý đăng ký khuôn mặt cho [{}] - {}", code, fullName);

            // 1. Chuẩn bị request multipart gửi sang AI Service
            String aiEndpoint = aiServiceUrl + "/api/v1/faces/process-registration";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("code", code);
            body.add("full_name", fullName);

            body.add("front_image", new NamedByteArrayResource(frontImage.getBytes(), frontImage.getOriginalFilename() != null ? frontImage.getOriginalFilename() : "front.jpg"));
            body.add("left_image", new NamedByteArrayResource(leftImage.getBytes(), leftImage.getOriginalFilename() != null ? leftImage.getOriginalFilename() : "left.jpg"));
            body.add("right_image", new NamedByteArrayResource(rightImage.getBytes(), rightImage.getOriginalFilename() != null ? rightImage.getOriginalFilename() : "right.jpg"));

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            // 2. Gọi AI Service trích xuất Vector 512d và upload MinIO
            ResponseEntity<AiFaceRegistrationResponseDto> response = restTemplate.postForEntity(
                    aiEndpoint, requestEntity, AiFaceRegistrationResponseDto.class
            );

            AiFaceRegistrationResponseDto aiResult = response.getBody();
            if (aiResult == null || !aiResult.isSuccess()) {
                throw new RuntimeException("AI Service không thể trích xuất vector khuôn mặt cho mã: " + code);
            }

            // 3. Chuyển đổi mảng List<Float> sang chuỗi định dạng PostgreSQL pgvector "[0.1,0.2,...]"
            String vecFront = formatVectorString(aiResult.getEmbeddingFront());
            String vecLeft = formatVectorString(aiResult.getEmbeddingLeft());
            String vecRight = formatVectorString(aiResult.getEmbeddingRight());

            // 4. Lưu hoặc cập nhật vào CSDL
            Optional<FaceData> existingOpt = faceDataRepository.findByCode(code);
            FaceData faceData;
            if (existingOpt.isPresent()) {
                faceData = existingOpt.get();
                faceData.setFullName(fullName);
                faceData.setImageFrontUrl(aiResult.getImageFrontUrl());
                faceData.setImageLeftUrl(aiResult.getImageLeftUrl());
                faceData.setImageRightUrl(aiResult.getImageRightUrl());
                faceData.setEmbeddingFront(vecFront);
                faceData.setEmbeddingLeft(vecLeft);
                faceData.setEmbeddingRight(vecRight);
            } else {
                faceData = FaceData.builder()
                        .code(code)
                        .fullName(fullName)
                        .imageFrontUrl(aiResult.getImageFrontUrl())
                        .imageLeftUrl(aiResult.getImageLeftUrl())
                        .imageRightUrl(aiResult.getImageRightUrl())
                        .embeddingFront(vecFront)
                        .embeddingLeft(vecLeft)
                        .embeddingRight(vecRight)
                        .build();
            }

            FaceData saved = faceDataRepository.save(faceData);
            log.info("Lưu thành công dữ liệu khuôn mặt cho [{}] vào Database (ID: {})", code, saved.getId());

            return toDto(saved);

        } catch (Exception e) {
            log.error("Lỗi khi đăng ký khuôn mặt cho [{}]: {}", code, e.getMessage(), e);
            throw new RuntimeException("Lỗi xử lý khuôn mặt: " + e.getMessage(), e);
        }
    }

    @Transactional
    public BulkImportResponseDto importBulkZip(MultipartFile zipFile) {
        log.info("Bắt đầu nạp hàng loạt dataset từ file ZIP: {}", zipFile.getOriginalFilename());
        int total = 0;
        int success = 0;
        int failed = 0;
        List<String> importedCodes = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // Map cấu trúc: code -> { "name": fullName, "front": bytes, "left": bytes, "right": bytes }
        Map<String, Map<String, Object>> datasetMap = new HashMap<>();

        try (InputStream is = zipFile.getInputStream();
             ZipInputStream zis = new ZipInputStream(is)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory() || entry.getName().startsWith("__MACOSX")) {
                    continue;
                }

                String filename = entry.getName();
                // Lấy tên file đơn giản (bỏ qua tên folder nếu có)
                if (filename.contains("/")) {
                    filename = filename.substring(filename.lastIndexOf("/") + 1);
                }
                if (filename.contains("\\")) {
                    filename = filename.substring(filename.lastIndexOf("\\") + 1);
                }

                // Quy tắc đặt tên file: {CODE}_{FULLNAME}_{ANGLE}.jpg hoặc {CODE}_{ANGLE}.jpg
                // Ví dụ: SE150001_NguyenVanA_front.jpg hoặc SE150001_front.jpg
                String baseName = filename.contains(".") ? filename.substring(0, filename.lastIndexOf(".")) : filename;
                String[] parts = baseName.split("_");

                if (parts.length >= 2) {
                    String code = parts[0].trim();
                    String angle = parts[parts.length - 1].toLowerCase().trim(); // front | left | right
                    String fullName = parts.length > 2 ? parts[1].replace("-", " ").trim() : "Sinh vien / Can bo " + code;

                    datasetMap.putIfAbsent(code, new HashMap<>());
                    Map<String, Object> personData = datasetMap.get(code);
                    personData.put("name", fullName);

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        baos.write(buffer, 0, len);
                    }
                    personData.put(angle, baos.toByteArray());
                }
            }

            total = datasetMap.size();
            log.info("Tìm thấy {} người dùng trong file ZIP.", total);

            for (Map.Entry<String, Map<String, Object>> entryItem : datasetMap.entrySet()) {
                String code = entryItem.getKey();
                Map<String, Object> data = entryItem.getValue();
                String fullName = (String) data.getOrDefault("name", "Người dùng " + code);

                byte[] frontBytes = (byte[]) data.get("front");
                byte[] leftBytes = (byte[]) data.get("left");
                byte[] rightBytes = (byte[]) data.get("right");

                // Nếu thiếu ảnh góc trái/phải thì lấy ảnh chính diện thay thế tạm thời
                if (frontBytes == null && leftBytes != null) frontBytes = leftBytes;
                if (frontBytes == null && rightBytes != null) frontBytes = rightBytes;
                if (leftBytes == null && frontBytes != null) leftBytes = frontBytes;
                if (rightBytes == null && frontBytes != null) rightBytes = frontBytes;

                if (frontBytes == null) {
                    failed++;
                    errors.add("Mã " + code + ": Không tìm thấy ảnh khuôn mặt.");
                    continue;
                }

                try {
                    MultipartFile frontFile = new InMemoryMultipartFile("frontImage", "front.jpg", "image/jpeg", frontBytes);
                    MultipartFile leftFile = new InMemoryMultipartFile("leftImage", "left.jpg", "image/jpeg", leftBytes);
                    MultipartFile rightFile = new InMemoryMultipartFile("rightImage", "right.jpg", "image/jpeg", rightBytes);

                    registerFace(code, fullName, frontFile, leftFile, rightFile);
                    success++;
                    importedCodes.add(code + " (" + fullName + ")");
                } catch (Exception e) {
                    failed++;
                    errors.add("Mã " + code + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("Lỗi khi giải nén file ZIP: {}", e.getMessage(), e);
            errors.add("Lỗi giải nén file ZIP: " + e.getMessage());
        }

        return BulkImportResponseDto.builder()
                .totalProcessed(total)
                .successCount(success)
                .failedCount(failed)
                .importedCodes(importedCodes)
                .errorMessages(errors)
                .build();
    }

    @Transactional(readOnly = true)
    public Page<FaceDataResponseDto> getAllFaces(String keyword, Pageable pageable) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            String kw = keyword.trim();
            return faceDataRepository.findByCodeContainingIgnoreCaseOrFullNameContainingIgnoreCase(kw, kw, pageable)
                    .map(this::toDto);
        }
        return faceDataRepository.findAll(pageable).map(this::toDto);
    }

    @Transactional
    public void deleteFace(UUID id) {
        faceDataRepository.deleteById(id);
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
        return FaceDataResponseDto.builder()
                .id(entity.getId())
                .code(entity.getCode())
                .fullName(entity.getFullName())
                .imageFrontUrl(entity.getImageFrontUrl())
                .imageLeftUrl(entity.getImageLeftUrl())
                .imageRightUrl(entity.getImageRightUrl())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    // Helper ByteArrayResource kèm filename để RestTemplate gửi multipart chuẩn
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

    // Helper InMemoryMultipartFile để tạo MultipartFile từ byte[] khi giải nén ZIP
    private record InMemoryMultipartFile(String name, String originalFilename, String contentType,
                                         byte[] bytes) implements MultipartFile {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getOriginalFilename() {
            return originalFilename;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public boolean isEmpty() {
            return bytes == null || bytes.length == 0;
        }

        @Override
        public long getSize() {
            return bytes.length;
        }

        @Override
        public byte[] getBytes() {
            return bytes;
        }

        @Override
        public InputStream getInputStream() {
            return new java.io.ByteArrayInputStream(bytes);
        }

        @Override
        public void transferTo(java.io.File dest) throws IllegalStateException, java.io.IOException {
            new java.io.FileOutputStream(dest).write(bytes);
        }
    }
}
