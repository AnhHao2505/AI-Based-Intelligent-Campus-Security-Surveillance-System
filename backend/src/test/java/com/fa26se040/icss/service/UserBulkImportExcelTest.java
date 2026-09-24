package com.fa26se040.icss.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

public class UserBulkImportExcelTest {

    @Test
    public void testReadMetadataXlsxFromZip() throws Exception {
        File zipFile = new File("D:/DoAnSE/New folder.zip");
        if (!zipFile.exists()) {
            return; // Skip on external CI environments
        }

        try (ZipFile zf = new ZipFile(zipFile)) {
            ZipEntry excelEntry = zf.getEntry("metadata.xlsx");
            assertNotNull(excelEntry, "metadata.xlsx must exist inside zip");

            try (InputStream is = zf.getInputStream(excelEntry);
                 Workbook workbook = WorkbookFactory.create(is)) {
                Sheet sheet = workbook.getSheetAt(0);
                assertNotNull(sheet);

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

                assertEquals(31, rows.size(), "Should have header + 30 rows");
                assertEquals("GU001", rows.get(1)[0]);
                assertEquals("Nguyễn Văn An", rows.get(1)[1]);
                assertEquals("GU030", rows.get(30)[0]);
                assertEquals("Nghiêm Xuân Vượng", rows.get(30)[1]);
                assertEquals("GUARD", rows.get(30)[3]);

                System.out.println("TEST PASSED! First row name: " + rows.get(1)[1]);
                System.out.println("TEST PASSED! Last row name: " + rows.get(30)[1]);
            }
        }
    }
}
