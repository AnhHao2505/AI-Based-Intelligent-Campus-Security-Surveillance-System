package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.BulkImportResponse;
import com.fa26se040.icss.dto.BulkImportRowResult;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.exception.MaxRecordsExceededException;
import com.fa26se040.icss.util.StringNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserBulkImportService {

    public static final List<Role> SYSTEM_ROLES = List.of(
            Role.ADMIN,
            Role.FACILITY_MANAGER,
            Role.GUARD
    );

    private static final String DATA_FOR_RANDOM_STRING = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()-_=+";
    private final SecureRandom random = new SecureRandom();

    private final UserBulkImportHelper userBulkImportHelper;

    private static class ParsedMetadata {
        final List<String[]> rows;
        final String sourceFileName;

        ParsedMetadata(List<String[]> rows, String sourceFileName) {
            this.rows = rows;
            this.sourceFileName = sourceFileName;
        }
    }

    private static class ZipImageEntry {
        final String fileName;
        final byte[] bytes;

        ZipImageEntry(String fileName, byte[] bytes) {
            this.fileName = fileName;
            this.bytes = bytes;
        }
    }

    /**
     * LUỒNG NẠP RIÊNG CHO NGƯỜI DÙNG THƯỜNG:
     * - Chỉ chấp nhận nạp tài khoản người dùng thường (Role.NORMAL_USER).
     * - Cần 3 cột: user_code, full_name, email.
     * - Nếu file có cột role và giá trị khác NORMAL_USER (ví dụ GUARD, ADMIN, FACILITY_MANAGER) -> Từ chối dòng đó.
     * - Hỗ trợ cả file Excel (.xlsx / .xls) và file CSV (.csv) trong file ZIP.
     */
    public BulkImportResponse bulkImportNormalUsers(MultipartFile zipFile) {
        validateZipFile(zipFile);
        File tempZip = createTempZipFile(zipFile);

        try {
            try (ZipFile zf = new ZipFile(tempZip)) {
                ParsedMetadata metadata = extractMetadata(zf);
                List<String[]> rows = metadata.rows;
                int dataRowCount = rows.size() - 1;
                if (dataRowCount <= 0) {
                    throw new IllegalArgumentException("File dữ liệu không có bản ghi người dùng nào.");
                }
                if (dataRowCount > 200) {
                    throw new MaxRecordsExceededException("File dữ liệu chứa " + dataRowCount + " bản ghi, vượt quá số lượng tối đa 200 bản ghi cho phép.");
                }

                String[] headers = rows.get(0);
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

                if (colUserCode == -1 || colFullName == -1 || colEmail == -1) {
                    throw new IllegalArgumentException("File metadata thiếu cột bắt buộc. Luồng người dùng thường cần đủ 3 cột: user_code, full_name, email.");
                }

                Map<String, ZipImageEntry> imageMap = extractImagesFromZip(zf);

                // Đếm trùng lặp trong nội bộ file
                Map<String, Integer> codeCountsInFile = new HashMap<>();
                Map<String, Integer> emailCountsInFile = new HashMap<>();
                for (int i = 1; i < rows.size(); i++) {
                    String[] tokens = rows.get(i);
                    String rawCode = (colUserCode < tokens.length) ? tokens[colUserCode] : "";
                    String rawEmail = (colEmail < tokens.length) ? tokens[colEmail] : "";
                    String userCode = StringNormalizer.normCode(rawCode);
                    String email = StringNormalizer.normEmail(rawEmail);
                    if (!userCode.isBlank()) codeCountsInFile.merge(userCode, 1, Integer::sum);
                    if (!email.isBlank()) emailCountsInFile.merge(email, 1, Integer::sum);
                }

                UUID importBatchId = UUID.randomUUID();
                log.info("Bulk import normal users batch {} started from {}, file: {}",
                        importBatchId, metadata.sourceFileName, zipFile.getOriginalFilename());

                List<BulkImportRowResult> rowResults = new ArrayList<>();
                int successCount = 0;
                int failureCount = 0;

                for (int i = 1; i < rows.size(); i++) {
                    int rowIndex = i + 1;
                    String[] tokens = rows.get(i);
                    String rawUserCode = (colUserCode < tokens.length) ? tokens[colUserCode] : "";
                    String rawFullName = (colFullName < tokens.length) ? tokens[colFullName] : "";
                    String rawEmail = (colEmail < tokens.length) ? tokens[colEmail] : "";
                    String rawRole = (colRole != -1 && colRole < tokens.length) ? tokens[colRole].trim() : "";

                    String userCode = StringNormalizer.normCode(rawUserCode);
                    String fullName = StringNormalizer.normName(rawFullName);
                    String email = StringNormalizer.normEmail(rawEmail);

                    // Kiểm tra vai trò: Luồng người dùng thường từ chối vai trò cán bộ
                    if (!rawRole.isBlank()) {
                        try {
                            Role parsedRole = Role.valueOf(rawRole.toUpperCase());
                            if (SYSTEM_ROLES.contains(parsedRole)) {
                                failureCount++;
                                rowResults.add(BulkImportRowResult.builder()
                                        .rowIndex(rowIndex)
                                        .userCode(userCode)
                                        .fullName(fullName)
                                        .email(email)
                                        .role(rawRole)
                                        .status("FAILED")
                                        .errorMessage("Luồng Người dùng thường không cho phép vai trò cán bộ (" + rawRole + "). Vui lòng sử dụng tính năng 'Nạp cán bộ / bảo vệ'.")
                                        .build());
                                continue;
                            }
                        } catch (IllegalArgumentException ex) {
                            failureCount++;
                            rowResults.add(BulkImportRowResult.builder()
                                    .rowIndex(rowIndex)
                                    .userCode(userCode)
                                    .fullName(fullName)
                                    .email(email)
                                    .role(rawRole)
                                    .status("FAILED")
                                    .errorMessage("Giá trị vai trò không hợp lệ: " + rawRole)
                                    .build());
                            continue;
                        }
                    }

                    List<String> inDuplicateErrors = new ArrayList<>();
                    if (!userCode.isBlank() && codeCountsInFile.getOrDefault(userCode, 0) > 1) {
                        inDuplicateErrors.add("Mã người dùng bị trùng lặp trong file import");
                    }
                    if (!email.isBlank() && emailCountsInFile.getOrDefault(email, 0) > 1) {
                        inDuplicateErrors.add("Email bị trùng lặp trong file import");
                    }
                    if (!inDuplicateErrors.isEmpty()) {
                        failureCount++;
                        rowResults.add(BulkImportRowResult.builder()
                                .rowIndex(rowIndex)
                                .userCode(userCode)
                                .fullName(fullName)
                                .email(email)
                                .role(Role.NORMAL_USER.name())
                                .status("FAILED")
                                .errorMessage(String.join(". ", inDuplicateErrors))
                                .build());
                        continue;
                    }

                    ZipImageEntry imgEntry = imageMap.get(userCode.toLowerCase());
                    byte[] imgBytes = imgEntry != null ? imgEntry.bytes : null;
                    String imgFileName = imgEntry != null ? imgEntry.fileName : userCode + ".jpg";
                    String tempPassword = generateRandomPassword(12);

                    BulkImportRowResult result = userBulkImportHelper.processSingleRow(
                            rowIndex, userCode, fullName, email, Role.NORMAL_USER, importBatchId, imgBytes, imgFileName, tempPassword
                    );

                    if ("SUCCESS".equalsIgnoreCase(result.getStatus())) {
                        successCount++;
                    } else {
                        failureCount++;
                    }
                    rowResults.add(result);
                }

                log.info("Bulk import normal users batch {} finished: total={}, success={}, failed={}",
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
            cleanupTempZip(tempZip);
        }
    }

    /**
     * LUỒNG NẠP RIÊNG CHO TÀI KHOẢN HỆ THỐNG (CÁN BỘ / BẢO VỆ):
     * - Bắt buộc có đủ 4 cột: user_code, full_name, email, role.
     * - Cột role bắt buộc có giá trị và chỉ chấp nhận: GUARD, FACILITY_MANAGER, ADMIN.
     * - Từ chối NORMAL_USER hoặc để trống role.
     * - Hỗ trợ cả file Excel (.xlsx / .xls) và file CSV (.csv) trong file ZIP.
     */
    public BulkImportResponse bulkImportStaffUsers(MultipartFile zipFile) {
        validateZipFile(zipFile);
        File tempZip = createTempZipFile(zipFile);

        try {
            try (ZipFile zf = new ZipFile(tempZip)) {
                ParsedMetadata metadata = extractMetadata(zf);
                List<String[]> rows = metadata.rows;
                int dataRowCount = rows.size() - 1;
                if (dataRowCount <= 0) {
                    throw new IllegalArgumentException("File dữ liệu không có bản ghi cán bộ nào.");
                }
                if (dataRowCount > 200) {
                    throw new MaxRecordsExceededException("File dữ liệu chứa " + dataRowCount + " bản ghi, vượt quá số lượng tối đa 200 bản ghi cho phép.");
                }

                String[] headers = rows.get(0);
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

                if (colUserCode == -1 || colFullName == -1 || colEmail == -1 || colRole == -1) {
                    throw new IllegalArgumentException("File metadata thiếu cột bắt buộc. Luồng cán bộ / bảo vệ cần đủ 4 cột: user_code, full_name, email, role.");
                }

                Map<String, ZipImageEntry> imageMap = extractImagesFromZip(zf);

                // Đếm trùng lặp trong nội bộ file
                Map<String, Integer> codeCountsInFile = new HashMap<>();
                Map<String, Integer> emailCountsInFile = new HashMap<>();
                for (int i = 1; i < rows.size(); i++) {
                    String[] tokens = rows.get(i);
                    String rawCode = (colUserCode < tokens.length) ? tokens[colUserCode] : "";
                    String rawEmail = (colEmail < tokens.length) ? tokens[colEmail] : "";
                    String userCode = StringNormalizer.normCode(rawCode);
                    String email = StringNormalizer.normEmail(rawEmail);
                    if (!userCode.isBlank()) codeCountsInFile.merge(userCode, 1, Integer::sum);
                    if (!email.isBlank()) emailCountsInFile.merge(email, 1, Integer::sum);
                }

                UUID importBatchId = UUID.randomUUID();
                log.info("Bulk import staff users batch {} started from {}, file: {}",
                        importBatchId, metadata.sourceFileName, zipFile.getOriginalFilename());

                List<BulkImportRowResult> rowResults = new ArrayList<>();
                int successCount = 0;
                int failureCount = 0;

                for (int i = 1; i < rows.size(); i++) {
                    int rowIndex = i + 1;
                    String[] tokens = rows.get(i);
                    String rawUserCode = (colUserCode < tokens.length) ? tokens[colUserCode] : "";
                    String rawFullName = (colFullName < tokens.length) ? tokens[colFullName] : "";
                    String rawEmail = (colEmail < tokens.length) ? tokens[colEmail] : "";
                    String rawRole = (colRole < tokens.length) ? tokens[colRole].trim() : "";

                    String userCode = StringNormalizer.normCode(rawUserCode);
                    String fullName = StringNormalizer.normName(rawFullName);
                    String email = StringNormalizer.normEmail(rawEmail);

                    if (rawRole.isBlank()) {
                        failureCount++;
                        rowResults.add(BulkImportRowResult.builder()
                                .rowIndex(rowIndex)
                                .userCode(userCode)
                                .fullName(fullName)
                                .email(email)
                                .role(null)
                                .status("FAILED")
                                .errorMessage("Cột role không được để trống cho tài khoản hệ thống")
                                .build());
                        continue;
                    }

                    Role targetRole;
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
                                .errorMessage("Vai trò không hợp lệ cho tài khoản hệ thống (chỉ chấp nhận: GUARD, FACILITY_MANAGER, ADMIN). Không chấp nhận NORMAL_USER.")
                                .build());
                        continue;
                    }

                    List<String> inDuplicateErrors = new ArrayList<>();
                    if (!userCode.isBlank() && codeCountsInFile.getOrDefault(userCode, 0) > 1) {
                        inDuplicateErrors.add("Mã người dùng bị trùng lặp trong file import");
                    }
                    if (!email.isBlank() && emailCountsInFile.getOrDefault(email, 0) > 1) {
                        inDuplicateErrors.add("Email bị trùng lặp trong file import");
                    }
                    if (!inDuplicateErrors.isEmpty()) {
                        failureCount++;
                        rowResults.add(BulkImportRowResult.builder()
                                .rowIndex(rowIndex)
                                .userCode(userCode)
                                .fullName(fullName)
                                .email(email)
                                .role(targetRole.name())
                                .status("FAILED")
                                .errorMessage(String.join(". ", inDuplicateErrors))
                                .build());
                        continue;
                    }

                    ZipImageEntry imgEntry = imageMap.get(userCode.toLowerCase());
                    byte[] imgBytes = imgEntry != null ? imgEntry.bytes : null;
                    String imgFileName = imgEntry != null ? imgEntry.fileName : userCode + ".jpg";
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

                log.info("Bulk import staff users batch {} finished: total={}, success={}, failed={}",
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
            cleanupTempZip(tempZip);
        }
    }

    private void validateZipFile(MultipartFile zipFile) {
        if (zipFile == null || zipFile.isEmpty()) {
            throw new IllegalArgumentException("Vui lòng chọn file ZIP để nạp dữ liệu.");
        }
        String originalFilename = zipFile.getOriginalFilename();
        if (originalFilename == null || !originalFilename.toLowerCase().endsWith(".zip")) {
            throw new IllegalArgumentException("Định dạng file không hợp lệ. Chỉ chấp nhận file nén .zip.");
        }
    }

    private File createTempZipFile(MultipartFile zipFile) {
        try {
            File tempZip = File.createTempFile("import-", ".zip");
            zipFile.transferTo(tempZip);
            return tempZip;
        } catch (IOException e) {
            log.error("Lỗi khi tạo/ghi file ZIP tạm: {}", e.getMessage(), e);
            throw new IllegalArgumentException("Không thể lưu file ZIP tải lên: " + e.getMessage());
        }
    }

    private void cleanupTempZip(File tempZip) {
        if (tempZip != null && tempZip.exists()) {
            boolean deleted = tempZip.delete();
            if (!deleted) {
                log.warn("Không thể xoá file ZIP tạm: {}", tempZip.getAbsolutePath());
            }
        }
    }

    private ParsedMetadata extractMetadata(ZipFile zf) throws IOException {
        ZipEntry excelEntry = null;
        ZipEntry csvEntry = null;

        Enumeration<? extends ZipEntry> entries = zf.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || entry.getName().startsWith("__MACOSX") || entry.getName().startsWith(".")) {
                continue;
            }
            String entryName = entry.getName().replace('\\', '/');
            String fileNameOnly = entryName.contains("/") ? entryName.substring(entryName.lastIndexOf('/') + 1) : entryName;
            String lower = fileNameOnly.toLowerCase();

            if (lower.equals("metadata.xlsx") || lower.equals("metadata.xls")) {
                excelEntry = entry;
                break; // Ưu tiên cao nhất
            } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
                if (excelEntry == null) excelEntry = entry;
            } else if (lower.equals("metadata.csv")) {
                if (csvEntry == null || !csvEntry.getName().toLowerCase().endsWith("metadata.csv")) {
                    csvEntry = entry;
                }
            } else if (lower.endsWith(".csv")) {
                if (csvEntry == null) csvEntry = entry;
            }
        }

        // 1. Ưu tiên đọc Excel (.xlsx/.xls) bằng Apache POI để giữ 100% tiếng Việt Unicode chuẩn
        if (excelEntry != null) {
            try (InputStream is = zf.getInputStream(excelEntry);
                 Workbook workbook = WorkbookFactory.create(is)) {
                Sheet sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
                if (sheet == null) {
                    throw new IllegalArgumentException("File Excel trong ZIP không có sheet dữ liệu nào.");
                }
                DataFormatter formatter = new DataFormatter();
                List<String[]> rows = new ArrayList<>();
                for (Row row : sheet) {
                    short lastCell = row.getLastCellNum();
                    if (lastCell <= 0) continue;
                    String[] cells = new String[lastCell];
                    boolean hasContent = false;
                    for (int c = 0; c < lastCell; c++) {
                        Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        String val = cell != null ? formatter.formatCellValue(cell).trim() : "";
                        if (!val.isEmpty()) hasContent = true;
                        cells[c] = val;
                    }
                    if (hasContent) {
                        rows.add(cells);
                    }
                }
                if (rows.isEmpty()) {
                    throw new IllegalArgumentException("File Excel (" + excelEntry.getName() + ") rỗng.");
                }
                log.info("Đã trích xuất metadata từ file Excel: {} ({} dòng)", excelEntry.getName(), rows.size());
                return new ParsedMetadata(rows, excelEntry.getName());
            } catch (Exception e) {
                log.error("Lỗi khi đọc file Excel từ ZIP: {}", e.getMessage(), e);
                throw new IllegalArgumentException("Không thể đọc file Excel trong ZIP: " + e.getMessage());
            }
        }

        // 2. Fallback đọc file CSV (hỗ trợ UTF-8 chuẩn và UTF-8 BOM)
        if (csvEntry != null) {
            byte[] csvBytes;
            try (InputStream is = zf.getInputStream(csvEntry)) {
                csvBytes = is.readAllBytes();
            } catch (IOException e) {
                log.error("Lỗi khi đọc file CSV từ ZIP: {}", e.getMessage(), e);
                throw new IllegalArgumentException("Không thể đọc file CSV trong file ZIP: " + e.getMessage());
            }

            String csvText = new String(csvBytes, StandardCharsets.UTF_8);
            if (csvText.startsWith("\uFEFF")) {
                csvText = csvText.substring(1);
            }

            String[] lines = csvText.split("\\r?\\n");
            List<String[]> rows = new ArrayList<>();
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    rows.add(parseCsvLine(line));
                }
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("File CSV (" + csvEntry.getName() + ") rỗng.");
            }
            log.info("Đã trích xuất metadata từ file CSV: {} ({} dòng)", csvEntry.getName(), rows.size());
            return new ParsedMetadata(rows, csvEntry.getName());
        }

        throw new IllegalArgumentException("Không tìm thấy file dữ liệu (metadata.xlsx hoặc metadata.csv) trong file ZIP.");
    }

    private Map<String, ZipImageEntry> extractImagesFromZip(ZipFile zf) throws IOException {
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
        return imageMap;
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
}
