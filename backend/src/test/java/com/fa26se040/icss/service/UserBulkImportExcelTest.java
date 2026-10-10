package com.fa26se040.icss.service;

import com.fa26se040.icss.dto.BulkImportResponse;
import com.fa26se040.icss.dto.BulkImportRowResult;
import com.fa26se040.icss.enums.ConfigKey;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Đọc metadata.xlsx trong ZIP nạp tài khoản theo lô (UserBulkImportService.extractMetadata qua bulkImportNormalUsers).
 * Viết lại từ bản cũ (ca36fdb^): bản cũ đọc file cố định "D:/DoAnSE/New folder.zip" và tự parse bằng POI ngay trong test,
 * không chạy code thật và luôn bỏ qua khi thiếu file. Bản này dựng ZIP + Excel trong bộ nhớ và gọi service thật.
 * Mọi dòng có role cán bộ (GUARD) nên luồng người dùng thường từ chối trước khi gọi helper -> không cần DB / MinIO.
 */
class UserBulkImportExcelTest {

    private UserBulkImportHelper helper;
    private UserBulkImportService service;

    @BeforeEach
    void setUp() {
        SystemConfigService systemConfigService = mock(SystemConfigService.class);
        when(systemConfigService.getInt(ConfigKey.USER_BULK_IMPORT_MAX_ROWS)).thenReturn(200);
        helper = mock(UserBulkImportHelper.class);
        service = new UserBulkImportService(helper, systemConfigService);
    }

    /** metadata.xlsx: header user_code, full_name, email, role + các dòng cho trước (null = dòng trống). */
    private static byte[] xlsx(List<Object[]> dataRows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("users");
            Row header = sheet.createRow(0);
            String[] cols = {"user_code", "full_name", "email", "role"};
            for (int c = 0; c < cols.length; c++) {
                header.createCell(c).setCellValue(cols[c]);
            }
            int r = 1;
            for (Object[] data : dataRows) {
                Row row = sheet.createRow(r++);
                if (data == null) {
                    continue;
                }
                for (int c = 0; c < data.length; c++) {
                    if (data[c] instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else {
                        row.createCell(c).setCellValue((String) data[c]);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static MockMultipartFile zip(String[] names, byte[][] contents) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bytes)) {
            for (int i = 0; i < names.length; i++) {
                zos.putNextEntry(new ZipEntry(names[i]));
                zos.write(contents[i]);
                zos.closeEntry();
            }
        }
        return new MockMultipartFile("file", "import.zip", "application/zip", bytes.toByteArray());
    }

    private static List<Object[]> thirtyGuards() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            String code = String.format("GU%03d", i);
            String name = i == 1 ? "Nguyễn Văn An" : i == 30 ? "Nghiêm Xuân Vượng" : "Bảo vệ số " + i;
            rows.add(new Object[]{code, name, code.toLowerCase() + "@fpt.edu.vn", "GUARD"});
            if (i == 15) {
                rows.add(null); // dòng trống giữa file bị bỏ qua
            }
        }
        return rows;
    }

    @Test
    @DisplayName("metadata.xlsx trong ZIP: header + 30 dòng (bỏ dòng trống), giữ nguyên tiếng Việt, cột role đọc đúng")
    void readsMetadataXlsxFromZip() throws Exception {
        BulkImportResponse res = service.bulkImportNormalUsers(
                zip(new String[]{"metadata.xlsx"}, new byte[][]{xlsx(thirtyGuards())}));

        assertEquals(30, res.getTotalRows());
        List<BulkImportRowResult> rows = res.getResults();
        assertEquals(30, rows.size());
        assertEquals("GU001", rows.get(0).getUserCode());
        assertEquals("Nguyễn Văn An", rows.get(0).getFullName());
        assertEquals("GU030", rows.get(29).getUserCode());
        assertEquals("Nghiêm Xuân Vượng", rows.get(29).getFullName());
        assertEquals("GUARD", rows.get(29).getRole());
        // Luồng người dùng thường từ chối vai trò cán bộ, không gọi helper (không ghi user / MinIO)
        assertEquals(30, res.getFailureCount());
        assertTrue(rows.stream().allMatch(r -> "FAILED".equals(r.getStatus())));
        verifyNoInteractions(helper);
    }

    @Test
    @DisplayName("Ô số trong Excel đọc như hiển thị (12345, không phải 12345.0)")
    void numericCell_readAsDisplayed() throws Exception {
        List<Object[]> rows = List.<Object[]>of(new Object[]{12345, "Trần Thị Số", "so@fpt.edu.vn", "GUARD"});

        BulkImportResponse res = service.bulkImportNormalUsers(
                zip(new String[]{"metadata.xlsx"}, new byte[][]{xlsx(rows)}));

        assertEquals("12345", res.getResults().get(0).getUserCode());
        assertEquals("Trần Thị Số", res.getResults().get(0).getFullName());
    }

    @Test
    @DisplayName("ZIP có cả metadata.csv và metadata.xlsx: ưu tiên Excel")
    void excelPreferredOverCsv() throws Exception {
        byte[] csv = "user_code,full_name,email,role\nCSV001,Từ CSV,csv@fpt.edu.vn,GUARD\n".getBytes(StandardCharsets.UTF_8);
        List<Object[]> rows = List.<Object[]>of(new Object[]{"XLS001", "Từ Excel", "xls@fpt.edu.vn", "GUARD"});

        BulkImportResponse res = service.bulkImportNormalUsers(
                zip(new String[]{"metadata.csv", "metadata.xlsx"}, new byte[][]{csv, xlsx(rows)}));

        assertEquals(1, res.getTotalRows());
        assertEquals("XLS001", res.getResults().get(0).getUserCode());
        assertEquals("Từ Excel", res.getResults().get(0).getFullName());
    }

    // ------------------------------------------------------------------ BR-AU-26: cột bắt buộc, file không có dữ liệu

    /** metadata.xlsx với header tuỳ chọn (để thử thiếu cột) + các dòng dữ liệu. */
    private static byte[] xlsxWithHeader(String[] cols, List<String[]> dataRows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("users");
            if (cols != null) {
                Row header = sheet.createRow(0);
                for (int c = 0; c < cols.length; c++) {
                    header.createCell(c).setCellValue(cols[c]);
                }
                int r = 1;
                for (String[] data : dataRows) {
                    Row row = sheet.createRow(r++);
                    for (int c = 0; c < data.length; c++) {
                        row.createCell(c).setCellValue(data[c]);
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static MockMultipartFile metadataZip(byte[] xlsx) throws Exception {
        return zip(new String[]{"metadata.xlsx"}, new byte[][]{xlsx});
    }

    @Test
    @DisplayName("BR-AU-26: luồng người dùng thường thiếu cột email -> lỗi nêu 3 cột bắt buộc, không tạo ai")
    void normal_missingRequiredColumn_rejected() throws Exception {
        byte[] file = xlsxWithHeader(new String[]{"user_code", "full_name"},
                List.<String[]>of(new String[]{"SV900", "Thiếu Email"}));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.bulkImportNormalUsers(metadataZip(file)));

        assertTrue(ex.getMessage().contains("thiếu cột bắt buộc"), ex.getMessage());
        assertTrue(ex.getMessage().contains("user_code, full_name, email"), ex.getMessage());
        verifyNoInteractions(helper);
    }

    @Test
    @DisplayName("BR-AU-26: luồng cán bộ thiếu cột role -> lỗi nêu 4 cột bắt buộc, không tạo ai")
    void staff_missingRoleColumn_rejected() throws Exception {
        byte[] file = xlsxWithHeader(new String[]{"user_code", "full_name", "email"},
                List.<String[]>of(new String[]{"GU900", "Thiếu Vai Trò", "gu900@fpt.edu.vn"}));

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.bulkImportStaffUsers(metadataZip(file)));

        assertTrue(ex.getMessage().contains("thiếu cột bắt buộc"), ex.getMessage());
        assertTrue(ex.getMessage().contains("user_code, full_name, email, role"), ex.getMessage());
        verifyNoInteractions(helper);
    }

    @Test
    @DisplayName("BR-AU-26: metadata chỉ có dòng tiêu đề (xlsx và csv) -> lỗi không có bản ghi, cả hai luồng")
    void headerOnly_noDataRow_rejected() throws Exception {
        byte[] headerOnlyXlsx = xlsxWithHeader(new String[]{"user_code", "full_name", "email", "role"}, List.of());
        byte[] headerOnlyCsv = "user_code,full_name,email,role\n".getBytes(StandardCharsets.UTF_8);

        IllegalArgumentException normalXlsx = assertThrows(IllegalArgumentException.class,
                () -> service.bulkImportNormalUsers(metadataZip(headerOnlyXlsx)));
        assertEquals("File dữ liệu không có bản ghi người dùng nào.", normalXlsx.getMessage());

        IllegalArgumentException staffXlsx = assertThrows(IllegalArgumentException.class,
                () -> service.bulkImportStaffUsers(metadataZip(headerOnlyXlsx)));
        assertEquals("File dữ liệu không có bản ghi cán bộ nào.", staffXlsx.getMessage());

        IllegalArgumentException normalCsv = assertThrows(IllegalArgumentException.class,
                () -> service.bulkImportNormalUsers(zip(new String[]{"metadata.csv"}, new byte[][]{headerOnlyCsv})));
        assertEquals("File dữ liệu không có bản ghi người dùng nào.", normalCsv.getMessage());
        verifyNoInteractions(helper);
    }

    @Test
    @DisplayName("BR-AU-26: sheet Excel không có dòng nào -> lỗi file rỗng")
    void emptySheet_rejected() throws Exception {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.bulkImportNormalUsers(metadataZip(xlsxWithHeader(null, List.of()))));

        assertTrue(ex.getMessage().contains("rỗng"), ex.getMessage());
        verifyNoInteractions(helper);
    }

    @Test
    @DisplayName("BR-AU-26: ZIP không có metadata.xlsx / metadata.csv; file không phải .zip; file rỗng -> lỗi tương ứng")
    void zipAndMetadataPresence_rejected() throws Exception {
        MockMultipartFile noMetadata = zip(new String[]{"images/SV900.jpg"}, new byte[][]{new byte[]{1, 2, 3}});
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> service.bulkImportNormalUsers(noMetadata));
        assertTrue(missing.getMessage().contains("Không tìm thấy file dữ liệu"), missing.getMessage());

        MockMultipartFile notZip = new MockMultipartFile("file", "users.xlsx", "application/octet-stream", new byte[]{1});
        IllegalArgumentException wrongType = assertThrows(IllegalArgumentException.class,
                () -> service.bulkImportNormalUsers(notZip));
        assertTrue(wrongType.getMessage().contains(".zip"), wrongType.getMessage());

        MockMultipartFile empty = new MockMultipartFile("file", "import.zip", "application/zip", new byte[0]);
        IllegalArgumentException emptyFile = assertThrows(IllegalArgumentException.class,
                () -> service.bulkImportStaffUsers(empty));
        assertEquals("Vui lòng chọn file ZIP để nạp dữ liệu.", emptyFile.getMessage());
        verifyNoInteractions(helper);
    }
}
