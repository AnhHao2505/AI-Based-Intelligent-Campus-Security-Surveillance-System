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

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

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
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");

            // Định dạng TEXT cho cột user_code
            DataFormat dataFormat = workbook.createDataFormat();
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(dataFormat.getFormat("@"));

            // Hàng tiêu đề
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("user_code");
            headerRow.createCell(1).setCellValue("full_name");
            headerRow.createCell(2).setCellValue("email");

            // Dòng dữ liệu mẫu
            String[][] samples = {
                    {"SV001", "Nguyễn Văn A", "nva@example.com"},
                    {"SV002", "Trần Thị B", "ttb@example.com"}
            };

            for (int i = 0; i < samples.length; i++) {
                Row row = sheet.createRow(i + 1);
                Cell cell0 = row.createCell(0);
                cell0.setCellStyle(textStyle);
                cell0.setCellValue(samples[i][0]);

                row.createCell(1).setCellValue(samples[i][1]);
                row.createCell(2).setCellValue(samples[i][2]);
            }

            sheet.setDefaultColumnStyle(0, textStyle);
            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            sheet.autoSizeColumn(2);

            workbook.write(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Lỗi khi tạo file Excel mẫu: {}", e.getMessage(), e);
            throw new RuntimeException("Không thể tạo file Excel mẫu", e);
        }
    }

    public byte[] generateSampleStaffExcel() {
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");

            // Định dạng TEXT cho cột user_code
            DataFormat dataFormat = workbook.createDataFormat();
            CellStyle textStyle = workbook.createCellStyle();
            textStyle.setDataFormat(dataFormat.getFormat("@"));

            // Hàng tiêu đề
            Row headerRow = sheet.createRow(0);
            headerRow.createCell(0).setCellValue("user_code");
            headerRow.createCell(1).setCellValue("full_name");
            headerRow.createCell(2).setCellValue("email");
            headerRow.createCell(3).setCellValue("role");

            // Dòng dữ liệu mẫu
            String[][] samples = {
                    {"NV001", "Nguyễn Văn An", "nva@fpt.edu.vn", "INTERNAL_GUARD"},
                    {"FM001", "Trần Thị Bình", "ttb@fpt.edu.vn", "FACILITY_MANAGER"},
                    {"OG001", "Lê Hoàng Cường", "lhc@fpt.edu.vn", "OUTSOURCED_GUARD"},
                    {"AD001", "Phạm Minh Đức", "pmd@fpt.edu.vn", "ADMIN"}
            };

            for (int i = 0; i < samples.length; i++) {
                Row row = sheet.createRow(i + 1);
                Cell cell0 = row.createCell(0);
                cell0.setCellStyle(textStyle);
                cell0.setCellValue(samples[i][0]);

                row.createCell(1).setCellValue(samples[i][1]);
                row.createCell(2).setCellValue(samples[i][2]);
                row.createCell(3).setCellValue(samples[i][3]);
            }

            // Dòng ghi chú liệt kê 4 giá trị hợp lệ
            Row noteRow = sheet.createRow(6);
            noteRow.createCell(0).setCellValue("CHÚ THÍCH: Cột role bắt buộc nhập chính xác 1 trong các giá trị: ADMIN, FACILITY_MANAGER, INTERNAL_GUARD, OUTSOURCED_GUARD. Không để trống. Không chấp nhận NORMAL_USER. Xóa các dòng mẫu trước khi nạp.");

            sheet.setDefaultColumnStyle(0, textStyle);
            sheet.autoSizeColumn(0);
            sheet.autoSizeColumn(1);
            sheet.autoSizeColumn(2);
            sheet.autoSizeColumn(3);

            workbook.write(baos);
            return baos.toByteArray();
        } catch (IOException e) {
            log.error("Lỗi khi tạo file Excel mẫu cán bộ: {}", e.getMessage(), e);
            throw new RuntimeException("Không thể tạo file Excel mẫu cán bộ", e);
        }
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
                // Lượt 1: Chỉ tìm và đọc metadata.xlsx để kiểm tra số dòng
                List<String[]> dataRows = docBangDuLieu(zf);

                if (dataRows.isEmpty()) {
                    throw new IllegalArgumentException("File metadata.xlsx rỗng.");
                }

                int dataRowCount = dataRows.size() - 1;
                if (dataRowCount > 200) {
                    throw new MaxRecordsExceededException("File metadata.xlsx chứa " + dataRowCount + " bản ghi, vượt quá số lượng tối đa 200 bản ghi cho phép.");
                }

                String[] headers = dataRows.get(0);
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
                Enumeration<? extends ZipEntry> entries = zf.entries();
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

                for (int i = 1; i < dataRows.size(); i++) {
                    String[] tokens = dataRows.get(i);

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

                for (int i = 1; i < dataRows.size(); i++) {
                    int rowIndex = i + 1; // 1-based index
                    String[] tokens = dataRows.get(i);

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

    private List<String[]> docBangDuLieu(ZipFile zf) {
        ZipEntry xlsxEntry = null;
        Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || entry.getName().startsWith("__MACOSX") || entry.getName().startsWith(".")) {
                continue;
            }
            String entryName = entry.getName().replace('\\', '/');
            String fileNameOnly = entryName.contains("/") ? entryName.substring(entryName.lastIndexOf('/') + 1) : entryName;
            if (fileNameOnly.equalsIgnoreCase("metadata.xlsx")) {
                xlsxEntry = entry;
                break;
            }
        }

        if (xlsxEntry == null) {
            throw new IllegalArgumentException("Không tìm thấy file metadata.xlsx trong file ZIP.");
        }

        List<String[]> result = new ArrayList<>();
        try (InputStream is = zf.getInputStream(xlsxEntry);
             Workbook workbook = new XSSFWorkbook(is)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IllegalArgumentException("File metadata.xlsx không chứa sheet dữ liệu nào.");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            int firstRowNum = sheet.getFirstRowNum();
            int lastRowNum = sheet.getLastRowNum();
            if (firstRowNum < 0 || lastRowNum < firstRowNum) {
                return result;
            }

            // Tìm hàng đầu tiên có dữ liệu làm header
            int headerRowIdx = -1;
            int maxCols = 0;
            for (int r = firstRowNum; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;
                boolean hasData = false;
                short lastCell = row.getLastCellNum();
                if (lastCell > 0) {
                    for (int c = 0; c < lastCell; c++) {
                        String val = getCellValueAsString(row.getCell(c), formatter, evaluator);
                        if (!val.isEmpty()) {
                            hasData = true;
                            break;
                        }
                    }
                }
                if (hasData) {
                    headerRowIdx = r;
                    maxCols = (int) lastCell;
                    break;
                }
            }

            if (headerRowIdx == -1) {
                return result;
            }

            // Xác định maxCols của toàn bảng để các dòng luôn đủ ô
            for (int r = headerRowIdx; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                if (row != null && row.getLastCellNum() > maxCols) {
                    maxCols = (int) row.getLastCellNum();
                }
            }

            for (int r = headerRowIdx; r <= lastRowNum; r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String[] rowData = new String[maxCols];
                boolean allEmpty = true;
                for (int c = 0; c < maxCols; c++) {
                    Cell cell = row.getCell(c);
                    String val = getCellValueAsString(cell, formatter, evaluator);
                    rowData[c] = val;
                    if (!val.isEmpty()) {
                        allEmpty = false;
                    }
                }
                if (!allEmpty) {
                    result.add(rowData);
                }
            }
        } catch (IOException e) {
            log.error("Lỗi khi mở/đọc file metadata.xlsx từ ZIP: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Không thể đọc file metadata.xlsx trong file ZIP: " + e.getMessage());
        }

        return result;
    }

    private String getCellValueAsString(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        CellType cellType = cell.getCellType();
        if (cellType == CellType.BLANK) {
            return "";
        }
        if (cellType == CellType.FORMULA) {
            try {
                CellValue cellValue = evaluator.evaluate(cell);
                if (cellValue == null) {
                    return "";
                }
                switch (cellValue.getCellType()) {
                    case STRING:
                        return cellValue.getStringValue() != null ? cellValue.getStringValue().trim() : "";
                    case NUMERIC:
                        double numVal = cellValue.getNumberValue();
                        if (numVal == (long) numVal) {
                            return String.valueOf((long) numVal);
                        }
                        String strVal = String.valueOf(numVal);
                        if (strVal.endsWith(".0")) {
                            strVal = strVal.substring(0, strVal.length() - 2);
                        }
                        return strVal.trim();
                    case BOOLEAN:
                        return String.valueOf(cellValue.getBooleanValue());
                    default:
                        return "";
                }
            } catch (Exception e) {
                String formatted = formatter.formatCellValue(cell);
                return cleanNumberString(formatted);
            }
        }

        if (cellType == CellType.STRING) {
            return cell.getStringCellValue() != null ? cell.getStringCellValue().trim() : "";
        }

        if (cellType == CellType.NUMERIC) {
            String formatted = formatter.formatCellValue(cell);
            return cleanNumberString(formatted);
        }

        String formatted = formatter.formatCellValue(cell);
        return cleanNumberString(formatted);
    }

    private String cleanNumberString(String str) {
        if (str == null) {
            return "";
        }
        str = str.trim();
        if (str.endsWith(".0")) {
            str = str.substring(0, str.length() - 2);
        }
        return str;
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
