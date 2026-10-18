package com.fa26se040.icss.controller;

import com.fa26se040.icss.dto.audit.AuditActor;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.AuditAction;
import com.fa26se040.icss.enums.AuditTargetType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.service.AuditService;
import com.fa26se040.icss.service.MinioStorageService;
import io.minio.StatObjectResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phục vụ ảnh từ các bucket MinIO private qua backend. Yêu cầu JWT như API khác (không permitAll).
 *
 * <ul>
 *   <li>Phân quyền theo bucket, mặc định từ chối: camera-snapshots, face-profiles -> ADMIN;
 *       security-evidence -> ADMIN, GUARD, FACILITY_MANAGER. Tên bucket lấy từ MinioStorageService (minio.bucket.*).</li>
 *   <li>Xem ảnh khuôn mặt / bằng chứng ghi audit VIEW_PHOTO; không ghi được audit -> 500, không trả ảnh.</li>
 *   <li>Trả stream, Content-Type theo statObject, {@code Cache-Control: private, no-store}, {@code nosniff}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/media")
@Slf4j
@RequiredArgsConstructor
public class MediaController {

    static final int MAX_OBJECT_NAME_LENGTH = 512;
    static final String CACHE_CONTROL_VALUE = "private, no-store";

    private final MinioStorageService minioStorageService;
    private final AuditService auditService;
    private final UserRepository userRepository;

    @GetMapping("/{bucket}/**")
    public ResponseEntity<InputStreamResource> getMedia(
            @PathVariable("bucket") String bucket,
            HttpServletRequest request,
            Authentication authentication
    ) {
        // B2: bucket ngoài bảng hoặc không đủ role -> 403
        Set<Role> allowedRoles = bucketAccess().get(bucket);
        if (allowedRoles == null || rolesOf(authentication).stream().noneMatch(allowedRoles::contains)) {
            log.warn("[MEDIA] Từ chối {} xem bucket {}", authentication.getName(), bucket);
            throw new AccessDeniedException("Bạn không có quyền xem ảnh này");
        }

        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String bestMatchPattern = (String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String objectName = new AntPathMatcher().extractPathWithinPattern(bestMatchPattern, fullPath);
        validateObjectName(objectName);

        // B3: không tồn tại -> 404, body không chứa thông điệp lỗi MinIO
        Optional<StatObjectResponse> stat;
        try {
            stat = minioStorageService.statObject(bucket, objectName);
        } catch (Exception e) {
            log.error("[MEDIA] Lỗi đọc metadata {}/{}: {}", bucket, objectName, e.getMessage());
            throw new MediaStorageException();
        }
        if (stat.isEmpty()) {
            throw new ResourceNotFoundException("Không tìm thấy ảnh");
        }

        InputStream stream;
        try {
            stream = minioStorageService.getObject(bucket, objectName);
        } catch (Exception e) {
            log.error("[MEDIA] Lỗi đọc object {}/{}: {}", bucket, objectName, e.getMessage());
            throw new MediaStorageException();
        }

        // B5: ghi audit trước khi trả ảnh; lỗi -> đóng stream, 500 (GlobalExceptionHandler)
        try {
            recordView(bucket, objectName, authentication);
        } catch (RuntimeException e) {
            closeQuietly(stream);
            throw e;
        }

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(contentTypeOf(stat.get()))
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL_VALUE)
                .header("X-Content-Type-Options", "nosniff");
        if (stat.get().size() >= 0) {
            response.contentLength(stat.get().size());
        }
        return response.body(new InputStreamResource(stream));
    }

    /** Bucket -> role được xem. Bucket không có trong map -> từ chối. */
    private Map<String, Set<Role>> bucketAccess() {
        return Map.of(
                minioStorageService.getSnapshotsBucket(), EnumSet.of(Role.ADMIN),
                minioStorageService.getFacesBucket(), EnumSet.of(Role.ADMIN),
                minioStorageService.getEvidenceBucket(), EnumSet.of(Role.ADMIN, Role.GUARD, Role.FACILITY_MANAGER)
        );
    }

    /** face-profiles -> FACE_PROFILE (targetId = userCode, phần trước dấu / đầu tiên); security-evidence -> SECURITY_EVIDENCE. */
    private void recordView(String bucket, String objectName, Authentication authentication) {
        AuditTargetType targetType;
        String targetId;
        if (bucket.equals(minioStorageService.getFacesBucket())) {
            targetType = AuditTargetType.FACE_PROFILE;
            int slash = objectName.indexOf('/');
            targetId = slash > 0 ? objectName.substring(0, slash) : objectName;
        } else if (bucket.equals(minioStorageService.getEvidenceBucket())) {
            targetType = AuditTargetType.SECURITY_EVIDENCE;
            targetId = objectName;
        } else {
            return; // camera-snapshots: không ghi audit
        }
        User actor = userRepository.findByEmail(authentication.getName())
                .orElseThrow(() -> new UnauthorizedException("Không tìm thấy thông tin người dùng"));
        auditService.record(targetType, AuditAction.VIEW_PHOTO, targetId, null, null,
                null, null, null, AuditActor.user(actor));
    }

    /** B3: không rỗng, không "..", không bắt đầu "/", không ký tự điều khiển, ≤ 512 ký tự -> sai thì 400. */
    static void validateObjectName(String objectName) {
        boolean invalid = objectName == null
                || objectName.isBlank()
                || objectName.length() > MAX_OBJECT_NAME_LENGTH
                || objectName.contains("..")
                || objectName.startsWith("/")
                || objectName.chars().anyMatch(Character::isISOControl);
        if (invalid) {
            throw new IllegalArgumentException("Đường dẫn ảnh không hợp lệ");
        }
    }

    private static MediaType contentTypeOf(StatObjectResponse stat) {
        String contentType = stat.contentType();
        if (contentType != null && !contentType.isBlank()) {
            try {
                return MediaType.parseMediaType(contentType);
            } catch (InvalidMediaTypeException e) {
                log.warn("[MEDIA] Content-Type không hợp lệ từ storage: {}", contentType);
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    private static Set<Role> rolesOf(Authentication authentication) {
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (GrantedAuthority authority : authentication.getAuthorities()) {
            String name = authority.getAuthority();
            if (name != null && name.startsWith("ROLE_")) {
                try {
                    roles.add(Role.valueOf(name.substring("ROLE_".length())));
                } catch (IllegalArgumentException ignored) {
                    // role lạ -> không cấp quyền
                }
            }
        }
        return roles;
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // đang trả lỗi audit, bỏ qua lỗi đóng stream
        }
    }

    /** Lỗi storage khác "không tồn tại" -> 500 qua handler chung (không lộ thông điệp MinIO). */
    static class MediaStorageException extends RuntimeException {
        MediaStorageException() {
            super("MEDIA_STORAGE_ERROR");
        }
    }
}
