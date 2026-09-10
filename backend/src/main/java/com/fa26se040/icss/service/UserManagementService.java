package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.user.UserCountsResponse;
import com.fa26se040.icss.dto.user.UserImportResponse;
import com.fa26se040.icss.dto.user.UserImportRowError;
import com.fa26se040.icss.dto.user.UserListResponse;
import com.fa26se040.icss.dto.user.UserUpdateRequest;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserManagementService {

    public static final List<Role> SYSTEM_ROLES = List.of(
        Role.ADMIN,
        Role.FACILITY_MANAGER,
        Role.INTERNAL_GUARD,
        Role.OUTSOURCED_GUARD
    );

    public static final List<Role> NORMAL_ROLES = List.of(
        Role.NORMAL_USER
    );

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@(.+)$");
    private static final String DEFAULT_PASSWORD = "123456";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional(readOnly = true)
    public Page<UserListResponse> getUsers(String keyword, String accountType, Boolean isActive, Pageable pageable) {
        String trimmedKeyword = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        Collection<Role> roles;

        if ("NORMAL".equalsIgnoreCase(accountType)) {
            roles = NORMAL_ROLES;
        } else if ("SYSTEM".equalsIgnoreCase(accountType)) {
            roles = SYSTEM_ROLES;
        } else {
            roles = List.of(Role.values());
        }

        return userRepository.searchFilteredUsers(trimmedKeyword, roles, isActive, pageable)
                .map(UserListResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public UserCountsResponse getUserCounts() {
        long normalCount = userRepository.countByRoleAndDeletedAtIsNull(Role.NORMAL_USER);
        long systemCount = userRepository.countByRolesAndDeletedAtIsNull(SYSTEM_ROLES);
        return new UserCountsResponse(normalCount, systemCount);
    }

    @Transactional
    public UserListResponse updateUser(UUID id, UserUpdateRequest request) {
        log.info("Updating user with id: {}", id);
        User user = userRepository.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        user.setFullName(request.fullName().trim());
        user.setRole(request.role());

        User savedUser = userRepository.save(user);
        log.info("User {} updated successfully with new role {}", id, request.role());
        return UserListResponse.fromEntity(savedUser);
    }

    @Transactional
    public UserListResponse toggleActive(UUID id, String currentUserEmail) {
        log.info("Toggling active status for user with id: {}", id);
        User user = userRepository.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        if (currentUserEmail != null && user.getEmail().equalsIgnoreCase(currentUserEmail)) {
            throw new IllegalArgumentException("Không thể tự thay đổi trạng thái tài khoản của chính bạn");
        }

        boolean newStatus = !Boolean.TRUE.equals(user.getIsActive());
        user.setIsActive(newStatus);

        User savedUser = userRepository.save(user);
        log.info("User {} active status toggled to {}", id, newStatus);
        return UserListResponse.fromEntity(savedUser);
    }

    @Transactional
    public void softDelete(UUID id, String currentUserEmail) {
        log.info("Soft-deleting user with id: {}", id);
        User user = userRepository.findById(id)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));

        if (currentUserEmail != null && user.getEmail().equalsIgnoreCase(currentUserEmail)) {
            throw new IllegalArgumentException("Không thể tự xóa tài khoản của chính bạn");
        }

        user.setIsActive(false);
        user.setDeletedAt(OffsetDateTime.now());
        userRepository.save(user);
        log.info("User {} soft-deleted successfully", id);
    }

    @Transactional
    public UserImportResponse importUsers(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File tải lên không được để trống.");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename != null && !originalFilename.toLowerCase().endsWith(".csv")) {
            throw new IllegalArgumentException("Định dạng file không hợp lệ. Vui lòng tải lên file định dạng .csv");
        }

        List<UserImportRowError> errors = new ArrayList<>();
        int totalProcessed = 0;
        int successCount = 0;
        int failedCount = 0;

        Set<String> seenUserCodesInFile = new HashSet<>();
        Set<String> seenEmailsInFile = new HashSet<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null || headerLine.trim().isEmpty()) {
                throw new IllegalArgumentException("File CSV không có dữ liệu tiêu đề.");
            }

            // Remove UTF-8 BOM if present
            if (headerLine.startsWith("\uFEFF")) {
                headerLine = headerLine.substring(1);
            }

            char delimiter = detectDelimiter(headerLine);
            List<String> headers = parseCsvLine(headerLine, delimiter);

            // Column indices
            int userCodeIdx = -1;
            int fullNameIdx = -1;
            int emailIdx = -1;
            int passwordIdx = -1;

            for (int i = 0; i < headers.size(); i++) {
                String h = headers.get(i).trim().toLowerCase();
                if (h.contains("usercode") || h.contains("user_code") || h.contains("mã định danh") || h.contains("ma dinh danh") || h.equals("mã") || h.equals("code")) {
                    userCodeIdx = i;
                } else if (h.contains("fullname") || h.contains("full_name") || h.contains("họ và tên") || h.contains("ho va ten") || h.contains("họ tên") || h.contains("tên") || h.equals("name")) {
                    fullNameIdx = i;
                } else if (h.contains("email")) {
                    emailIdx = i;
                } else if (h.contains("password") || h.contains("mật khẩu") || h.contains("mat khau") || h.equals("pass")) {
                    passwordIdx = i;
                }
            }

            // Fallback to default positional mapping if header name matching didn't resolve all required
            if (userCodeIdx == -1 || fullNameIdx == -1 || emailIdx == -1) {
                if (headers.size() >= 3) {
                    userCodeIdx = 0;
                    fullNameIdx = 1;
                    emailIdx = 2;
                    passwordIdx = headers.size() >= 4 ? 3 : -1;
                } else {
                    throw new IllegalArgumentException("File CSV thiếu các cột bắt buộc (userCode, fullName, email).");
                }
            }

            String line;
            int rowNum = 1; // Row 1 is header, data rows start at row 2

            List<User> usersToSave = new ArrayList<>();

            while ((line = reader.readLine()) != null) {
                rowNum++;
                if (line.trim().isEmpty()) {
                    continue;
                }

                totalProcessed++;
                List<String> tokens = parseCsvLine(line, delimiter);

                String userCode = userCodeIdx < tokens.size() ? tokens.get(userCodeIdx).trim() : "";
                String fullName = fullNameIdx < tokens.size() ? tokens.get(fullNameIdx).trim() : "";
                String email = emailIdx < tokens.size() ? tokens.get(emailIdx).trim() : "";
                String rawPassword = (passwordIdx != -1 && passwordIdx < tokens.size()) ? tokens.get(passwordIdx).trim() : "";

                // Field validations
                if (userCode.isEmpty()) {
                    errors.add(new UserImportRowError(rowNum, userCode, "Mã định danh không được để trống"));
                    failedCount++;
                    continue;
                }

                if (fullName.isEmpty()) {
                    errors.add(new UserImportRowError(rowNum, userCode, "Họ và tên không được để trống"));
                    failedCount++;
                    continue;
                }

                if (email.isEmpty()) {
                    errors.add(new UserImportRowError(rowNum, userCode, "Email không được để trống"));
                    failedCount++;
                    continue;
                }

                if (!EMAIL_PATTERN.matcher(email).matches()) {
                    errors.add(new UserImportRowError(rowNum, userCode, "Định dạng email không hợp lệ (" + email + ")"));
                    failedCount++;
                    continue;
                }

                // Check duplicates within uploaded file
                String lowerCode = userCode.toLowerCase();
                String lowerEmail = email.toLowerCase();

                if (!seenUserCodesInFile.add(lowerCode)) {
                    errors.add(new UserImportRowError(rowNum, userCode, "Mã định danh trùng lặp trong file"));
                    failedCount++;
                    continue;
                }

                if (!seenEmailsInFile.add(lowerEmail)) {
                    errors.add(new UserImportRowError(rowNum, userCode, "Email trùng lặp trong file (" + email + ")"));
                    failedCount++;
                    continue;
                }

                // Check duplicates against database
                if (userRepository.existsByUserCode(userCode)) {
                    errors.add(new UserImportRowError(rowNum, userCode, "Mã định danh đã tồn tại trong hệ thống"));
                    failedCount++;
                    continue;
                }

                if (userRepository.existsByEmail(email)) {
                    errors.add(new UserImportRowError(rowNum, userCode, "Email đã tồn tại trong hệ thống (" + email + ")"));
                    failedCount++;
                    continue;
                }

                // Default password if empty
                if (rawPassword.isEmpty()) {
                    rawPassword = DEFAULT_PASSWORD;
                }

                // Construct Normal User
                User newUser = User.builder()
                        .userCode(userCode)
                        .fullName(fullName)
                        .email(email)
                        .password(passwordEncoder.encode(rawPassword))
                        .role(Role.NORMAL_USER)
                        .isActive(true)
                        .build();

                usersToSave.add(newUser);
                successCount++;
            }

            if (!usersToSave.isEmpty()) {
                userRepository.saveAll(usersToSave);
                log.info("Batch imported {} normal users successfully.", usersToSave.size());
            }

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error parsing CSV file", e);
            throw new RuntimeException("Lỗi khi đọc file CSV: " + e.getMessage());
        }

        return new UserImportResponse(totalProcessed, successCount, failedCount, errors);
    }

    public byte[] generateSampleCsv() {
        String csv = "userCode,fullName,email,password\n" +
                "SE193843,Nguyễn Anh Hào,haonase193843@fpt.edu.vn,\n" +
                "SE182049,Phan Thị Minh Châu,chauptmse182049@fpt.edu.vn,Pass123456\n" +
                "SE171203,Trần Minh Anh,anhtmse171203@fpt.edu.vn,\n" +
                "SE183491,Võ Hoàng Gia Bảo,baovhgse183491@fpt.edu.vn,\n" +
                "SE160912,Đặng Thanh Thảo,thaodttse160912@fpt.edu.vn,\n";
        return csv.getBytes(StandardCharsets.UTF_8);
    }

    private char detectDelimiter(String headerLine) {
        int semicolonCount = 0;
        int commaCount = 0;
        for (char c : headerLine.toCharArray()) {
            if (c == ';') semicolonCount++;
            else if (c == ',') commaCount++;
        }
        return semicolonCount > commaCount ? ';' : ',';
    }

    private List<String> parseCsvLine(String line, char delimiter) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString().trim());
        return tokens;
    }
}
