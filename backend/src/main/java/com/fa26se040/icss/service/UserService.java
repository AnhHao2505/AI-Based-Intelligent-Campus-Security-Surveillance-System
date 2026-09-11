package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.FaceDataResponseDto;
import com.fa26se040.icss.dto.UserInfo;
import com.fa26se040.icss.dto.user.StaffAccountCreateRequest;
import com.fa26se040.icss.dto.user.StaffAccountCreateResponse;
import com.fa26se040.icss.dto.user.UserListResponse;
import com.fa26se040.icss.dto.user.UserPageResponse;
import com.fa26se040.icss.entity.PasswordResetToken;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.DuplicateResourceException;
import com.fa26se040.icss.exception.InvalidRoleAssignmentException;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.repository.PasswordResetTokenRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    public static final List<Role> SYSTEM_ROLES = List.of(
            Role.ADMIN,
            Role.FACILITY_MANAGER,
            Role.INTERNAL_GUARD,
            Role.OUTSOURCED_GUARD
    );

    public static final List<Role> NORMAL_ROLES = List.of(
            Role.NORMAL_USER
    );

    private static final String CHAR_LOWER = "abcdefghijklmnopqrstuvwxyz";
    private static final String CHAR_UPPER = CHAR_LOWER.toUpperCase();
    private static final String NUMBER = "0123456789";
    private static final String DATA_FOR_RANDOM_STRING = CHAR_LOWER + CHAR_UPPER + NUMBER;
    private static final SecureRandom random = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final FaceDataService faceDataService;
    private final MinioStorageService minioStorageService;
    // private final NotificationService notificationService;

    @Transactional
    public StaffAccountCreateResponse createStaffAccount(StaffAccountCreateRequest request) {
        log.info("Creating staff account for userCode: {}, email: {}, role: {}", request.getUserCode(), request.getEmail(), request.getRole());

        // BR-02: Check operational staff role (Reject ADMIN or NORMAL_USER for this API)
        Role role = request.getRole();
        if (role == Role.ADMIN || role == Role.NORMAL_USER) {
            throw new InvalidRoleAssignmentException("Không thể tạo tài khoản với vai trò " + role + " qua API này. Chỉ hỗ trợ FACILITY_MANAGER, INTERNAL_GUARD, OUTSOURCED_GUARD.");
        }

        // BR-03: Check userCode duplicate (active users only)
        if (userRepository.existsByUserCodeAndDeletedAtIsNull(request.getUserCode().trim())) {
            throw new DuplicateResourceException("Mã cán bộ " + request.getUserCode() + " đã tồn tại trong hệ thống.");
        }

        // BR-03: Check email duplicate (active users only)
        if (userRepository.existsByEmailAndDeletedAtIsNull(request.getEmail().trim())) {
            throw new DuplicateResourceException("Email " + request.getEmail() + " đã tồn tại trong hệ thống.");
        }

        // BR-06: Generate temporary password
        String tempPassword = generateRandomPassword(10);
        log.info("Pass {}", tempPassword); // for dev purpose
        String encodedPassword = passwordEncoder.encode(tempPassword);

        // 1. Process Face Registration with AI-Service & MinIO (VAL-05, BR-04, BR-08)
        FaceDataResponseDto faceResponse = faceDataService.registerFace(request.getUserCode().trim(), request.getFaceImage());

        // 2. Begin DB Transaction for User, FaceData & PasswordResetToken (BR-05, BR-06, BR-07)
        try {
            User user = User.builder()
                    .fullName(request.getFullName().trim())
                    .userCode(request.getUserCode().trim())
                    .email(request.getEmail().trim())
                    .password(encodedPassword)
                    .role(role)
                    .isActive(true) // BR-07: is_active = true by default
                    .createdAt(OffsetDateTime.now())
                    .updatedAt(OffsetDateTime.now())
                    .build();

            User savedUser = userRepository.save(user);

            // notificationService.sendStaffAccountSetupEmail(
            //         savedUser.getEmail(),
            //         savedUser.getFullName(),
            //         savedUser.getUserCode(),
            //         tempPassword
            // );

            return StaffAccountCreateResponse.builder()
                    .id(savedUser.getId())
                    .fullName(savedUser.getFullName())
                    .userCode(savedUser.getUserCode())
                    .email(savedUser.getEmail())
                    .role(savedUser.getRole())
                    .isActive(savedUser.getIsActive())
                    .faceData(StaffAccountCreateResponse.FaceDataInfo.builder()
                            .id(faceResponse.getId())
                            .imageFrontUrl(faceResponse.getImageFrontUrl())
                            .build())
                    .createdAt(savedUser.getCreatedAt() != null ? savedUser.getCreatedAt().toInstant() : Instant.now())
                    .build();
        } catch (Exception e) {
            log.error("DB Transaction failed for userCode {}. Deleting uploaded image from MinIO.", request.getUserCode(), e);
            minioStorageService.deleteFaceImage(request.getUserCode().trim());
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public UserInfo getUserByCode(String code) {
        User user = userRepository.findByUserCode(code)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng với mã: " + code));

        return UserInfo.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .userCode(user.getUserCode())
                .email(user.getEmail())
                .role(user.getRole())
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toInstant() : null)
                .build();
    }

    @Transactional(readOnly = true)
    public UserPageResponse getUsers(String keyword, String accountType, Boolean isActive, Pageable pageable) {
        String kw = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;

        List<Role> roleFilter = null;
        if ("SYSTEM".equalsIgnoreCase(accountType)) {
            roleFilter = SYSTEM_ROLES;
        } else if ("NORMAL".equalsIgnoreCase(accountType)) {
            roleFilter = NORMAL_ROLES;
        }

        Page<User> userPage = userRepository.findUsersWithFilters(kw, roleFilter, isActive, pageable);
        Page<UserListResponse> dtoPage = userPage.map(this::toUserListResponse);

        long normalCount = userRepository.countByAccountType(NORMAL_ROLES, isActive);
        long systemCount = userRepository.countByAccountType(SYSTEM_ROLES, isActive);

        return UserPageResponse.builder()
                .users(dtoPage)
                .normalCount(normalCount)
                .systemCount(systemCount)
                .build();
    }

    @Transactional
    public UserListResponse toggleActive(UUID userId, String currentAdminEmail) {
        User user = userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng với ID: " + userId));

        if (currentAdminEmail != null && currentAdminEmail.equalsIgnoreCase(user.getEmail())) {
            throw new IllegalArgumentException("Không thể tự vô hiệu hóa tài khoản của chính mình.");
        }

        user.setIsActive(!user.getIsActive());
        user.setUpdatedAt(OffsetDateTime.now());
        User updatedUser = userRepository.save(user);

        log.info("Toggled active state for user {}: now {}", userId, updatedUser.getIsActive());
        return toUserListResponse(updatedUser);
    }

    @Transactional
    public void softDelete(UUID userId, String currentAdminEmail) {
        User user = userRepository.findById(userId)
                .filter(u -> u.getDeletedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng với ID: " + userId));

        if (currentAdminEmail != null && currentAdminEmail.equalsIgnoreCase(user.getEmail())) {
            throw new IllegalArgumentException("Không thể tự xóa tài khoản của chính mình.");
        }

        user.setDeletedAt(OffsetDateTime.now());
        user.setIsActive(false);
        user.setUpdatedAt(OffsetDateTime.now());
        userRepository.save(user);

        log.info("Soft-deleted user {}", userId);
    }

    public String generateSampleCsv() {
        return "full_name,user_code,email,role\n" +
                "Nguyễn Văn A,NV001,nva@example.com,FACILITY_MANAGER\n" +
                "Trần Thị B,NV002,ttb@example.com,INTERNAL_GUARD\n" +
                "Lê Văn C,NV003,lvc@example.com,OUTSOURCED_GUARD\n";
    }

    private String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(DATA_FOR_RANDOM_STRING.charAt(random.nextInt(DATA_FOR_RANDOM_STRING.length())));
        }
        return sb.toString();
    }

    private UserListResponse toUserListResponse(User user) {
        return UserListResponse.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .userCode(user.getUserCode())
                .email(user.getEmail())
                .role(user.getRole())
                .isActive(user.getIsActive())
                .createdAt(user.getCreatedAt() != null ? user.getCreatedAt().toInstant() : null)
                .build();
    }
}
