package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.BulkImportResponse;
import com.fa26se040.icss.dto.BulkImportRowResult;
import com.fa26se040.icss.dto.FaceDataResponseDto;
import com.fa26se040.icss.dto.UserInfo;
import com.fa26se040.icss.dto.user.StaffAccountCreateRequest;
import com.fa26se040.icss.dto.user.StaffAccountCreateResponse;
import com.fa26se040.icss.dto.user.UserListResponse;
import com.fa26se040.icss.dto.user.UserPageResponse;
import com.fa26se040.icss.dto.user.ImportBatchSummaryResponse;
import com.fa26se040.icss.dto.user.BatchUserResponse;
import com.fa26se040.icss.dto.user.BatchDeleteResponse;
import com.fa26se040.icss.dto.user.BatchRestoreResponse;
import com.fa26se040.icss.dto.user.BatchRestoreSkippedUser;
import com.fa26se040.icss.dto.user.UserSearchResponse;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.DuplicateResourceException;
import com.fa26se040.icss.exception.MaxRecordsExceededException;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.exception.UnauthorizedException;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.util.StringNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;


@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    public static final List<Role> SYSTEM_ROLES = List.of(
            Role.ADMIN,
            Role.FACILITY_MANAGER,
            Role.GUARD
    );

    public static final List<Role> NORMAL_ROLES = List.of(
            Role.NORMAL_USER
    );

    private static final Set<Role> ALLOWED_CREATE_ROLES = EnumSet.of(
            Role.ADMIN,
            Role.FACILITY_MANAGER,
            Role.GUARD,
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
    private final UserBulkImportHelper userBulkImportHelper;
    private final NotificationService notificationService;
    private final UserAccessLevelHelper userAccessLevelHelper;
    private final AccessControlAuditService auditService;
    private final UserBulkImportService userBulkImportService;

    @Transactional
    public StaffAccountCreateResponse createStaffAccount(StaffAccountCreateRequest request) {
        log.info("Creating account for userCode: {}, email: {}, role: {}", request.getUserCode(), request.getEmail(), request.getRole());

        // Validate role must belong to ALLOWED_CREATE_ROLES (ADMIN, FACILITY_MANAGER, GUARD, NORMAL_USER)
        Role role = request.getRole();
        if (role == null || !ALLOWED_CREATE_ROLES.contains(role)) {
            throw new IllegalArgumentException("Vai trò không hợp lệ. Chỉ chấp nhận: ADMIN, FACILITY_MANAGER, GUARD, NORMAL_USER");
        }

        // Apply shared normalization
        String normUserCode = StringNormalizer.normCode(request.getUserCode());
        String normEmail = StringNormalizer.normEmail(request.getEmail());
        String normFullName = StringNormalizer.normName(request.getFullName());

        // Duplicate checks with UPPER(userCode) and LOWER(email)
        Set<String> existingCodes = userRepository.findExistingUserCodes(Set.of(normUserCode));
        Set<String> existingEmails = userRepository.findExistingEmails(Set.of(normEmail));

        List<String> duplicateReasons = new ArrayList<>();
        if (!existingCodes.isEmpty()) {
            duplicateReasons.add("Mã người dùng đã tồn tại trong hệ thống");
        }
        if (!existingEmails.isEmpty()) {
            duplicateReasons.add("Email đã tồn tại trong hệ thống");
        }
        if (!duplicateReasons.isEmpty()) {
            throw new DuplicateResourceException(String.join(". ", duplicateReasons));
        }

        // BR-06: Generate 12-char temporary password
        String tempPassword = generateRandomPassword(12);
        String encodedPassword = passwordEncoder.encode(tempPassword);

        // 1. Process Face Registration with AI-Service & MinIO (VAL-05, BR-04, BR-08)
        FaceDataResponseDto faceResponse = faceDataService.registerFace(normUserCode, request.getFaceImage());

        // 2. Begin DB Transaction for User, FaceData
        try {
            int defaultAccessLevel = resolveDefaultAccessLevel(role);
            User user = User.builder()
                    .fullName(normFullName)
                    .userCode(normUserCode)
                    .email(normEmail)
                    .password(encodedPassword)
                    .role(role)
                    .accessLevel(defaultAccessLevel)
                    .isActive(true) // BR-07: is_active = true by default
                    .createdAt(OffsetDateTime.now())
                    .updatedAt(OffsetDateTime.now())
                    .build();

            User savedUser = userRepository.save(user);

            // 6b. Gửi email thông tin tài khoản và mật khẩu khởi tạo
            try {
                notificationService.sendStaffAccountSetupEmail(
                        savedUser.getEmail(),
                        savedUser.getFullName(),
                        savedUser.getUserCode(),
                        tempPassword
                );
            } catch (Exception mailEx) {
                log.warn("Gửi email khởi tạo tài khoản cho [{}] thất bại nhưng giữ tài khoản: {}", normUserCode, mailEx.getMessage());
            }

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
            log.error("DB Transaction failed for userCode {}. Deleting uploaded image from MinIO.", normUserCode, e);
            minioStorageService.deleteFaceImage(normUserCode);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public UserInfo getUserByCode(String code) {
        User user = userRepository.findByUserCodeAndDeletedAtIsNull(code)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng với mã: " + code));

        return UserInfo.builder()
                .id(user.getId())
                .fullName(user.getFullName())
                .userCode(user.getUserCode())
                .email(user.getEmail())
                .role(user.getRole() != null ? user.getRole().name() : null)
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

        Page<User> userPage;
        if (roleFilter != null && !roleFilter.isEmpty()) {
            userPage = userRepository.searchFilteredUsers(kw, roleFilter, isActive, pageable);
        } else {
            userPage = userRepository.searchUsers(kw, pageable);
        }

        Page<UserListResponse> dtoPage = userPage.map(UserListResponse::fromEntity);

        long normalCount = userRepository.countByRolesAndDeletedAtIsNull(NORMAL_ROLES);
        long systemCount = userRepository.countByRolesAndDeletedAtIsNull(SYSTEM_ROLES);

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
        return UserListResponse.fromEntity(updatedUser);
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

    @Transactional(readOnly = true)
    public Page<ImportBatchSummaryResponse> getImportBatches(Pageable pageable) {
        return userRepository.findImportBatchSummaries(pageable);
    }

    @Transactional(readOnly = true)
    public List<BatchUserResponse> getBatchDetails(UUID batchId) {
        if (!userRepository.existsByImportBatchId(batchId)) {
            throw new ResourceNotFoundException("Không tìm thấy lô import với mã: " + batchId);
        }
        return userRepository.findByImportBatchIdOrderByUserCodeAsc(batchId)
                .stream()
                .map(BatchUserResponse::fromEntity)
                .toList();
    }

    @Transactional
    public BatchDeleteResponse deleteBatch(UUID batchId) {
        if (!userRepository.existsByImportBatchId(batchId)) {
            throw new ResourceNotFoundException("Không tìm thấy lô import với mã: " + batchId);
        }
        int deletedCount = userRepository.softDeleteByImportBatchId(batchId);
        log.info("Soft-deleted import batch {}: {} users affected", batchId, deletedCount);
        return new BatchDeleteResponse(batchId, deletedCount, "Đã gỡ " + deletedCount + " tài khoản trong lô.");
    }

    @Transactional
    public BatchRestoreResponse restoreBatch(UUID batchId) {
        if (!userRepository.existsByImportBatchId(batchId)) {
            throw new ResourceNotFoundException("Không tìm thấy lô import với mã: " + batchId);
        }

        List<User> deletedUsers = userRepository.findByImportBatchIdAndDeletedAtIsNotNullOrderByUserCodeAsc(batchId);
        if (deletedUsers.isEmpty()) {
            return new BatchRestoreResponse(batchId, 0, 0, List.of());
        }

        Set<String> restoredCodes = new HashSet<>();
        Set<String> restoredEmails = new HashSet<>();
        List<BatchRestoreSkippedUser> skippedUsers = new ArrayList<>();
        int restoredCount = 0;

        for (User u : deletedUsers) {
            String normCode = StringNormalizer.normCode(u.getUserCode());
            String normEmail = StringNormalizer.normEmail(u.getEmail());

            boolean codeConflict = restoredCodes.contains(normCode) ||
                    userRepository.existsActiveByUserCodeUpperAndIdNot(normCode, u.getId());
            boolean emailConflict = restoredEmails.contains(normEmail) ||
                    userRepository.existsActiveByEmailLowerAndIdNot(normEmail, u.getId());

            if (codeConflict && emailConflict) {
                skippedUsers.add(new BatchRestoreSkippedUser(
                        u.getUserCode(),
                        u.getEmail(),
                        "Mã tài khoản và Email đã được sử dụng bởi tài khoản khác đang hoạt động."
                ));
                continue;
            }

            if (codeConflict) {
                skippedUsers.add(new BatchRestoreSkippedUser(
                        u.getUserCode(),
                        u.getEmail(),
                        "Mã tài khoản '" + u.getUserCode() + "' đã được sử dụng bởi một tài khoản khác đang hoạt động."
                ));
                continue;
            }

            if (emailConflict) {
                skippedUsers.add(new BatchRestoreSkippedUser(
                        u.getUserCode(),
                        u.getEmail(),
                        "Email '" + u.getEmail() + "' đã được sử dụng bởi một tài khoản khác đang hoạt động."
                ));
                continue;
            }

            u.setDeletedAt(null);
            u.setIsActive(true);
            u.setUpdatedAt(OffsetDateTime.now());
            userRepository.save(u);

            restoredCodes.add(normCode);
            restoredEmails.add(normEmail);
            restoredCount++;
        }

        log.info("Restored import batch {}: {} restored, {} skipped", batchId, restoredCount, skippedUsers.size());
        return new BatchRestoreResponse(batchId, restoredCount, skippedUsers.size(), skippedUsers);
    }

    public String generateSampleCsv() {
        return "user_code,full_name,email\n" +
                "SV001,Nguyễn Văn A,nva@example.com\n" +
                "SV002,Trần Thị B,ttb@example.com\n";
    }

    public byte[] generateSampleExcel() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addZipEntry(zos, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
                    "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
                    "  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n" +
                    "  <Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>\n" +
                    "  <Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n" +
                    "</Types>");

            addZipEntry(zos, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                    "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>\n" +
                    "</Relationships>");

            addZipEntry(zos, "xl/_rels/workbook.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                    "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>\n" +
                    "</Relationships>");

            addZipEntry(zos, "xl/workbook.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n" +
                    "  <sheets>\n" +
                    "    <sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/>\n" +
                    "  </sheets>\n" +
                    "</workbook>");

            addZipEntry(zos, "xl/worksheets/sheet1.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
                    "  <sheetData>\n" +
                    "    <row r=\"1\">\n" +
                    "      <c r=\"A1\" t=\"inlineStr\"><is><t>user_code</t></is></c>\n" +
                    "      <c r=\"B1\" t=\"inlineStr\"><is><t>full_name</t></is></c>\n" +
                    "      <c r=\"C1\" t=\"inlineStr\"><is><t>email</t></is></c>\n" +
                    "    </row>\n" +
                    "    <row r=\"2\">\n" +
                    "      <c r=\"A2\" t=\"inlineStr\"><is><t>SV001</t></is></c>\n" +
                    "      <c r=\"B2\" t=\"inlineStr\"><is><t>Nguyễn Văn A</t></is></c>\n" +
                    "      <c r=\"C2\" t=\"inlineStr\"><is><t>nva@example.com</t></is></c>\n" +
                    "    </row>\n" +
                    "    <row r=\"3\">\n" +
                    "      <c r=\"A3\" t=\"inlineStr\"><is><t>SV002</t></is></c>\n" +
                    "      <c r=\"B3\" t=\"inlineStr\"><is><t>Trần Thị B</t></is></c>\n" +
                    "      <c r=\"C3\" t=\"inlineStr\"><is><t>ttb@example.com</t></is></c>\n" +
                    "    </row>\n" +
                    "  </sheetData>\n" +
                    "</worksheet>");
        } catch (IOException e) {
            log.error("Lỗi khi tạo file Excel mẫu: {}", e.getMessage(), e);
            throw new RuntimeException("Không thể tạo file Excel mẫu", e);
        }
        return baos.toByteArray();
    }

    public byte[] generateSampleStaffExcel() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            addZipEntry(zos, "[Content_Types].xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">\n" +
                    "  <Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>\n" +
                    "  <Default Extension=\"xml\" ContentType=\"application/xml\"/>\n" +
                    "  <Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>\n" +
                    "  <Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n" +
                    "</Types>");

            addZipEntry(zos, "_rels/.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                    "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>\n" +
                    "</Relationships>");

            addZipEntry(zos, "xl/_rels/workbook.xml.rels",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">\n" +
                    "  <Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>\n" +
                    "</Relationships>");

            addZipEntry(zos, "xl/workbook.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">\n" +
                    "  <sheets>\n" +
                    "    <sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/>\n" +
                    "  </sheets>\n" +
                    "</workbook>");

            addZipEntry(zos, "xl/worksheets/sheet1.xml",
                    "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                    "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">\n" +
                    "  <sheetData>\n" +
                    "    <row r=\"1\">\n" +
                    "      <c r=\"A1\" t=\"inlineStr\"><is><t>user_code</t></is></c>\n" +
                    "      <c r=\"B1\" t=\"inlineStr\"><is><t>full_name</t></is></c>\n" +
                    "      <c r=\"C1\" t=\"inlineStr\"><is><t>email</t></is></c>\n" +
                    "      <c r=\"D1\" t=\"inlineStr\"><is><t>role</t></is></c>\n" +
                    "    </row>\n" +
                    "    <row r=\"2\">\n" +
                    "      <c r=\"A2\" t=\"inlineStr\"><is><t>NV001</t></is></c>\n" +
                    "      <c r=\"B2\" t=\"inlineStr\"><is><t>Nguyễn Văn An</t></is></c>\n" +
                    "      <c r=\"C2\" t=\"inlineStr\"><is><t>nva@fpt.edu.vn</t></is></c>\n" +
                    "      <c r=\"D2\" t=\"inlineStr\"><is><t>GUARD</t></is></c>\n" +
                    "    </row>\n" +
                    "    <row r=\"3\">\n" +
                    "      <c r=\"A3\" t=\"inlineStr\"><is><t>FM001</t></is></c>\n" +
                    "      <c r=\"B3\" t=\"inlineStr\"><is><t>Trần Thị Bình</t></is></c>\n" +
                    "      <c r=\"C3\" t=\"inlineStr\"><is><t>ttb@fpt.edu.vn</t></is></c>\n" +
                    "      <c r=\"D3\" t=\"inlineStr\"><is><t>FACILITY_MANAGER</t></is></c>\n" +
                    "    </row>\n" +
                    "    <row r=\"4\">\n" +
                    "      <c r=\"A4\" t=\"inlineStr\"><is><t>OG001</t></is></c>\n" +
                    "      <c r=\"B4\" t=\"inlineStr\"><is><t>Lê Hoàng Cường</t></is></c>\n" +
                    "      <c r=\"C4\" t=\"inlineStr\"><is><t>lhc@fpt.edu.vn</t></is></c>\n" +
                    "      <c r=\"D4\" t=\"inlineStr\"><is><t>GUARD</t></is></c>\n" +
                    "    </row>\n" +
                    "    <row r=\"5\">\n" +
                    "      <c r=\"A5\" t=\"inlineStr\"><is><t>AD001</t></is></c>\n" +
                    "      <c r=\"B5\" t=\"inlineStr\"><is><t>Phạm Minh Đức</t></is></c>\n" +
                    "      <c r=\"C5\" t=\"inlineStr\"><is><t>pmd@fpt.edu.vn</t></is></c>\n" +
                    "      <c r=\"D5\" t=\"inlineStr\"><is><t>ADMIN</t></is></c>\n" +
                    "    </row>\n" +
                    "    <row r=\"7\">\n" +
                    "      <c r=\"A7\" t=\"inlineStr\"><is><t>CHÚ THÍCH: Cột role bắt buộc nhập chính xác 1 trong các giá trị: ADMIN, FACILITY_MANAGER, GUARD. Không để trống. Không chấp nhận NORMAL_USER. Xóa các dòng mẫu trước khi nạp.</t></is></c>\n" +
                    "    </row>\n" +
                    "  </sheetData>\n" +
                    "</worksheet>");
        } catch (IOException e) {
            log.error("Lỗi khi tạo file Excel mẫu cán bộ: {}", e.getMessage(), e);
            throw new RuntimeException("Không thể tạo file Excel mẫu cán bộ", e);
        }
        return baos.toByteArray();
    }

    private void addZipEntry(ZipOutputStream zos, String path, String content) throws IOException {
        ZipEntry entry = new ZipEntry(path);
        zos.putNextEntry(entry);
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    public BulkImportResponse bulkImportNormalUsers(MultipartFile zipFile) {
        return userBulkImportService.bulkImportNormalUsers(zipFile);
    }

    public BulkImportResponse bulkImportStaffUsers(MultipartFile zipFile) {
        return userBulkImportService.bulkImportStaffUsers(zipFile);
    }

    private String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(DATA_FOR_RANDOM_STRING.charAt(random.nextInt(DATA_FOR_RANDOM_STRING.length())));
        }
        return sb.toString();
    }

    @Transactional(readOnly = true)
    public Page<UserSearchResponse> searchUsers(String q, Pageable pageable) {
        if (q == null || q.trim().length() < 2) {
            throw new IllegalArgumentException("Từ khoá tìm kiếm phải có tối thiểu 2 ký tự");
        }
        String cleanQ = q.trim();
        int cappedSize = Math.min(Math.max(1, pageable.getPageSize()), 20);
        Pageable cappedPageable = PageRequest.of(pageable.getPageNumber(), cappedSize, pageable.getSort());
        Page<User> page = userRepository.searchActiveUsers(cleanQ, cappedPageable);
        return page.map(u -> new UserSearchResponse(
                u.getId(),
                u.getUserCode(),
                u.getFullName(),
                u.getRole(),
                u.getAccessLevel()
        ));
    }

    public int resolveDefaultAccessLevel(Role role) {
        return userAccessLevelHelper.resolveDefaultAccessLevel(role);
    }

    @Transactional
    public UserSearchResponse updateAccessLevel(UUID id, Integer accessLevel, String actorEmail) {
        return updateAccessLevel(id, accessLevel, "Cập nhật cấp độ truy cập", actorEmail);
    }

    @Transactional
    public UserSearchResponse updateAccessLevel(UUID id, Integer accessLevel, String reason, String actorEmail) {
        User actor = null;
        if (actorEmail != null) {
            actor = userRepository.findByEmail(actorEmail)
                    .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));
            if (actor.getId().equals(id)) {
                throw new AccessDeniedException("Bạn không thể tự thay đổi cấp truy cập của chính mình");
            }
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Không tìm thấy người dùng"));
        if (user.getDeletedAt() != null) {
            throw new ResourceNotFoundException("Không tìm thấy người dùng");
        }

        // BR-AL-06: Thao tác không làm thay đổi giá trị (new == old) -> không ghi log, trả về trạng thái hiện tại
        if (Objects.equals(user.getAccessLevel(), accessLevel)) {
            log.info("User {} accessLevel unchanged ({}), skipping audit log", id, accessLevel);
            return new UserSearchResponse(
                    user.getId(),
                    user.getUserCode(),
                    user.getFullName(),
                    user.getRole(),
                    user.getAccessLevel()
            );
        }

        Integer oldLevel = user.getAccessLevel();
        user.setAccessLevel(accessLevel);
        user.setUpdatedAt(OffsetDateTime.now());
        User saved = userRepository.save(user);

        if (actor != null) {
            com.fa26se040.icss.dto.accesscontrol.snapshot.UserAccessLevelAuditSnapshot oldSnapshot =
                    new com.fa26se040.icss.dto.accesscontrol.snapshot.UserAccessLevelAuditSnapshot(oldLevel);
            com.fa26se040.icss.dto.accesscontrol.snapshot.UserAccessLevelAuditSnapshot newSnapshot =
                    new com.fa26se040.icss.dto.accesscontrol.snapshot.UserAccessLevelAuditSnapshot(saved.getAccessLevel());

            auditService.record(
                    com.fa26se040.icss.enums.AccessControlTargetType.USER_ACCESS_LEVEL,
                    com.fa26se040.icss.enums.AccessControlAction.UPDATE,
                    saved.getId().toString(),
                    null,
                    saved,
                    oldSnapshot,
                    newSnapshot,
                    reason,
                    actor
            );
        }

        return new UserSearchResponse(
                saved.getId(),
                saved.getUserCode(),
                saved.getFullName(),
                saved.getRole(),
                saved.getAccessLevel()
        );
    }
}
