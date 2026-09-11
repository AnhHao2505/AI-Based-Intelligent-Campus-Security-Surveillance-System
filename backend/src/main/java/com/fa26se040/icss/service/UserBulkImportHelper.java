package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.BulkImportRowResult;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserBulkImportHelper {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FaceDataService faceDataService;
    private final MinioStorageService minioStorageService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BulkImportRowResult processSingleRow(
            int rowIndex,
            String rawUserCode,
            String rawFullName,
            String rawEmail,
            byte[] imageBytes,
            String imageFileName,
            String tempPassword
    ) {
        String userCode = rawUserCode != null ? rawUserCode.trim() : "";
        String fullName = rawFullName != null ? rawFullName.trim() : "";
        String email = rawEmail != null ? rawEmail.trim() : "";

        // 1. Validate fields
        if (userCode.isBlank()) {
            return buildError(rowIndex, userCode, fullName, email, "Mã người dùng không được để trống");
        }
        if (userCode.length() > 50) {
            return buildError(rowIndex, userCode, fullName, email, "Mã người dùng không được vượt quá 50 ký tự");
        }
        if (userRepository.existsByUserCodeAndDeletedAtIsNull(userCode)) {
            return buildError(rowIndex, userCode, fullName, email, "Mã người dùng đã tồn tại trong hệ thống");
        }

        if (fullName.isBlank()) {
            return buildError(rowIndex, userCode, fullName, email, "Họ và tên không được để trống");
        }
        if (fullName.length() > 100) {
            return buildError(rowIndex, userCode, fullName, email, "Họ và tên không được vượt quá 100 ký tự");
        }

        if (email.isBlank()) {
            return buildError(rowIndex, userCode, fullName, email, "Email không được để trống");
        }
        if (email.length() > 100) {
            return buildError(rowIndex, userCode, fullName, email, "Email không được vượt quá 100 ký tự");
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return buildError(rowIndex, userCode, fullName, email, "Định dạng Email không hợp lệ");
        }
        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            return buildError(rowIndex, userCode, fullName, email, "Email đã tồn tại trong hệ thống");
        }

        // 2. Validate face image
        if (imageBytes == null || imageBytes.length == 0) {
            return buildError(rowIndex, userCode, fullName, email, "Không tìm thấy file ảnh tương ứng trong thư mục images/ (yêu cầu images/" + userCode + ".jpg hoặc .png)");
        }
        long maxSizeBytes = 350 * 1024; // 350KB
        if (imageBytes.length > maxSizeBytes) {
            return buildError(rowIndex, userCode, fullName, email, String.format("Kích thước ảnh vượt quá giới hạn tối đa 350KB (dung lượng file: %.1f KB)", imageBytes.length / 1024.0));
        }

        String lowerImgName = imageFileName != null ? imageFileName.toLowerCase() : "";
        if (!(lowerImgName.endsWith(".jpg") || lowerImgName.endsWith(".jpeg") || lowerImgName.endsWith(".png"))) {
            return buildError(rowIndex, userCode, fullName, email, "Định dạng file ảnh không hợp lệ. Chỉ chấp nhận file JPG hoặc PNG.");
        }

        // 3. Perform DB User creation & Face Registration inside try-catch
        try {
            User user = User.builder()
                    .userCode(userCode)
                    .fullName(fullName)
                    .email(email)
                    .password(passwordEncoder.encode(tempPassword))
                    .role(Role.NORMAL_USER)
                    .isActive(true)
                    .createdAt(OffsetDateTime.now())
                    .updatedAt(OffsetDateTime.now())
                    .build();

            userRepository.save(user);

            MultipartFile imageMultipart = new InMemoryMultipartFile(
                    "frontImage",
                    imageFileName,
                    getContentType(imageFileName),
                    imageBytes
            );

            faceDataService.registerFace(userCode, imageMultipart);

            return BulkImportRowResult.builder()
                    .rowIndex(rowIndex)
                    .userCode(userCode)
                    .fullName(fullName)
                    .email(email)
                    .status("SUCCESS")
                    .build();

        } catch (Exception e) {
            log.error("Lỗi khi xử lý dòng {}: {}", rowIndex, e.getMessage());
            // Mark transaction as rollback-only so User/FaceData DB records are undone
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();

            // Compensating action: cleanup MinIO if upload succeeded before exception
            try {
                minioStorageService.deleteFaceImage(userCode);
            } catch (Exception minioEx) {
                log.warn("Không thể xóa ảnh MinIO của [{}] khi rollback: {}", userCode, minioEx.getMessage());
            }

            String errorMsg = e.getMessage();
            if (errorMsg == null || errorMsg.isBlank()) {
                errorMsg = "Lỗi hệ thống khi xử lý người dùng " + userCode;
            }

            return buildError(rowIndex, userCode, fullName, email, errorMsg);
        }
    }

    private BulkImportRowResult buildError(int rowIndex, String userCode, String fullName, String email, String errorMessage) {
        return BulkImportRowResult.builder()
                .rowIndex(rowIndex)
                .userCode(userCode)
                .fullName(fullName)
                .email(email)
                .status("FAILED")
                .errorMessage(errorMessage)
                .build();
    }

    private String getContentType(String filename) {
        if (filename == null) return "image/jpeg";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        return "image/jpeg";
    }

    public static class InMemoryMultipartFile implements MultipartFile {
        private final String name;
        private final String originalFilename;
        private final String contentType;
        private final byte[] bytes;

        public InMemoryMultipartFile(String name, String originalFilename, String contentType, byte[] bytes) {
            this.name = name;
            this.originalFilename = originalFilename;
            this.contentType = contentType;
            this.bytes = bytes;
        }

        @Override public String getName() { return name; }
        @Override public String getOriginalFilename() { return originalFilename; }
        @Override public String getContentType() { return contentType; }
        @Override public boolean isEmpty() { return bytes == null || bytes.length == 0; }
        @Override public long getSize() { return bytes != null ? bytes.length : 0; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException {
            try (FileOutputStream fos = new FileOutputStream(dest)) {
                fos.write(bytes);
            }
        }
    }
}
