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
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.DuplicateResourceException;
import com.fa26se040.icss.exception.InvalidRoleAssignmentException;
import com.fa26se040.icss.exception.MaxRecordsExceededException;
import com.fa26se040.icss.exception.ResourceNotFoundException;
import com.fa26se040.icss.repository.UserRepository;
import com.fa26se040.icss.util.StringNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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

    private static final Set<Role> ALLOWED_CREATE_ROLES = EnumSet.of(
            Role.ADMIN,
            Role.FACILITY_MANAGER,
            Role.INTERNAL_GUARD,
            Role.OUTSOURCED_GUARD,
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

    @Transactional
    public StaffAccountCreateResponse createStaffAccount(StaffAccountCreateRequest request) {
        log.info("Creating account for userCode: {}, email: {}, role: {}", request.getUserCode(), request.getEmail(), request.getRole());

        // Validate role must belong to ALLOWED_CREATE_ROLES (ADMIN, FACILITY_MANAGER, INTERNAL_GUARD, OUTSOURCED_GUARD, NORMAL_USER)
        Role role = request.getRole();
        if (role == null || !ALLOWED_CREATE_ROLES.contains(role)) {
            throw new IllegalArgumentException("Vai trò không hợp lệ. Chỉ chấp nhận: ADMIN, FACILITY_MANAGER, INTERNAL_GUARD, OUTSOURCED_GUARD, NORMAL_USER");
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
            User user = User.builder()
                    .fullName(normFullName)
                    .userCode(normUserCode)
                    .email(normEmail)
                    .password(encodedPassword)
                    .role(role)
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
                    "      <c r=\"D2\" t=\"inlineStr\"><is><t>INTERNAL_GUARD</t></is></c>\n" +
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
                    "      <c r=\"D4\" t=\"inlineStr\"><is><t>OUTSOURCED_GUARD</t></is></c>\n" +
                    "    </row>\n" +
                    "    <row r=\"5\">\n" +
                    "      <c r=\"A5\" t=\"inlineStr\"><is><t>AD001</t></is></c>\n" +
                    "      <c r=\"B5\" t=\"inlineStr\"><is><t>Phạm Minh Đức</t></is></c>\n" +
                    "      <c r=\"C5\" t=\"inlineStr\"><is><t>pmd@fpt.edu.vn</t></is></c>\n" +
                    "      <c r=\"D5\" t=\"inlineStr\"><is><t>ADMIN</t></is></c>\n" +
                    "    </row>\n" +
                    "    <row r=\"7\">\n" +
                    "      <c r=\"A7\" t=\"inlineStr\"><is><t>CHÚ THÍCH: Cột role bắt buộc nhập chính xác 1 trong các giá trị: ADMIN, FACILITY_MANAGER, INTERNAL_GUARD, OUTSOURCED_GUARD. Không để trống. Không chấp nhận NORMAL_USER. Xóa các dòng mẫu trước khi nạp.</t></is></c>\n" +
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
        return processBulkImport(zipFile, false);
    }

    public BulkImportResponse bulkImportStaffUsers(MultipartFile zipFile) {
        return processBulkImport(zipFile, true);
    }

    private BulkImportResponse processBulkImport(MultipartFile zipFile, boolean isStaffImport) {
        if (zipFile == null || zipFile.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn file ZIP để nạp dữ liệu.");
        }
        String originalFilename = zipFile.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("Định dạng file không hợp lệ. Chỉ chấp nhận file nén .zip.");
        }

        File tempZip;
        try {
            tempZip = File.createTempFile("import-", ".zip");
        } catch (IOException e) {
            log.error("Lỗi khi tạo file ZIP tạm: {}", e.getMessage(), e);
            throw new RuntimeException("Không thể tạo file tạm để xử lý import", e);
        }

        try {
            try {
                zipFile.transferTo(tempZip);
            } catch (IOException e) {
                log.error("Lỗi khi ghi file ZIP tạm xuống đĩa: {}", e.getMessage(), e);
                throw new IllegalArgumentException("Không thể lưu file ZIP tải lên: " + e.getMessage());
            }

            try (ZipFile zf = new ZipFile(tempZip)) {
                // Lượt 1: Chỉ tìm và đọc metadata.csv để kiểm tra số dòng
                ZipEntry csvEntry = null;
                Enumeration<? extends ZipEntry> entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || entry.getName().startsWith("__MACOSX") || entry.getName().startsWith(".")) {
                        continue;
                    }
                    String entryName = entry.getName().replace('\\', '/');
                    String fileNameOnly = entryName.contains("/") ? entryName.substring(entryName.lastIndexOf('/') + 1) : entryName;
                    if (fileNameOnly.equalsIgnoreCase("metadata.csv")) {
                        csvEntry = entry;
                        break;
                    }
                }

                if (csvEntry == null) {
                    throw new IllegalArgumentException("Không tìm thấy file metadata.csv trong file ZIP.");
                }

                byte[] csvBytes;
                try (InputStream is = zf.getInputStream(csvEntry)) {
                    csvBytes = is.readAllBytes();
                } catch (IOException e) {
                    log.error("Lỗi khi đọc file metadata.csv từ ZIP: {}", e.getMessage(), e);
                    throw new IllegalArgumentException("Không thể đọc file metadata.csv trong file ZIP: " + e.getMessage());
                }

                String csvText = new String(csvBytes, StandardCharsets.UTF_8);
                if (csvText.startsWith("\uFEFF")) {
                    csvText = csvText.substring(1);
                }

                String[] lines = csvText.split("\\r?\\n");
                List<String> validLines = new ArrayList<>();
                for (String line : lines) {
                    if (!line.trim().isEmpty()) {
                        validLines.add(line);
                    }
                }

                if (validLines.isEmpty()) {
                    throw new IllegalArgumentException("File metadata.csv rỗng.");
                }

                int dataRowCount = validLines.size() - 1;
                if (dataRowCount > 200) {
                    throw new MaxRecordsExceededException("File metadata.csv chứa " + dataRowCount + " bản ghi, vượt quá số lượng tối đa 200 bản ghi cho phép.");
                }

                String headerLine = validLines.get(0);
                String[] headers = parseCsvLine(headerLine);
                int colUserCode = -1;
                int colFullName = -1;
                int colEmail = -1;
                int colRole = -1;

                for (int i = 0; i < headers.length; i++) {
                    String h = headers[i].trim().toLowerCase().replace("_", "");
                    if (h.equals("usercode") || h.equals("code") || h.equals("manguoidung") || h.equals("mscanbo") || h.equals("msnv")) {
                        colUserCode = i;
                    } else if (h.equals("fullname") || h.equals("name") || h.equals("hovaten")) {
                        colFullName = i;
                    } else if (h.equals("email")) {
                        colEmail = i;
                    } else if (h.equals("role") || h.equals("vaitro") || h.equals("quyen")) {
                        colRole = i;
                    }
                }

                // Bỏ hoàn toàn phần dự phòng index. Nhận diện cột theo header, thiếu cột -> lỗi.
                if (isStaffImport) {
                    if (colUserCode == -1 || colFullName == -1 || colEmail == -1 || colRole == -1) {
                        throw new IllegalArgumentException("File metadata thiếu cột bắt buộc. Luồng cán bộ cần đủ 4 cột: user_code, full_name, email, role.");
                    }
                } else {
                    if (colUserCode == -1 || colFullName == -1 || colEmail == -1) {
                        throw new IllegalArgumentException("File metadata thiếu cột bắt buộc. Luồng người dùng thường cần đủ 3 cột: user_code, full_name, email.");
                    }
                }

                // Lượt 2: Tới đây mới nạp ảnh vào imageMap
                Map<String, ZipImageEntry> imageMap = new HashMap<>();
                entries = zf.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || entry.getName().startsWith("__MACOSX") || entry.getName().startsWith(".")) {
                        continue;
                    }
                    String entryName = entry.getName().replace('\\', '/');
                    String fileNameOnly = entryName.contains("/") ? entryName.substring(entryName.lastIndexOf('/') + 1) : entryName;
                    if (fileNameOnly.toLowerCase().endsWith(".jpg") || fileNameOnly.toLowerCase().endsWith(".jpeg") || fileNameOnly.toLowerCase().endsWith(".png")) {
                        String baseName = fileNameOnly.contains(".") ? fileNameOnly.substring(0, fileNameOnly.lastIndexOf('.')) : fileNameOnly;
                        byte[] imgBytes;
                        try (InputStream is = zf.getInputStream(entry)) {
                            imgBytes = is.readAllBytes();
                        }
                        imageMap.put(baseName.toLowerCase(), new ZipImageEntry(fileNameOnly, imgBytes));
                    }
                }

                List<BulkImportRowResult> rowResults = new ArrayList<>();
                int successCount = 0;
                int failureCount = 0;

                // Lượt 1: duyệt toàn bộ dòng, chuẩn hoá, đếm số lần xuất hiện mỗi code và mỗi email vào 2 Map (KHÔNG chạm DB)
                Map<String, Integer> codeCountsInFile = new HashMap<>();
                Map<String, Integer> emailCountsInFile = new HashMap<>();

                for (int i = 1; i < validLines.size(); i++) {
                    String line = validLines.get(i);
                    String[] tokens = parseCsvLine(line);

                    String rawUserCode = (colUserCode < tokens.length) ? tokens[colUserCode] : "";
                    String rawEmail = (colEmail < tokens.length) ? tokens[colEmail] : "";

                    String userCode = StringNormalizer.normCode(rawUserCode);
                    String email = StringNormalizer.normEmail(rawEmail);

                    if (!userCode.isBlank()) {
                        codeCountsInFile.merge(userCode, 1, Integer::sum);
                    }
                    if (!email.isBlank()) {
                        emailCountsInFile.merge(email, 1, Integer::sum);
                    }
                }

                // Lượt 2: dòng nào có count > 1 thì FAIL ngay, không gọi processSingleRow
                UUID importBatchId = UUID.randomUUID();
                log.info("Bulk import batch {} started, file: {}", importBatchId, zipFile.getOriginalFilename());

                for (int i = 1; i < validLines.size(); i++) {
                    int rowIndex = i + 1; // 1-based index
                    String line = validLines.get(i);
                    String[] tokens = parseCsvLine(line);

                    String rawUserCode = (colUserCode < tokens.length) ? tokens[colUserCode] : "";
                    String rawFullName = (colFullName < tokens.length) ? tokens[colFullName] : "";
                    String rawEmail = (colEmail < tokens.length) ? tokens[colEmail] : "";

                    String userCode = StringNormalizer.normCode(rawUserCode);
                    String fullName = StringNormalizer.normName(rawFullName);
                    String email = StringNormalizer.normEmail(rawEmail);

                    // Kiểm tra trùng lặp trong nội bộ file (count > 1 thì CẢ HAI dòng đều FAIL)
                    List<String> inDuplicateErrors = new ArrayList<>();
                    if (!userCode.isBlank() && codeCountsInFile.getOrDefault(userCode, 0) > 1) {
                        inDuplicateErrors.add("Mã người dùng bị trùng lặp trong file import");
                    }
                    if (!email.isBlank() && emailCountsInFile.getOrDefault(email, 0) > 1) {
                        inDuplicateErrors.add("Email bị trùng lặp trong file import");
                    }

                    // Xử lý Role theo luồng
                    Role targetRole = null;
                    String roleForReporting = null;
                    if (isStaffImport) {
                        String rawRole = (colRole < tokens.length) ? tokens[colRole].trim() : "";
                        roleForReporting = rawRole.isBlank() ? null : rawRole;
                        if (rawRole.isBlank()) {
                            failureCount++;
                            rowResults.add(BulkImportRowResult.builder()
                                    .rowIndex(rowIndex)
                                    .userCode(userCode)
                                    .fullName(fullName)
                                    .email(email)
                                    .role(null)
                                    .status("FAILED")
                                    .errorMessage("Cột role không được để trống")
                                    .build());
                            continue;
                        }

                        try {
                            targetRole = Role.valueOf(rawRole.toUpperCase());
                        } catch (IllegalArgumentException ex) {
                            targetRole = null;
                        }

                        if (targetRole == null || !SYSTEM_ROLES.contains(targetRole)) {
                            failureCount++;
                            rowResults.add(BulkImportRowResult.builder()
                                    .rowIndex(rowIndex)
                                    .userCode(userCode)
                                    .fullName(fullName)
                                    .email(email)
                                    .role(rawRole)
                                    .status("FAILED")
                                    .errorMessage("Giá trị role không hợp lệ")
                                    .build());
                            continue;
                        }
                        roleForReporting = targetRole.name();
                    } else {
                        targetRole = Role.NORMAL_USER;
                        roleForReporting = Role.NORMAL_USER.name();
                    }

                    // Dòng nào có count > 1 thì FAIL ngay, không gọi processSingleRow
                    if (!inDuplicateErrors.isEmpty()) {
                        failureCount++;
                        rowResults.add(BulkImportRowResult.builder()
                                .rowIndex(rowIndex)
                                .userCode(userCode)
                                .fullName(fullName)
                                .email(email)
                                .role(roleForReporting)
                                .status("FAILED")
                                .errorMessage(String.join(". ", inDuplicateErrors))
                                .build());
                        continue;
                    }

                    ZipImageEntry imgEntry = imageMap.get(userCode.toLowerCase());
                    byte[] imgBytes = imgEntry != null ? imgEntry.bytes : null;
                    String imgFileName = imgEntry != null ? imgEntry.fileName : userCode + ".jpg";

                    // BR-06: 12-char random password
                    String tempPassword = generateRandomPassword(12);

                    BulkImportRowResult result = userBulkImportHelper.processSingleRow(
                            rowIndex, userCode, fullName, email, targetRole, importBatchId, imgBytes, imgFileName, tempPassword
                    );

                    if ("SUCCESS".equalsIgnoreCase(result.getStatus())) {
                        successCount++;
                    } else {
                        failureCount++;
                    }
                    rowResults.add(result);
                }

                log.info("Bulk import batch {} finished: total={}, success={}, failed={}",
                         importBatchId, dataRowCount, successCount, failureCount);

                return BulkImportResponse.builder()
                        .importBatchId(importBatchId)
                        .totalRows(dataRowCount)
                        .successCount(successCount)
                        .failureCount(failureCount)
                        .results(rowResults)
                        .build();
            } catch (IOException e) {
                log.error("Lỗi khi đọc file ZIP: {}", e.getMessage(), e);
                throw new IllegalArgumentException("Không thể đọc file ZIP: " + e.getMessage());
            }
        } finally {
            if (tempZip.exists()) {
                boolean deleted = tempZip.delete();
                if (!deleted) {
                    log.warn("Không thể xoá file ZIP tạm: {}", tempZip.getAbsolutePath());
                }
            }
        }
    }

    private String[] parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                values.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        values.add(sb.toString().trim());
        return values.toArray(new String[0]);
    }

    private String generateRandomPassword(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(DATA_FOR_RANDOM_STRING.charAt(random.nextInt(DATA_FOR_RANDOM_STRING.length())));
        }
        return sb.toString();
    }

    private static class ZipImageEntry {
        final String fileName;
        final byte[] bytes;
        ZipImageEntry(String fileName, byte[] bytes) {
            this.fileName = fileName;
            this.bytes = bytes;
        }
    }
}
