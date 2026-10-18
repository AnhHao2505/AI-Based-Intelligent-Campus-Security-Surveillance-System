package com.fa26se040.icss.controller;

import com.fa26se040.icss.service.MinioStorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.InputStream;
import java.util.Set;

/**
 * Controller phục vụ dữ liệu media/ảnh từ các bucket MinIO private.
 * Cho phép frontend và client xem ảnh an toàn qua backend mà không cần mở public policy trên MinIO.
 */
@RestController
@RequestMapping("/api/media")
@Slf4j
@RequiredArgsConstructor
public class MediaController {

    private final MinioStorageService minioStorageService;

    private static final Set<String> ALLOWED_BUCKETS = Set.of(
            "face-profiles",
            "security-evidence",
            "camera-snapshots"
    );

    @GetMapping("/{bucket}/**")
    public ResponseEntity<byte[]> getMedia(
            @PathVariable("bucket") String bucket,
            HttpServletRequest request
    ) {
        if (!ALLOWED_BUCKETS.contains(bucket)) {
            log.warn("[MEDIA] Từ chối truy cập bucket không được phép: {}", bucket);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String bestMatchPattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String objectName = new AntPathMatcher().extractPathWithinPattern(bestMatchPattern, fullPath);

        if (objectName == null || objectName.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        try (InputStream inputStream = minioStorageService.getObject(bucket, objectName)) {
            byte[] bytes = StreamUtils.copyToByteArray(inputStream);

            MediaType mediaType = MediaType.IMAGE_JPEG;
            String lower = objectName.toLowerCase();
            if (lower.endsWith(".png")) {
                mediaType = MediaType.IMAGE_PNG;
            } else if (lower.endsWith(".webp")) {
                mediaType = MediaType.parseMediaType("image/webp");
            }

            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
                    .body(bytes);

        } catch (Exception e) {
            log.warn("[MEDIA] Không tìm thấy hoặc lỗi đọc file ({}/{}): {}", bucket, objectName, e.getMessage());
            return ResponseEntity.notFound().build();
        }
    }
}
