package com.fa26se040.icss.service;

import com.fa26se040.icss.entity.GuardShift;
import com.fa26se040.icss.entity.GuardShiftRequest;
import com.fa26se040.icss.entity.GuardTeam;
import com.fa26se040.icss.entity.User;
import com.fa26se040.icss.enums.GuardShiftRequestStatus;
import com.fa26se040.icss.enums.GuardShiftRequestType;
import com.fa26se040.icss.enums.Role;
import com.fa26se040.icss.enums.ShiftStatus;
import com.fa26se040.icss.enums.ShiftType;
import com.fa26se040.icss.repository.GuardShiftRepository;
import com.fa26se040.icss.repository.GuardShiftRequestRepository;
import com.fa26se040.icss.repository.GuardTeamRepository;
import com.fa26se040.icss.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class GuardShiftExportService {

    private final GuardShiftRepository guardShiftRepository;
    private final GuardShiftRequestRepository guardShiftRequestRepository;
    private final UserRepository userRepository;
    private final GuardTeamRepository guardTeamRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss dd/MM/yyyy");

    @Transactional(readOnly = true)
    public byte[] exportMonthlyTimesheet(int year, int month, UUID teamId) throws IOException {
        log.info("Generating guard payroll timesheet Excel export for year={}, month={}, teamId={}", year, month, teamId);

        YearMonth ym = YearMonth.of(year, month);
        LocalDate startDate = ym.atDay(1);
        LocalDate endDate = ym.atEndOfMonth();

        // 1. Fetch guards
        List<User> guards = userRepository.findActiveUsersByRole(Role.GUARD).stream()
                .filter(g -> teamId == null || (g.getTeam() != null && teamId.equals(g.getTeam().getId())))
                .sorted(Comparator.comparing(User::getFullName, String.CASE_INSENSITIVE_ORDER))
                .collect(Collectors.toList());

        // Optional team name
        String teamName = "Tất cả các đội bảo vệ";
        if (teamId != null) {
            Optional<GuardTeam> tOpt = guardTeamRepository.findById(teamId);
            if (tOpt.isPresent()) {
                teamName = tOpt.get().getTeamName();
            }
        }

        // 2. Fetch shifts in month
        List<GuardShift> allShifts = guardShiftRepository.findByShiftDateBetween(startDate, endDate).stream()
                .filter(s -> teamId == null || (s.getGuard() != null && s.getGuard().getTeam() != null && teamId.equals(s.getGuard().getTeam().getId())))
                .sorted(Comparator.comparing(GuardShift::getShiftDate).thenComparing(GuardShift::getStartTime))
                .collect(Collectors.toList());

        // Group shifts by guard id
        Map<UUID, List<GuardShift>> shiftsByGuard = allShifts.stream()
                .filter(s -> s.getGuard() != null)
                .collect(Collectors.groupingBy(s -> s.getGuard().getId()));

        // 3. Fetch approved leave requests in month
        List<GuardShiftRequest> leaveRequests = guardShiftRequestRepository.findRequests(
                GuardShiftRequestStatus.APPROVED,
                teamId,
                startDate,
                endDate
        );
        Map<UUID, Long> leavesByGuard = leaveRequests.stream()
                .filter(r -> r.getRequestType() == GuardShiftRequestType.LEAVE_REQUEST && r.getRequester() != null)
                .collect(Collectors.groupingBy(r -> r.getRequester().getId(), Collectors.counting()));

        // 4. Build Excel Workbook
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            CreationHelper createHelper = workbook.getCreationHelper();

            // Setup Styles
            Styles styles = createStyles(workbook, createHelper);

            // Sheet 1: Summary & Payroll
            buildSummarySheet(workbook, styles, year, month, teamName, guards, shiftsByGuard, leavesByGuard);

            // Sheet 2: Shift Details Logs
            buildDetailSheet(workbook, styles, year, month, teamName, allShifts);

            // Write to stream
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void buildSummarySheet(
            XSSFWorkbook workbook,
            Styles styles,
            int year,
            int month,
            String teamName,
            List<User> guards,
            Map<UUID, List<GuardShift>> shiftsByGuard,
            Map<UUID, Long> leavesByGuard
    ) {
        Sheet sheet = workbook.createSheet("Tổng Hợp Chấm Công & Lương");
        sheet.setDisplayGridlines(true);

        int rowIdx = 0;

        // Title
        Row titleRow = sheet.createRow(rowIdx++);
        titleRow.setHeightInPoints(30);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(String.format("BẢNG TỔNG HỢP CHẤM CÔNG VÀ DỰ TÍNH LƯƠNG BẢO VỆ - THÁNG %02d/%d", month, year));
        titleCell.setCellStyle(styles.titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 15));

        // Subtitle Info
        Row infoRow1 = sheet.createRow(rowIdx++);
        Cell infoCell1 = infoRow1.createCell(0);
        infoCell1.setCellValue(String.format("Đội bảo vệ: %s  |  Kỳ chấm công: 01/%02d/%d - %02d/%02d/%d  |  Ngày xuất file: %s",
                teamName, month, year, YearMonth.of(year, month).lengthOfMonth(), month, year,
                LocalDate.now().format(DATE_FORMATTER)));
        infoCell1.setCellStyle(styles.subInfoStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 15));

        rowIdx++; // Blank line

        // Parameter Box (Header + Values)
        Row pHeaderRow = sheet.createRow(rowIdx++);
        pHeaderRow.setHeightInPoints(20);
        Cell pHeader = pHeaderRow.createCell(1);
        pHeader.setCellValue("BẢNG THAM SỐ TÍNH LƯƠNG (Có thể chỉnh sửa để bảng tự nhảy số):");
        pHeader.setCellStyle(styles.paramHeaderStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 1, 3));

        // Row 5 (index 4): Unit price
        Row pRow1 = sheet.createRow(rowIdx++);
        Cell lbl1 = pRow1.createCell(1);
        lbl1.setCellValue("Đơn giá ca chuẩn (VNĐ):");
        lbl1.setCellStyle(styles.paramLabelStyle);
        Cell val1 = pRow1.createCell(2);
        val1.setCellValue(300000.0);
        val1.setCellStyle(styles.paramCurrencyStyle);

        // Row 6 (index 5): Night shift multiplier
        Row pRow2 = sheet.createRow(rowIdx++);
        Cell lbl2 = pRow2.createCell(1);
        lbl2.setCellValue("Hệ số ca đêm (22:00 - 06:00):");
        lbl2.setCellStyle(styles.paramLabelStyle);
        Cell val2 = pRow2.createCell(2);
        val2.setCellValue(1.30);
        val2.setCellStyle(styles.paramDecimalStyle);

        // Row 7 (index 6): OT multiplier
        Row pRow3 = sheet.createRow(rowIdx++);
        Cell lbl3 = pRow3.createCell(1);
        lbl3.setCellValue("Hệ số ca OT / Tăng cường sự kiện:");
        lbl3.setCellStyle(styles.paramLabelStyle);
        Cell val3 = pRow3.createCell(2);
        val3.setCellValue(1.50);
        val3.setCellStyle(styles.paramDecimalStyle);

        rowIdx++; // Blank line

        // Table Headers (Row index 8)
        Row headerRow = sheet.createRow(rowIdx++);
        headerRow.setHeightInPoints(26);
        String[] headers = {
                "STT", "Mã NV", "Họ và Tên", "Email", "Đội Bảo Vệ",
                "Ca Sáng", "Ca Chiều", "Ca Đêm", "Ca OT / Tăng Cường", "Trực Thay",
                "Nghỉ Phép", "Vắng Mặt", "Tổng Giờ Làm (giờ)",
                "Tổng Công Quy Đổi", "Lương Dự Tính (VNĐ)", "Ghi Chú"
        };
        for (int i = 0; i < headers.length; i++) {
            Cell c = headerRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(styles.headerStyle);
        }

        int dataStartRow = rowIdx + 1; // 1-based index in Excel for formulas

        // Data Rows
        int stt = 1;
        for (User guard : guards) {
            Row r = sheet.createRow(rowIdx++);
            r.setHeightInPoints(20);

            List<GuardShift> guardShifts = shiftsByGuard.getOrDefault(guard.getId(), Collections.emptyList());

            int morningCount = 0;
            int afternoonCount = 0;
            int nightCount = 0;
            int otCount = 0;
            int substituteCount = 0;
            int absentCount = 0;
            double totalHours = 0.0;

            for (GuardShift s : guardShifts) {
                if (s.getStatus() == ShiftStatus.CANCELLED) {
                    continue;
                }
                if (s.getStatus() == ShiftStatus.ABSENT) {
                    absentCount++;
                    continue;
                }

                // Calculate duration
                double shiftHours = 8.0;
                if (s.getCheckInAt() != null && s.getCheckOutAt() != null) {
                    long minutes = Duration.between(s.getCheckInAt(), s.getCheckOutAt()).toMinutes();
                    if (minutes > 0) {
                        shiftHours = Math.round((minutes / 60.0) * 10.0) / 10.0;
                    }
                }
                totalHours += shiftHours;

                // Shift categorization
                boolean isOt = Boolean.TRUE.equals(s.getIsOvertime()) || (s.getNotes() != null && s.getNotes().contains("⚡"));
                boolean isSub = s.getNotes() != null && (s.getNotes().contains("Trực thay do ") || s.getNotes().contains("Trực thay"));

                if (isOt) {
                    otCount++;
                } else if (isSub) {
                    substituteCount++;
                } else if (s.getShiftType() == ShiftType.SHIFT_NIGHT) {
                    nightCount++;
                } else if (s.getShiftType() == ShiftType.SHIFT_MORNING) {
                    morningCount++;
                } else if (s.getShiftType() == ShiftType.SHIFT_AFTERNOON) {
                    afternoonCount++;
                }
            }

            long leaveCount = leavesByGuard.getOrDefault(guard.getId(), 0L);

            // Columns
            // 0: STT
            Cell c0 = r.createCell(0);
            c0.setCellValue(stt++);
            c0.setCellStyle(styles.centerDataStyle);

            // 1: Mã NV
            Cell c1 = r.createCell(1);
            c1.setCellValue(guard.getUserCode() != null ? guard.getUserCode() : "");
            c1.setCellStyle(styles.centerDataStyle);

            // 2: Họ và tên
            Cell c2 = r.createCell(2);
            c2.setCellValue(guard.getFullName() != null ? guard.getFullName() : "");
            c2.setCellStyle(styles.leftDataStyle);

            // 3: Email
            Cell c3 = r.createCell(3);
            c3.setCellValue(guard.getEmail() != null ? guard.getEmail() : "");
            c3.setCellStyle(styles.leftDataStyle);

            // 4: Đội
            Cell c4 = r.createCell(4);
            c4.setCellValue(guard.getTeam() != null ? guard.getTeam().getTeamName() : "Chưa phân đội");
            c4.setCellStyle(styles.leftDataStyle);

            // 5: Ca Sáng (Col F)
            Cell c5 = r.createCell(5);
            c5.setCellValue(morningCount);
            c5.setCellStyle(styles.numberDataStyle);

            // 6: Ca Chiều (Col G)
            Cell c6 = r.createCell(6);
            c6.setCellValue(afternoonCount);
            c6.setCellStyle(styles.numberDataStyle);

            // 7: Ca Đêm (Col H)
            Cell c7 = r.createCell(7);
            c7.setCellValue(nightCount);
            c7.setCellStyle(styles.numberDataStyle);

            // 8: Ca OT (Col I)
            Cell c8 = r.createCell(8);
            c8.setCellValue(otCount);
            c8.setCellStyle(styles.numberDataStyle);

            // 9: Trực thay (Col J)
            Cell c9 = r.createCell(9);
            c9.setCellValue(substituteCount);
            c9.setCellStyle(styles.numberDataStyle);

            // 10: Nghỉ phép (Col K)
            Cell c10 = r.createCell(10);
            c10.setCellValue(leaveCount);
            c10.setCellStyle(styles.numberDataStyle);

            // 11: Vắng mặt (Col L)
            Cell c11 = r.createCell(11);
            c11.setCellValue(absentCount);
            c11.setCellStyle(styles.numberDataStyle);

            // 12: Tổng giờ làm (Col M)
            Cell c12 = r.createCell(12);
            c12.setCellValue(totalHours);
            c12.setCellStyle(styles.decimalDataStyle);

            // 13: Tổng công quy đổi (Col N) -> Formula: =(F{row}+G{row}+J{row}) + (H{row}*$C$6) + (I{row}*$C$7)
            int excelRowNum = rowIdx; // 1-based index
            Cell c13 = r.createCell(13);
            String convFormula = String.format("(F%d+G%d+J%d)+(H%d*$C$6)+(I%d*$C$7)",
                    excelRowNum, excelRowNum, excelRowNum, excelRowNum, excelRowNum);
            c13.setCellFormula(convFormula);
            c13.setCellStyle(styles.formulaDecimalStyle);

            // 14: Lương dự tính (Col O) -> Formula: =N{row}*$C$5
            Cell c14 = r.createCell(14);
            String salaryFormula = String.format("N%d*$C$5", excelRowNum);
            c14.setCellFormula(salaryFormula);
            c14.setCellStyle(styles.formulaCurrencyStyle);

            // 15: Ghi chú
            Cell c15 = r.createCell(15);
            c15.setCellValue("");
            c15.setCellStyle(styles.leftDataStyle);
        }

        int dataEndRow = rowIdx; // 1-based index of last data row

        // Total Row
        Row totalRow = sheet.createRow(rowIdx++);
        totalRow.setHeightInPoints(24);
        Cell totLabel = totalRow.createCell(0);
        totLabel.setCellValue("TỔNG CỘNG:");
        totLabel.setCellStyle(styles.totalLabelStyle);
        sheet.addMergedRegion(new CellRangeAddress(rowIdx - 1, rowIdx - 1, 0, 4));

        for (int col = 1; col <= 4; col++) {
            Cell emptyC = totalRow.createCell(col);
            emptyC.setCellStyle(styles.totalLabelStyle);
        }

        // Sum formulas for numerical columns (Cols F to O)
        for (int col = 5; col <= 14; col++) {
            Cell tc = totalRow.createCell(col);
            String colLetter = getColumnLetter(col);
            if (dataStartRow <= dataEndRow) {
                tc.setCellFormula(String.format("SUM(%s%d:%s%d)", colLetter, dataStartRow, colLetter, dataEndRow));
            } else {
                tc.setCellValue(0);
            }
            if (col == 14) {
                tc.setCellStyle(styles.totalCurrencyStyle);
            } else if (col == 12 || col == 13) {
                tc.setCellStyle(styles.totalDecimalStyle);
            } else {
                tc.setCellStyle(styles.totalNumberStyle);
            }
        }
        Cell totNote = totalRow.createCell(15);
        totNote.setCellValue("");
        totNote.setCellStyle(styles.totalLabelStyle);

        // Signatures area
        rowIdx += 2;
        Row sigTitleRow = sheet.createRow(rowIdx++);
        Cell sig1 = sigTitleRow.createCell(1);
        sig1.setCellValue("Người Lập Bảng");
        sig1.setCellStyle(styles.signatureHeaderStyle);

        Cell sig2 = sigTitleRow.createCell(6);
        sig2.setCellValue("Đội Trưởng Bảo Vệ");
        sig2.setCellStyle(styles.signatureHeaderStyle);

        Cell sig3 = sigTitleRow.createCell(12);
        sig3.setCellValue("Trưởng Phòng QLVH / Kế Toán");
        sig3.setCellStyle(styles.signatureHeaderStyle);

        Row sigNoteRow = sheet.createRow(rowIdx);
        Cell sn1 = sigNoteRow.createCell(1);
        sn1.setCellValue("(Ký và ghi rõ họ tên)");
        sn1.setCellStyle(styles.signatureNoteStyle);

        Cell sn2 = sigNoteRow.createCell(6);
        sn2.setCellValue("(Ký và ghi rõ họ tên)");
        sn2.setCellStyle(styles.signatureNoteStyle);

        Cell sn3 = sigNoteRow.createCell(12);
        sn3.setCellValue("(Ký và ghi rõ họ tên)");
        sn3.setCellStyle(styles.signatureNoteStyle);

        // Auto-fit columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
            int width = sheet.getColumnWidth(i);
            sheet.setColumnWidth(i, Math.max(width + 800, 3200));
        }
        sheet.setColumnWidth(0, 2000);  // STT
        sheet.setColumnWidth(2, 6000);  // Họ và tên
        sheet.setColumnWidth(4, 4500);  // Đội bảo vệ
        sheet.setColumnWidth(13, 4500); // Tổng công
        sheet.setColumnWidth(14, 5200); // Lương dự tính
    }

    private void buildDetailSheet(
            XSSFWorkbook workbook,
            Styles styles,
            int year,
            int month,
            String teamName,
            List<GuardShift> shifts
    ) {
        Sheet sheet = workbook.createSheet("Chi Tiết Ca Trực");
        sheet.setDisplayGridlines(true);

        int rowIdx = 0;

        // Title
        Row titleRow = sheet.createRow(rowIdx++);
        titleRow.setHeightInPoints(28);
        Cell titleCell = titleRow.createCell(0);
        titleCell.setCellValue(String.format("BẢNG ĐỐI SOÁT CHI TIẾT TỪNG CA TRỰC - THÁNG %02d/%d", month, year));
        titleCell.setCellStyle(styles.titleStyle);
        sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 13));

        // Subtitle
        Row infoRow = sheet.createRow(rowIdx++);
        Cell infoCell = infoRow.createCell(0);
        infoCell.setCellValue(String.format("Đội bảo vệ: %s  |  Tổng số ca: %d ca", teamName, shifts.size()));
        infoCell.setCellStyle(styles.subInfoStyle);
        sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, 13));

        rowIdx++; // Blank line

        // Headers
        Row headerRow = sheet.createRow(rowIdx++);
        headerRow.setHeightInPoints(24);
        String[] headers = {
                "STT", "Ngày Trực", "Thứ", "Mã NV", "Họ và Tên",
                "Đội Bảo Vệ", "Loại Ca", "Giờ Kế Hoạch", "Khu Vực",
                "Giờ Check-in", "Giờ Check-out", "Trạng Thái", "Tăng Cường?", "Ghi Chú"
        };
        for (int i = 0; i < headers.length; i++) {
            Cell c = headerRow.createCell(i);
            c.setCellValue(headers[i]);
            c.setCellStyle(styles.detailHeaderStyle);
        }

        int stt = 1;
        for (GuardShift s : shifts) {
            Row r = sheet.createRow(rowIdx++);
            r.setHeightInPoints(19);

            // STT
            Cell c0 = r.createCell(0);
            c0.setCellValue(stt++);
            c0.setCellStyle(styles.centerDataStyle);

            // Ngày trực
            Cell c1 = r.createCell(1);
            c1.setCellValue(s.getShiftDate().format(DATE_FORMATTER));
            c1.setCellStyle(styles.centerDataStyle);

            // Thứ
            Cell c2 = r.createCell(2);
            c2.setCellValue(getDayOfWeekName(s.getShiftDate()));
            c2.setCellStyle(styles.centerDataStyle);

            // Mã NV
            Cell c3 = r.createCell(3);
            c3.setCellValue(s.getGuard() != null && s.getGuard().getUserCode() != null ? s.getGuard().getUserCode() : "");
            c3.setCellStyle(styles.centerDataStyle);

            // Họ tên
            Cell c4 = r.createCell(4);
            c4.setCellValue(s.getGuard() != null && s.getGuard().getFullName() != null ? s.getGuard().getFullName() : "");
            c4.setCellStyle(styles.leftDataStyle);

            // Đội
            Cell c5 = r.createCell(5);
            c5.setCellValue(s.getGuard() != null && s.getGuard().getTeam() != null ? s.getGuard().getTeam().getTeamName() : "");
            c5.setCellStyle(styles.leftDataStyle);

            // Loại ca
            Cell c6 = r.createCell(6);
            c6.setCellValue(formatShiftTypeName(s.getShiftType()));
            c6.setCellStyle(styles.centerDataStyle);

            // Giờ kế hoạch
            Cell c7 = r.createCell(7);
            String planTime = String.format("%s - %s",
                    s.getStartTime() != null ? s.getStartTime().format(TIME_FORMATTER) : "",
                    s.getEndTime() != null ? s.getEndTime().format(TIME_FORMATTER) : "");
            c7.setCellValue(planTime);
            c7.setCellStyle(styles.centerDataStyle);

            // Khu vực
            Cell c8 = r.createCell(8);
            c8.setCellValue(s.getArea() != null ? s.getArea().getName() : "");
            c8.setCellStyle(styles.leftDataStyle);

            // Check-in
            Cell c9 = r.createCell(9);
            c9.setCellValue(s.getCheckInAt() != null ? s.getCheckInAt().format(DATETIME_FORMATTER) : "--");
            c9.setCellStyle(styles.centerDataStyle);

            // Check-out
            Cell c10 = r.createCell(10);
            c10.setCellValue(s.getCheckOutAt() != null ? s.getCheckOutAt().format(DATETIME_FORMATTER) : "--");
            c10.setCellStyle(styles.centerDataStyle);

            // Trạng thái
            Cell c11 = r.createCell(11);
            c11.setCellValue(formatShiftStatusName(s.getStatus()));
            c11.setCellStyle(styles.centerDataStyle);

            // OT?
            Cell c12 = r.createCell(12);
            boolean isOt = Boolean.TRUE.equals(s.getIsOvertime()) || (s.getNotes() != null && s.getNotes().contains("⚡"));
            c12.setCellValue(isOt ? "Có (OT)" : "Không");
            c12.setCellStyle(styles.centerDataStyle);

            // Ghi chú
            Cell c13 = r.createCell(13);
            c13.setCellValue(s.getNotes() != null ? s.getNotes() : "");
            c13.setCellStyle(styles.leftDataStyle);
        }

        // Auto-fit columns
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
            int width = sheet.getColumnWidth(i);
            sheet.setColumnWidth(i, Math.max(width + 600, 3000));
        }
        sheet.setColumnWidth(0, 1800);  // STT
        sheet.setColumnWidth(4, 5500);  // Họ tên
        sheet.setColumnWidth(9, 4800);  // Check-in
        sheet.setColumnWidth(10, 4800); // Check-out
        sheet.setColumnWidth(13, 7000); // Ghi chú
    }

    private Styles createStyles(XSSFWorkbook workbook, CreationHelper createHelper) {
        Styles s = new Styles();

        // Palette
        byte[] navyBlue = new byte[]{(byte) 30, (byte) 58, (byte) 138};
        byte[] slateBlue = new byte[]{(byte) 51, (byte) 65, (byte) 85};
        byte[] lightYellow = new byte[]{(byte) 254, (byte) 243, (byte) 199};
        byte[] grayBorder = new byte[]{(byte) 203, (byte) 213, (byte) 225};

        // Title Style
        XSSFFont titleFont = workbook.createFont();
        titleFont.setFontName("Segoe UI");
        titleFont.setFontHeightInPoints((short) 15);
        titleFont.setBold(true);
        titleFont.setColor(new XSSFColor(navyBlue, null));

        s.titleStyle = workbook.createCellStyle();
        s.titleStyle.setFont(titleFont);
        s.titleStyle.setAlignment(HorizontalAlignment.CENTER);
        s.titleStyle.setVerticalAlignment(VerticalAlignment.CENTER);

        // Sub Info Style
        XSSFFont subFont = workbook.createFont();
        subFont.setFontName("Segoe UI");
        subFont.setFontHeightInPoints((short) 10);
        subFont.setItalic(true);
        subFont.setColor(IndexedColors.GREY_50_PERCENT.getIndex());

        s.subInfoStyle = workbook.createCellStyle();
        s.subInfoStyle.setFont(subFont);
        s.subInfoStyle.setAlignment(HorizontalAlignment.CENTER);

        // Parameter Box Styles
        XSSFFont paramHFont = workbook.createFont();
        paramHFont.setFontName("Segoe UI");
        paramHFont.setFontHeightInPoints((short) 10);
        paramHFont.setBold(true);
        paramHFont.setColor(new XSSFColor(new byte[]{(byte) 146, (byte) 64, (byte) 14}, null));

        s.paramHeaderStyle = workbook.createCellStyle();
        s.paramHeaderStyle.setFont(paramHFont);
        s.paramHeaderStyle.setFillForegroundColor(new XSSFColor(lightYellow, null));
        s.paramHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.paramHeaderStyle.setBorderTop(BorderStyle.THIN);
        s.paramHeaderStyle.setBorderLeft(BorderStyle.THIN);
        s.paramHeaderStyle.setBorderRight(BorderStyle.THIN);

        XSSFFont paramFont = workbook.createFont();
        paramFont.setFontName("Segoe UI");
        paramFont.setFontHeightInPoints((short) 10);

        s.paramLabelStyle = workbook.createCellStyle();
        s.paramLabelStyle.setFont(paramFont);
        s.paramLabelStyle.setBorderLeft(BorderStyle.THIN);
        s.paramLabelStyle.setBorderBottom(BorderStyle.THIN);

        s.paramCurrencyStyle = workbook.createCellStyle();
        s.paramCurrencyStyle.setFont(paramFont);
        s.paramCurrencyStyle.setDataFormat(createHelper.createDataFormat().getFormat("#,##0 \"VNĐ\""));
        s.paramCurrencyStyle.setBorderRight(BorderStyle.THIN);
        s.paramCurrencyStyle.setBorderBottom(BorderStyle.THIN);
        s.paramCurrencyStyle.setAlignment(HorizontalAlignment.RIGHT);

        s.paramDecimalStyle = workbook.createCellStyle();
        s.paramDecimalStyle.setFont(paramFont);
        s.paramDecimalStyle.setDataFormat(createHelper.createDataFormat().getFormat("0.00"));
        s.paramDecimalStyle.setBorderRight(BorderStyle.THIN);
        s.paramDecimalStyle.setBorderBottom(BorderStyle.THIN);
        s.paramDecimalStyle.setAlignment(HorizontalAlignment.RIGHT);

        // Header Style (Sheet 1)
        XSSFFont headerFont = workbook.createFont();
        headerFont.setFontName("Segoe UI");
        headerFont.setFontHeightInPoints((short) 10);
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());

        s.headerStyle = workbook.createCellStyle();
        s.headerStyle.setFont(headerFont);
        s.headerStyle.setFillForegroundColor(new XSSFColor(navyBlue, null));
        s.headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.headerStyle.setAlignment(HorizontalAlignment.CENTER);
        s.headerStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        s.headerStyle.setWrapText(true);
        setBorders(s.headerStyle, BorderStyle.THIN);

        // Detail Header Style (Sheet 2)
        s.detailHeaderStyle = workbook.createCellStyle();
        s.detailHeaderStyle.setFont(headerFont);
        s.detailHeaderStyle.setFillForegroundColor(new XSSFColor(slateBlue, null));
        s.detailHeaderStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        s.detailHeaderStyle.setAlignment(HorizontalAlignment.CENTER);
        s.detailHeaderStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(s.detailHeaderStyle, BorderStyle.THIN);

        // Data Styles
        XSSFFont dataFont = workbook.createFont();
        dataFont.setFontName("Segoe UI");
        dataFont.setFontHeightInPoints((short) 10);

        s.centerDataStyle = workbook.createCellStyle();
        s.centerDataStyle.setFont(dataFont);
        s.centerDataStyle.setAlignment(HorizontalAlignment.CENTER);
        s.centerDataStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(s.centerDataStyle, BorderStyle.THIN);

        s.leftDataStyle = workbook.createCellStyle();
        s.leftDataStyle.setFont(dataFont);
        s.leftDataStyle.setAlignment(HorizontalAlignment.LEFT);
        s.leftDataStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(s.leftDataStyle, BorderStyle.THIN);

        s.numberDataStyle = workbook.createCellStyle();
        s.numberDataStyle.setFont(dataFont);
        s.numberDataStyle.setDataFormat(createHelper.createDataFormat().getFormat("#,##0"));
        s.numberDataStyle.setAlignment(HorizontalAlignment.RIGHT);
        s.numberDataStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(s.numberDataStyle, BorderStyle.THIN);

        s.decimalDataStyle = workbook.createCellStyle();
        s.decimalDataStyle.setFont(dataFont);
        s.decimalDataStyle.setDataFormat(createHelper.createDataFormat().getFormat("#,##0.0"));
        s.decimalDataStyle.setAlignment(HorizontalAlignment.RIGHT);
        s.decimalDataStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(s.decimalDataStyle, BorderStyle.THIN);

        // Formula Result Styles
        XSSFFont formulaFont = workbook.createFont();
        formulaFont.setFontName("Segoe UI");
        formulaFont.setFontHeightInPoints((short) 10);
        formulaFont.setBold(true);

        s.formulaDecimalStyle = workbook.createCellStyle();
        s.formulaDecimalStyle.setFont(formulaFont);
        s.formulaDecimalStyle.setDataFormat(createHelper.createDataFormat().getFormat("#,##0.00"));
        s.formulaDecimalStyle.setAlignment(HorizontalAlignment.RIGHT);
        s.formulaDecimalStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(s.formulaDecimalStyle, BorderStyle.THIN);

        s.formulaCurrencyStyle = workbook.createCellStyle();
        s.formulaCurrencyStyle.setFont(formulaFont);
        s.formulaCurrencyStyle.setDataFormat(createHelper.createDataFormat().getFormat("#,##0 \"VNĐ\""));
        s.formulaCurrencyStyle.setAlignment(HorizontalAlignment.RIGHT);
        s.formulaCurrencyStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorders(s.formulaCurrencyStyle, BorderStyle.THIN);

        // Total Row Styles
        XSSFFont totalFont = workbook.createFont();
        totalFont.setFontName("Segoe UI");
        totalFont.setFontHeightInPoints((short) 10);
        totalFont.setBold(true);

        s.totalLabelStyle = workbook.createCellStyle();
        s.totalLabelStyle.setFont(totalFont);
        s.totalLabelStyle.setAlignment(HorizontalAlignment.CENTER);
        s.totalLabelStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        s.totalLabelStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.totalLabelStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(s.totalLabelStyle, BorderStyle.MEDIUM);

        s.totalNumberStyle = workbook.createCellStyle();
        s.totalNumberStyle.setFont(totalFont);
        s.totalNumberStyle.setDataFormat(createHelper.createDataFormat().getFormat("#,##0"));
        s.totalNumberStyle.setAlignment(HorizontalAlignment.RIGHT);
        s.totalNumberStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        s.totalNumberStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.totalNumberStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(s.totalNumberStyle, BorderStyle.MEDIUM);

        s.totalDecimalStyle = workbook.createCellStyle();
        s.totalDecimalStyle.setFont(totalFont);
        s.totalDecimalStyle.setDataFormat(createHelper.createDataFormat().getFormat("#,##0.00"));
        s.totalDecimalStyle.setAlignment(HorizontalAlignment.RIGHT);
        s.totalDecimalStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        s.totalDecimalStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.totalDecimalStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(s.totalDecimalStyle, BorderStyle.MEDIUM);

        s.totalCurrencyStyle = workbook.createCellStyle();
        s.totalCurrencyStyle.setFont(totalFont);
        s.totalCurrencyStyle.setDataFormat(createHelper.createDataFormat().getFormat("#,##0 \"VNĐ\""));
        s.totalCurrencyStyle.setAlignment(HorizontalAlignment.RIGHT);
        s.totalCurrencyStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        s.totalCurrencyStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        s.totalCurrencyStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        setBorders(s.totalCurrencyStyle, BorderStyle.MEDIUM);

        // Signatures
        s.signatureHeaderStyle = workbook.createCellStyle();
        s.signatureHeaderStyle.setFont(totalFont);
        s.signatureHeaderStyle.setAlignment(HorizontalAlignment.CENTER);

        XSSFFont sigNoteFont = workbook.createFont();
        sigNoteFont.setFontName("Segoe UI");
        sigNoteFont.setFontHeightInPoints((short) 9);
        sigNoteFont.setItalic(true);

        s.signatureNoteStyle = workbook.createCellStyle();
        s.signatureNoteStyle.setFont(sigNoteFont);
        s.signatureNoteStyle.setAlignment(HorizontalAlignment.CENTER);

        return s;
    }

    private void setBorders(CellStyle style, BorderStyle border) {
        style.setBorderTop(border);
        style.setBorderBottom(border);
        style.setBorderLeft(border);
        style.setBorderRight(border);
    }

    private String getColumnLetter(int colIndex) {
        StringBuilder sb = new StringBuilder();
        while (colIndex >= 0) {
            sb.insert(0, (char) ('A' + (colIndex % 26)));
            colIndex = (colIndex / 26) - 1;
        }
        return sb.toString();
    }

    private String getDayOfWeekName(LocalDate date) {
        switch (date.getDayOfWeek()) {
            case MONDAY: return "Thứ 2";
            case TUESDAY: return "Thứ 3";
            case WEDNESDAY: return "Thứ 4";
            case THURSDAY: return "Thứ 5";
            case FRIDAY: return "Thứ 6";
            case SATURDAY: return "Thứ 7";
            case SUNDAY: return "Chủ Nhật";
            default: return "";
        }
    }

    private String formatShiftTypeName(ShiftType type) {
        if (type == null) return "";
        switch (type) {
            case SHIFT_MORNING: return "Ca Sáng";
            case SHIFT_AFTERNOON: return "Ca Chiều";
            case SHIFT_NIGHT: return "Ca Đêm";
            default: return type.name();
        }
    }

    private String formatShiftStatusName(ShiftStatus status) {
        if (status == null) return "";
        switch (status) {
            case SCHEDULED: return "Đã lên lịch";
            case CHECKED_IN: return "Đang trực";
            case COMPLETED: return "Hoàn thành";
            case ABSENT: return "Vắng mặt";
            case CANCELLED: return "Đã hủy";
            default: return status.name();
        }
    }

    private static class Styles {
        CellStyle titleStyle;
        CellStyle subInfoStyle;
        CellStyle paramHeaderStyle;
        CellStyle paramLabelStyle;
        CellStyle paramCurrencyStyle;
        CellStyle paramDecimalStyle;
        CellStyle headerStyle;
        CellStyle detailHeaderStyle;
        CellStyle centerDataStyle;
        CellStyle leftDataStyle;
        CellStyle numberDataStyle;
        CellStyle decimalDataStyle;
        CellStyle formulaDecimalStyle;
        CellStyle formulaCurrencyStyle;
        CellStyle totalLabelStyle;
        CellStyle totalNumberStyle;
        CellStyle totalDecimalStyle;
        CellStyle totalCurrencyStyle;
        CellStyle signatureHeaderStyle;
        CellStyle signatureNoteStyle;
    }
}
