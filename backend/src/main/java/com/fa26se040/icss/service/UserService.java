package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.FaceDataResponseDto;
import com.fa26se040.icss.dto.UserInfo;
import com.fa26se040.icss.dto.user.StaffAccountCreateRequest;
import com.fa26se040.icss.dto.user.StaffAccountCreateResponse;
import com.fa26se040.icss.dto.user.UserListResponse;
import com.fa26se040.icss.dto.user.UserPageResponse;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.DuplicateResourceException;
import com.fa26se040.icss.exception.InvalidRoleAssignmentException;
import com.fa26se040.icss.exception.ResourceNotFoundException;
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

        Role role = request.getRole();
        if (role == Role.ADMIN || role == Role.NORMAL_USER) {
            throw new InvalidRoleAssignmentException("Không thể tạo tài khoản với vai trò " + role + " qua API này. Chỉ hỗ trợ FACILITY_MANAGER, INTERNAL_GUARD, OUTSOURCED_GUARD.");
        }

        if (userRepository.existsByUserCodeAndDeletedAtIsNull(request.getUserCode())) {
            throw new DuplicateResourceException("Mã cán bộ " + request.getUserCode() + " đã tồn tại trong hệ thống.");
        }

        if (userRepository.existsByEmailAndDeletedAtIsNull(request.getEmail())) {
            throw new DuplicateResourceException("Email " + request.getEmail() + " đã tồn tại trong hệ thống.");
        }

        String tempPassword = generateRandomPassword(10);
        log.info("Pass {}", tempPassword); // for dev purpose
        String encodedPassword = passwordEncoder.encode(tempPassword);

        // 1. Process Face Registration with AI-Service and upload to MinIO
        FaceDataResponseDto faceResponse = faceDataService.registerFace(request.getUserCode().trim(), request.getFaceImage());

        // 2. Save User and construct response with compensating action on DB failure
        try {
            User user = User.builder()
                    .fullName(request.getFullName().trim())
                    .userCode(request.getUserCode().trim())
                    .email(request.getEmail().trim())
                    .password(encodedPassword)
                    .role(role)
                    .isActive(true)
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
                .email(user.getEmail())
                .role(user.getRole() != null ? user.getRole().name() : null)
                .userCode(user.getUserCode())
                .build();
    }

    @Transactional(readOnly = true)
    public UserPageResponse getUsers(String keyword, String accountType, Boolean isActive, Pageable pageable) {
        String trimmedKeyword = (keyword != null && !keyword.trim().isEmpty()) ? keyword.trim() : null;
        Collection<Role> roles;

        if ("NORMAL".equalsIgnoreCase(accountType)) {
            roles = NORMAL_ROLES;
        } else if ("SYSTEM".equalsIgnoreCase(accountType)) {
            roles = SYSTEM_ROLES;
        } else {
            roles = List.of(Role.values());
        }

        Page<UserListResponse> userPage = userRepository.searchFilteredUsers(trimmedKeyword, roles, isActive, pageable)
                .map(UserListResponse::fromEntity);

        long normalCount = userRepository.countByRoleAndDeletedAtIsNull(Role.NORMAL_USER);
        long systemCount = userRepository.countByRolesAndDeletedAtIsNull(SYSTEM_ROLES);

        return UserPageResponse.builder()
                .users(userPage)
                .normalCount(normalCount)
                .systemCount(systemCount)
                .build();
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

    public String generateSampleCsv() {
        return "userCode,fullName,email,password\n" +
               "SE150001,Nguyen Van A,nva@example.com,Password123!\n" +
               "SE150002,Tran Thi B,ttb@example.com,Password123!\n";
    }

    private String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int rndCharAt = random.nextInt(DATA_FOR_RANDOM_STRING.length());
            sb.append(DATA_FOR_RANDOM_STRING.charAt(rndCharAt));
        }
        return sb.toString();
    }
}
