# Design Specification: Guard Payroll Timesheet Excel Export

- **Date:** 2026-09-26
- **Feature:** Guard Payroll Timesheet Excel Export (`Xuất Excel Báo Cáo Chấm Công & Tính Lương Bảo Vệ`)
- **Author:** Antigravity & User Pair Programming
- **Target Audience:** Facility Manager, Administrator, Accounting / Payroll Department

---

## 1. Overview & Objectives

In campus security operations, duty scheduling and attendance verification must translate directly into monthly payroll processing. The objective of this feature is to allow Facility Managers (FM) and Administrators to export a complete, professional, multi-sheet Excel timesheet (`.xlsx`) directly from the system.

### Key Goals:
1. **Accurate Duty Aggregation:** Correctly categorize worked shifts (Morning, Afternoon, Night, Overtime/Event Dispatch, Substitute duty), leaves, and unexcused absences for each guard.
2. **Accountant-Friendly Dynamic Formulas:** Embed native Excel formulas (`SUM`, unit price multiplications, coefficient scalers) so accountants or managers can modify unit rates or coefficients directly in Excel without touching system code.
3. **Auditability & Traceability:** Include both a high-level summary sheet (Sheet 1) and a detailed per-shift audit log sheet (Sheet 2).
4. **Intuitive Frontend UX:** Provide a clean, accessible button `📊 Xuất Excel Chấm Công` and a modal to select the target month/year and team.

---

## 2. Business Rules & Logic

### 2.1 Shift Weight & Status Rules
- **Completed Shifts:** Shifts with status `COMPLETED` (and `CHECKED_IN` if exporting during an active day) are counted as worked.
- **Normal Shifts (Morning / Afternoon):** Counted as standard shifts ($1.0$ weight).
- **Night Shifts (`SHIFT_NIGHT` 22:00 — 06:00):** Counted as night shifts, eligible for the night shift coefficient ($1.3$ or $1.5$ standard in Vietnamese Labor Law).
- **Overtime Shifts (`isOvertime = true` or Event Dispatch `⚡`):** Counted as overtime shifts, eligible for the overtime coefficient ($1.5$ or $2.0$).
- **Substitute Shifts (`Trực thay`):** When Guard A substitutes for Guard B on leave, the shift record belongs to Guard A. The duty credit is awarded to Guard A (the person who actually worked).
- **Approved Leaves (`Nghỉ phép`):** Counted from approved `GuardShiftRequest` (type `LEAVE_REQUEST`, status `APPROVED`) in the target month.
- **Unexcused Absences (`Vắng mặt`):** Counted from `GuardShift` with status `ABSENT`.

### 2.2 Dynamic Excel Formulas in Sheet 1
The Summary Sheet will include a top-level **Parameters Box** (Ô Tham Số):
- Cell `B3`: Đơn giá ca chuẩn (VNĐ) - Default: `300,000`
- Cell `B4`: Hệ số phụ cấp ca đêm - Default: `1.30`
- Cell `B5`: Hệ số ca tăng cường / OT - Default: `1.50`

For each guard row $i$ in the table:
- **Tổng công quy đổi (Converted Standard Shifts):**
  $$\text{Công quy đổi}_i = (\text{Ca Sáng}_i + \text{Ca Chiều}_i + \text{Trực thay}_i) \times 1.0 + (\text{Ca Đêm}_i \times \$B\$4) + (\text{Ca OT}_i \times \$B\$5)$$
- **Lương dự tính (Estimated Salary VNĐ):**
  $$\text{Lương dự tính}_i = \text{Công quy đổi}_i \times \$B\$3$$
- **Bottom Summary Row:** `=SUM(...)` for all numeric columns.

---

## 3. Excel Workbook Structure (`.xlsx`)

The file is generated using **Apache POI (`poi-ooxml`)**.

### 3.1 Sheet 1: "Tổng Hợp Chấm Công & Lương"
1. **Title Banner:**
   - Text: `BẢNG TỔNG HỢP CHẤM CÔNG VÀ DỰ TÍNH LƯƠNG BẢO VỆ - THÁNG MM/YYYY`
   - Bold, Font 16pt, Center merged across columns A to N.
2. **Metadata & Settings Area:**
   - Ngày xuất báo cáo: `dd/MM/yyyy HH:mm`
   - Đội bảo vệ: `Tất cả các đội` hoặc tên đội cụ thể.
   - Bảng tham số (Đơn giá ca chuẩn, Hệ số đêm, Hệ số OT) với viền mỏng và nền màu kem nhạt `#FFFBEB`.
3. **Main Data Table:**
   - **Header Row:** Nền xanh Navy `#1E3A8A`, chữ trắng in đậm, căn giữa.
   - **Columns:**
     1. `STT` (No.)
     2. `Mã NV` (User Code)
     3. `Họ và Tên` (Full Name)
     4. `Số Điện Thoại` (Phone)
     5. `Đội Bảo Vệ` (Team Name)
     6. `Ca Sáng (công)`
     7. `Ca Chiều (công)`
     8. `Ca Đêm (công)`
     9. `Ca OT / Tăng Cường (công)`
     10. `Trực Thay (công)`
     11. `Nghỉ Phép (ngày)`
     12. `Vắng Mặt (ngày)`
     13. `Tổng Giờ Làm (giờ)`
     14. `Tổng Công Quy Đổi` *(Formula)*
     15. `Lương Dự Tính (VNĐ)` *(Formula, formatted as `#,##0 "VNĐ"`)*
4. **Footer Row:**
   - Dòng `TỔNG CỘNG`: Tính tổng các cột số lượng và tổng quỹ lương bằng hàm `=SUM(...)`.
5. **Sign-off Area:**
   - Người Lập Bảng | Đội Trưởng Bảo Vệ | Trưởng Phòng QLVH / Kế Toán.

### 3.2 Sheet 2: "Chi Tiết Ca Trực"
1. **Header Row:** Nền xanh Slate `#334155`, chữ trắng in đậm.
2. **Columns:**
   1. `STT`
   2. `Ngày Trực` (`dd/MM/yyyy`)
   3. `Thứ` (Thứ 2, Thứ 3, ..., Chủ Nhật)
   4. `Mã NV`
   5. `Họ và Tên`
   6. `Đội Trực`
   7. `Loại Ca` (Ca Sáng, Ca Chiều, Ca Đêm)
   8. `Giờ Kế Hoạch` (`06:00 - 14:00`, v.v.)
   9. `Khu Vực / Tòa Nhà`
   10. `Giờ Check-in Thực Tế` (`HH:mm:ss`)
   11. `Giờ Check-out Thực Tế` (`HH:mm:ss`)
   12. `Trạng Thái Ca` (Hoàn thành, Đang trực, Vắng mặt, Lên lịch)
   13. `Tăng Cường (OT)?` (Có / Không)
   14. `Ghi Chú` (Trực thay, Đổi ca, v.v.)

---

## 4. Backend Architecture & API

### 4.1 Endpoint
- **URL:** `GET /api/guard-shifts/export-timesheet`
- **Security:** `@PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER')")`
- **Query Parameters:**
  - `year` (`Integer`, required, e.g. `2026`)
  - `month` (`Integer`, required, e.g. `9`)
  - `teamId` (`UUID`, optional, e.g. filter for specific team)
- **Response:**
  - Content-Type: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`
  - Header: `Content-Disposition: attachment; filename="Bang_Cham_Cong_Bao_Ve_Thang_{month}_{year}.xlsx"`
  - Body: Binary `.xlsx` stream (`byte[]` / `ByteArrayResource`).

### 4.2 Service Layer
- **New Service:** `GuardShiftExportService`
  - Injected with `GuardShiftRepository`, `GuardShiftRequestRepository`, `UserRepository`, `GuardTeamRepository`.
  - Step 1: Query all guards (filtered by `teamId` if specified, role `GUARD`).
  - Step 2: Query all shifts in date range `[year-month-01, year-month-lastDay]`.
  - Step 3: Query all approved leave requests in date range.
  - Step 4: Build POI `XSSFWorkbook`, create fonts, cell styles (Header, Data, Currency, Numeric, Border).
  - Step 5: Populate Sheet 1 (Summary) with formulas and Sheet 2 (Details).
  - Step 6: Apply `trackAllColumnsForAutoSizing()` / `sheet.autoSizeColumn()` for professional layout.
  - Step 7: Write to `ByteArrayOutputStream` and return `byte[]`.

---

## 5. Frontend UI/UX Design

### 5.1 Trigger Button
- Location: In `GuardTeamManagementPage.jsx`, inside the command toolbar next to `Phân công theo mẫu` and `Tạo lịch tự động`.
- Button:
  ```jsx
  <button
    onClick={() => setShowExportModal(true)}
    className="schedule-btn-secondary text-emerald-700 dark:text-emerald-400 border-emerald-300 hover:bg-emerald-50 dark:hover:bg-emerald-950/40"
    title="Xuất bảng tổng hợp chấm công và tính lương ra file Excel"
  >
    <FileSpreadsheet size={16} className="text-emerald-600 dark:text-emerald-400" />
    <span>Xuất Excel Chấm Công</span>
  </button>
  ```

### 5.2 Modal (`ExportTimesheetModal.jsx`)
- Compact dialog containing:
  1. **Tiêu đề:** `Xuất Bảng Chấm Công & Tính Lương (.xlsx)`
  2. **Chọn Tháng / Năm:** `<input type="month">` or dual dropdown Month / Year (defaults to month of current schedule date).
  3. **Chọn Đội Bảo Vệ:** `<select>` with options:
     - `Tất cả các đội`
     - `<Tên đội>` (Đội 1 - Sáng, Đội 2 - Chiều, ...)
  4. **Nút Hành Động:**
     - `Hủy`
     - `Tải File Excel` (displays spinner and `Đang xuất file...` while downloading).
- Uses `apiClient` or `fetch` with `responseType: 'blob'`, dynamically triggers browser file download.

---

## 6. Error Handling & Edge Cases
1. **No shifts in selected month:** Generates empty table template with headers and zero counts without throwing error.
2. **Guards with 0 shifts:** Included in Sheet 1 with 0 counts so management knows they had no shifts assigned.
3. **Check-in with missing check-out:** Handles null `checkOutAt` gracefully, displaying `Chưa check-out` or standard planned shift duration.
4. **UTF-8 Vietnamese characters:** Fully supported natively in Apache POI `XSSFWorkbook` (OpenXML standard).

---

## 7. Verification Plan
1. Backend test: Unit test / Integration test validating `.xlsx` stream generation with 2 sheets.
2. API test: Call endpoint with query params and verify `200 OK` + valid `Content-Disposition`.
3. Excel verification: Open exported file in Microsoft Excel / LibreOffice, check that:
   - Both sheets exist and are styled cleanly.
   - Formulas in `Tổng công quy đổi` and `Lương dự tính` work and recalculate when unit price cell is edited.
4. Frontend test: Click `Xuất Excel Chấm Công`, select month & team, verify file downloads properly.
