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
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GuardShiftExportServiceTest {

    @Mock
    private GuardShiftRepository guardShiftRepository;

    @Mock
    private GuardShiftRequestRepository guardShiftRequestRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private GuardTeamRepository guardTeamRepository;

    @InjectMocks
    private GuardShiftExportService guardShiftExportService;

    private User guard1;
    private GuardTeam team1;

    @BeforeEach
    void setUp() {
        team1 = GuardTeam.builder()
                .id(UUID.randomUUID())
                .teamName("Đội 1 (Ca Sáng)")
                .description("Phụ trách Tòa Alpha")
                .isActive(true)
                .build();

        guard1 = User.builder()
                .id(UUID.randomUUID())
                .fullName("Trần Quốc Bảo")
                .userCode("GU002")
                .email("bao.tran@campus.fa26.edu.vn")
                .role(Role.GUARD)
                .team(team1)
                .isActive(true)
                .build();
    }

    @Test
    @DisplayName("Should generate valid multi-sheet Excel workbook with correct summary formulas")
    void testExportMonthlyTimesheet_Success() throws IOException {
        // Arrange
        LocalDate date1 = LocalDate.of(2026, 9, 10);
        LocalDate date2 = LocalDate.of(2026, 9, 15);
        LocalDate date3 = LocalDate.of(2026, 9, 20);
        LocalDate date4 = LocalDate.of(2026, 9, 25);

        GuardShift morningShift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard1)
                .shiftDate(date1)
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .status(ShiftStatus.COMPLETED)
                .build();

        GuardShift nightShift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard1)
                .shiftDate(date2)
                .shiftType(ShiftType.SHIFT_NIGHT)
                .startTime(LocalTime.of(22, 0))
                .endTime(LocalTime.of(6, 0))
                .status(ShiftStatus.COMPLETED)
                .build();

        GuardShift otShift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard1)
                .shiftDate(date3)
                .shiftType(ShiftType.SHIFT_AFTERNOON)
                .startTime(LocalTime.of(14, 0))
                .endTime(LocalTime.of(22, 0))
                .isOvertime(true)
                .status(ShiftStatus.COMPLETED)
                .build();

        GuardShift substituteShift = GuardShift.builder()
                .id(UUID.randomUUID())
                .guard(guard1)
                .shiftDate(date4)
                .shiftType(ShiftType.SHIFT_MORNING)
                .startTime(LocalTime.of(6, 0))
                .endTime(LocalTime.of(14, 0))
                .notes("Trực thay do Nguyễn Văn An nghỉ phép")
                .status(ShiftStatus.COMPLETED)
                .build();

        List<GuardShift> shifts = List.of(morningShift, nightShift, otShift, substituteShift);

        when(userRepository.findActiveUsersByRole(Role.GUARD)).thenReturn(List.of(guard1));
        when(guardShiftRepository.findByShiftDateBetween(any(), any())).thenReturn(shifts);
        when(guardShiftRequestRepository.findRequests(any(), any(), any(), any())).thenReturn(Collections.emptyList());

        // Act
        byte[] excelBytes = guardShiftExportService.exportMonthlyTimesheet(2026, 9, null);

        // Assert
        assertNotNull(excelBytes);
        assertTrue(excelBytes.length > 0);

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(excelBytes))) {
            assertEquals(2, workbook.getNumberOfSheets());

            // 1. Verify Sheet 1: Summary
            Sheet summarySheet = workbook.getSheetAt(0);
            assertEquals("Tổng Hợp Chấm Công & Lương", summarySheet.getSheetName());

            // Verify title banner
            Row titleRow = summarySheet.getRow(0);
            assertNotNull(titleRow);
            assertTrue(titleRow.getCell(0).getStringCellValue().contains("THÁNG 09/2026"));

            // Verify Table Header at row 8 (0-indexed)
            Row headerRow = summarySheet.getRow(8);
            assertNotNull(headerRow);
            assertEquals("STT", headerRow.getCell(0).getStringCellValue());
            assertEquals("Mã NV", headerRow.getCell(1).getStringCellValue());
            assertEquals("Họ và Tên", headerRow.getCell(2).getStringCellValue());
            assertEquals("Tổng Công Quy Đổi", headerRow.getCell(13).getStringCellValue());
            assertEquals("Lương Dự Tính (VNĐ)", headerRow.getCell(14).getStringCellValue());

            // Verify Data Row at row 9 (0-indexed)
            Row dataRow = summarySheet.getRow(9);
            assertNotNull(dataRow);
            assertEquals(1, (int) dataRow.getCell(0).getNumericCellValue());
            assertEquals("GU002", dataRow.getCell(1).getStringCellValue());
            assertEquals("Trần Quốc Bảo", dataRow.getCell(2).getStringCellValue());

            // Ca Sáng: 1, Ca Chiều: 0, Ca Đêm: 1, Ca OT: 1, Trực Thay: 1
            assertEquals(1, (int) dataRow.getCell(5).getNumericCellValue()); // Ca Sáng
            assertEquals(0, (int) dataRow.getCell(6).getNumericCellValue()); // Ca Chiều
            assertEquals(1, (int) dataRow.getCell(7).getNumericCellValue()); // Ca Đêm
            assertEquals(1, (int) dataRow.getCell(8).getNumericCellValue()); // Ca OT
            assertEquals(1, (int) dataRow.getCell(9).getNumericCellValue()); // Trực Thay

            // Formula checks
            assertNotNull(dataRow.getCell(13).getCellFormula());
            assertTrue(dataRow.getCell(13).getCellFormula().contains("$C$6")); // Night multiplier cell
            assertTrue(dataRow.getCell(13).getCellFormula().contains("$C$7")); // OT multiplier cell

            assertNotNull(dataRow.getCell(14).getCellFormula());
            assertTrue(dataRow.getCell(14).getCellFormula().contains("$C$5")); // Unit price cell

            // 2. Verify Sheet 2: Details
            Sheet detailSheet = workbook.getSheetAt(1);
            assertEquals("Chi Tiết Ca Trực", detailSheet.getSheetName());

            Row detailHeader = detailSheet.getRow(3);
            assertNotNull(detailHeader);
            assertEquals("Ngày Trực", detailHeader.getCell(1).getStringCellValue());

            // Verify 4 data rows in detail sheet
            assertNotNull(detailSheet.getRow(4));
            assertNotNull(detailSheet.getRow(7));
        }
    }
}
