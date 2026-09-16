# Báo Cáo Khảo Sát & Đánh Giá Rủi Ro: `--theme-primary`

> **Tình trạng:** Khảo sát chi tiết — **TUYỆT ĐỐI KHÔNG SỬA CODE** theo quy định.  
> **Mục tiêu:** Rà soát toàn bộ các vị trí đang phụ thuộc vào hệ màu xanh dương cũ (`--theme-primary` trỏ về `#2563eb` light / `#3b82f6` dark), phân loại rủi ro khi chuyển đổi sang hệ cyan-tím của Design System.  
> **Ngày thực hiện:** 16/09/2026

---

## 1. Tổng Quan Khảo Sát & Xác Minh Baseline

- **Tổng số vị trí phát hiện chính xác (Baseline đã chuẩn hóa):** **63 vị trí** trên 6 file (xác minh bằng `grep -rn "theme-primary" frontend/src`).
- **Giải trình độ lệch 7 vị trí:** Bản thảo sơ bộ trước đây ghi nhận 56 vị trí do gộp theo cụm selector (AreaListPage đếm 24 cụm thay vì 29 dòng thuộc tính; ManageAccountPage đếm 17 cụm thay vì 19 dòng thuộc tính; chênh đúng 5 + 2 = 7). Bảng chi tiết mục 2 và phân loại rủi ro mục 3 (13 Cao + 41 Trung + 9 Thấp = 63) đã phản ánh đầy đủ 63 dòng thuộc tính độc lập.
- **Phân bố thực tế theo file:**
  1. `frontend/src/styles/theme.css`: 10 vị trí (khai báo biến token gốc cho Light và Dark mode).
  2. `frontend/src/styles/AreaListPage.css`: 29 vị trí (bản đồ, sidebar, toolbar, floor tabs, buttons, empty states).
  3. `frontend/src/styles/ManageAccountPage.css`: 19 vị trí (tab active, table row hover, buttons, dropzone, pagination).
  4. `frontend/src/pages/accounts/ManageAccountPage.jsx`: 3 vị trí (inline styles cho preview avatar, link thay ảnh, dropzone ZIP).
  5. `frontend/src/styles/AreaCameraManagementPage.css`: 1 vị trí (icon header camera unassigned).
  6. `frontend/src/styles/CameraDetailPage.css`: 1 vị trí (icon trợ giúp khi hover).

---

## 2. Chi Tiết 63 Vị Trí Sử Dụng `--theme-primary*`

| STT | File | Dòng | Selector / Vị trí | Thuộc tính | Phần tử giao diện | Trạng thái hiển thị | Mức rủi ro |
|---|---|---|---|---|---|---|---|
| 1 | `theme.css` | 32 | `:root` (Light mode) | `--theme-primary: #2563eb` | Token định nghĩa gốc | Toàn hệ thống | **CAO** |
| 2 | `theme.css` | 33 | `:root` (Light mode) | `--theme-primary-hover: #1d4ed8` | Token hover gốc | Khi hover | **CAO** |
| 3 | `theme.css` | 34 | `:root` (Light mode) | `--theme-primary-light: #eff6ff` | Token nền nhạt gốc | Nền/tag/badge | **CAO** |
| 4 | `theme.css` | 35 | `:root` (Light mode) | `--theme-primary-subtle: rgba(...)` | Token viền/nền mờ | Focus/hover | **CAO** |
| 5 | `theme.css` | 36 | `:root` (Light mode) | `--theme-primary-border: #bfdbfe` | Token viền gốc | Viền khối | **CAO** |
| 6 | `theme.css` | 126 | `[data-theme="dark"]` | `--theme-primary: #3b82f6` | Token định nghĩa gốc | Toàn hệ thống | **CAO** |
| 7 | `theme.css` | 127 | `[data-theme="dark"]` | `--theme-primary-hover: #2563eb` | Token hover gốc | Khi hover | **CAO** |
| 8 | `theme.css` | 128 | `[data-theme="dark"]` | `--theme-primary-light: rgba(...)` | Token nền nhạt gốc | Nền/tag/badge | **CAO** |
| 9 | `theme.css` | 129 | `[data-theme="dark"]` | `--theme-primary-subtle: rgba(...)` | Token viền/nền mờ | Focus/hover | **CAO** |
| 10 | `theme.css` | 130 | `[data-theme="dark"]` | `--theme-primary-border: rgba(...)` | Token viền gốc | Viền khối | **CAO** |
| 11 | `AreaCameraManagementPage.css` | 195 | `.transfer-box-header__icon` | `color` | Icon header hộp danh sách camera chưa gán | Mặc định | **THẤP** |
| 12 | `CameraDetailPage.css` | 569 | `.help-icon-wrapper:hover .help-icon` | `color` | Icon dấu hỏi trợ giúp bộ lọc | Khi hover icon wrapper | **TRUNG** |
| 13 | `ManageAccountPage.jsx` | 1032 | Inline style `<img>` frontPreview | `border` | Viền ảnh xem trước khuôn mặt nhân viên | Khi đã có ảnh xem trước | **TRUNG** |
| 14 | `ManageAccountPage.jsx` | 1040 | Inline style `<label>` thay ảnh | `color` | Nút văn bản "Chọn ảnh khác" | Khi đã có ảnh xem trước | **TRUNG** |
| 15 | `ManageAccountPage.jsx` | 1368 | Inline style `<label>` bulk ZIP | `border` | Khung viền vùng kéo thả file ZIP hàng loạt | Khi đã chọn file ZIP | **CAO** |
| 16 | `ManageAccountPage.css` | 153 | `.account-tab-btn--active` | `color` | Chữ của tab đang chọn (All, Staff, Student) | Khi tab active/selected | **TRUNG** |
| 17 | `ManageAccountPage.css` | 154 | `.account-tab-btn--active` | `border-bottom-color` | Gạch chân chỉ báo tab đang chọn | Khi tab active/selected | **TRUNG** |
| 18 | `ManageAccountPage.css` | 174 | `.account-tab-btn--active .account-tab-badge` | `color` | Chữ badge số lượng trên tab đang chọn | Khi tab active/selected | **TRUNG** |
| 19 | `ManageAccountPage.css` | 337 | `.account-table tbody tr:hover` | `background-color` | Màu nền dòng bảng tài khoản khi rê chuột | Khi hover vào dòng bảng | **TRUNG** |
| 20 | `ManageAccountPage.css` | 520 | `.account-action-btn:hover` | `color` | Màu icon thao tác bảng khi rê chuột | Khi hover vào nút thao tác | **TRUNG** |
| 21 | `ManageAccountPage.css` | 668 | `.account-page-btn--active` | `background-color` | Nền nút số trang đang chọn | Khi trang đang active | **TRUNG** |
| 22 | `ManageAccountPage.css` | 670 | `.account-page-btn--active` | `border-color` | Viền nút số trang đang chọn | Khi trang đang active | **TRUNG** |
| 23 | `ManageAccountPage.css` | 860 | `.account-dropzone:hover, .account-dropzone--active` | `border-color` | Viền khung kéo thả ảnh khuôn mặt đơn lẻ | Khi hover hoặc kéo file vào | **CAO** |
| 24 | `ManageAccountPage.css` | 865 | `.account-dropzone__icon` | `color` | Icon đám mây upload trong vùng kéo thả ảnh | Khi hover hoặc kéo file vào | **CAO** |
| 25 | `ManageAccountPage.css` | 1280 | `.account-modal-btn--primary` | `background-color` | Nền nút Lưu/Xác nhận trong modal tạo/sửa | Mặc định | **TRUNG** |
| 26 | `ManageAccountPage.css` | 1285 | `.account-modal-btn--primary:hover:not(:disabled)` | `background-color` | Nền nút Lưu/Xác nhận khi rê chuột | Khi hover | **TRUNG** |
| 27 | `ManageAccountPage.css` | 1442 | `.account-bulk-template-icon` | `color` | Icon file mẫu Excel trong modal import | Mặc định | **THẤP** |
| 28 | `ManageAccountPage.css` | 1467 | `.account-bulk-download-btn` | `color` | Chữ nút "Tải file mẫu" | Mặc định | **TRUNG** |
| 29 | `ManageAccountPage.css` | 1468 | `.account-bulk-download-btn` | `background-color` | Nền nút "Tải file mẫu" | Mặc định | **TRUNG** |
| 30 | `ManageAccountPage.css` | 1469 | `.account-bulk-download-btn` | `border` | Viền nút "Tải file mẫu" | Mặc định | **TRUNG** |
| 31 | `ManageAccountPage.css` | 1477 | `.account-bulk-download-btn:hover` | `background-color` | Nền nút "Tải file mẫu" khi rê chuột | Khi hover | **TRUNG** |
| 32 | `ManageAccountPage.css` | 1478 | `.account-bulk-download-btn:hover` | `border-color` | Viền nút "Tải file mẫu" khi rê chuột | Khi hover | **TRUNG** |
| 33 | `ManageAccountPage.css` | 1553 | `.account-bulk-pill--active` | `background-color` | Nền pill lọc trạng thái (Hợp lệ/Lỗi) | Khi active/selected | **TRUNG** |
| 34 | `ManageAccountPage.css` | 1554 | `.account-bulk-pill--active` | `border-color` | Viền pill lọc trạng thái (Hợp lệ/Lỗi) | Khi active/selected | **TRUNG** |
| 35 | `AreaListPage.css` | 38 | `.area-ambient__orb--1` | `background` | Quầng sáng gradient trang trí nền bản đồ | Mặc định (hiệu ứng nền) | **THẤP** |
| 36 | `AreaListPage.css` | 132 | `.area-sidebar__nav-item:hover` | `background` | Nền mục menu sidebar khi rê chuột | Khi hover | **TRUNG** |
| 37 | `AreaListPage.css` | 133 | `.area-sidebar__nav-item:hover` | `color` | Chữ mục menu sidebar khi rê chuột | Khi hover | **TRUNG** |
| 38 | `AreaListPage.css` | 134 | `.area-sidebar__nav-item:hover` | `border-color` | Viền mục menu sidebar khi rê chuột | Khi hover | **TRUNG** |
| 39 | `AreaListPage.css` | 138 | `.area-sidebar__nav-item--active` | `background` | Nền mục menu sidebar đang chọn | Khi active/selected | **TRUNG** |
| 40 | `AreaListPage.css` | 139 | `.area-sidebar__nav-item--active` | `color` | Chữ mục menu sidebar đang chọn | Khi active/selected | **TRUNG** |
| 41 | `AreaListPage.css` | 140 | `.area-sidebar__nav-item--active` | `border-color` | Viền mục menu sidebar đang chọn | Khi active/selected | **TRUNG** |
| 42 | `AreaListPage.css` | 145 | `.area-sidebar__nav-item--active svg` | `color` | Icon mục menu sidebar đang chọn | Khi active/selected | **TRUNG** |
| 43 | `AreaListPage.css` | 213 | `.area-sidebar__user-role` | `color` | Nhãn phân quyền người dùng góc dưới sidebar | Mặc định | **THẤP** |
| 44 | `AreaListPage.css` | 337 | `.area-floor-tab--active` | `background` | Nền tab tầng đang kích hoạt | Khi tab tầng active | **TRUNG** |
| 45 | `AreaListPage.css` | 338 | `.area-floor-tab--active` | `border-color` | Viền tab tầng đang kích hoạt | Khi tab tầng active | **TRUNG** |
| 46 | `AreaListPage.css` | 356 | `.zone-toolbar__add, .area-toolbar__create-btn` | `background` | Nền nút "Thêm khu vực" trên thanh công cụ | Mặc định | **TRUNG** |
| 47 | `AreaListPage.css` | 368 | `.zone-toolbar__add:hover, .area-toolbar__create-btn:hover` | `background` | Nền nút "Thêm khu vực" khi rê chuột | Khi hover | **TRUNG** |
| 48 | `AreaListPage.css` | 401 | `.area-control-btn:hover` | `background` | Nền nút điều khiển zoom/xoay bản đồ khi rê chuột | Khi hover | **TRUNG** |
| 49 | `AreaListPage.css` | 402 | `.area-control-btn:hover` | `border-color` | Viền nút điều khiển zoom/xoay bản đồ khi rê chuột | Khi hover | **TRUNG** |
| 50 | `AreaListPage.css` | 403 | `.area-control-btn:hover` | `color` | Icon nút điều khiển zoom/xoay bản đồ khi rê chuột | Khi hover | **TRUNG** |
| 51 | `AreaListPage.css` | 588 | `.area-empty-state__icon` | `background` | Nền khối icon khi tầng chưa có khu vực nào | Mặc định (khi trống dữ liệu) | **THẤP** |
| 52 | `AreaListPage.css` | 592 | `.area-empty-state__icon` | `color` | Icon cảnh báo khi tầng chưa có khu vực nào | Mặc định (khi trống dữ liệu) | **THẤP** |
| 53 | `AreaListPage.css` | 717 | `.zone-details__code` | `color` | Mã code khu vực hiển thị trong drawer chi tiết | Mặc định | **THẤP** |
| 54 | `AreaListPage.css` | 780 | `.zone-btn--primary` | `background` | Nền nút hành động chính trong drawer khu vực | Mặc định | **TRUNG** |
| 55 | `AreaListPage.css` | 786 | `.zone-btn--primary:hover` | `background` | Nền nút hành động chính trong drawer khi hover | Khi hover | **TRUNG** |
| 56 | `AreaListPage.css` | 1885 | `.zone-canvas-empty__switch-btn` | `color` | Chữ nút chuyển tầng khi canvas trống | Mặc định | **THẤP** |
| 57 | `AreaListPage.css` | 2014 | `.zone-rail-item--selected` | `background` | Nền item khu vực được chọn trong thanh trượt | Khi selected | **TRUNG** |
| 58 | `AreaListPage.css` | 2015 | `.zone-rail-item--selected` | `border-color` | Viền item khu vực được chọn trong thanh trượt | Khi selected | **TRUNG** |
| 59 | `AreaListPage.css` | 2191 | `.zone-btn-action--primary` | `background` | Nền nút thao tác khu vực phụ | Mặc định | **TRUNG** |
| 60 | `AreaListPage.css` | 2192 | `.zone-btn-action--primary` | `border-color` | Viền nút thao tác khu vực phụ | Mặc định | **TRUNG** |
| 61 | `AreaListPage.css` | 2193 | `.zone-btn-action--primary` | `color` | Chữ nút thao tác khu vực phụ | Mặc định | **TRUNG** |
| 62 | `AreaListPage.css` | 2197 | `.zone-btn-action--primary:hover` | `background` | Nền nút thao tác khu vực phụ khi hover | Khi hover | **TRUNG** |
| 63 | `AreaListPage.css` | 2198 | `.zone-btn-action--primary:hover` | `border-color` | Viền nút thao tác khu vực phụ khi hover | Khi hover | **TRUNG** |

---

## 3. Phân Nhóm Theo Mức Độ Rủi Ro

### 3.1. RỦI RO CAO (13 vị trí)
Gồm các token gốc hệ thống và các vùng tương tác phức tạp, dùng chung hoặc khó phát hiện khi vỡ giao diện:
- **10 vị trí khai báo token trong `theme.css`:** Nếu đổi trực tiếp giá trị `--theme-primary` mà không rà soát các trạng thái phái sinh (`-hover`, `-light`, `-subtle`, `-border`), toàn bộ ứng dụng sẽ bị ảnh hưởng hàng loạt.
- **3 vị trí Drag & Drop Dropzone:**
  - `ManageAccountPage.jsx` dòng 1368 (viền dropzone file ZIP hàng loạt, đang dùng cả hex viết cứng `#3b82f6` và `#eff6ff`).
  - `ManageAccountPage.css` dòng 860, 865 (viền và icon vùng upload ảnh đơn lẻ khi active/dragover).

### 3.2. RỦI RO TRUNG BÌNH (41 vị trí)
Gồm các trạng thái tương tác phổ biến, tái hiện rõ ràng bằng thao tác chuột hoặc chọn tab:
- **Tab navigation & Badge active:** `ManageAccountPage.css` (L153, L154, L174), `AreaListPage.css` (L337, L338, L138-145).
- **Các nút Primary & Hover states:** Các nút "Thêm khu vực", nút Lưu modal, nút tải file mẫu (`ManageAccountPage.css` L1280, L1285, L1467-1478, `AreaListPage.css` L356, L368, L780, L786).
- **Pagination active:** Nút phân trang đang chọn (`ManageAccountPage.css` L668, L670).
- **Table row hover & Rail selected:** Dòng bảng khi hover (`ManageAccountPage.css` L337), mục danh sách rail khi chọn (`AreaListPage.css` L2014, L2015).
- **Preview avatar & Change photo action:** `ManageAccountPage.jsx` L1032, L1040.

### 3.3. RỦI RO THẤP (9 vị trí)
Chỉ hiện ở một trạng thái tĩnh, dễ quan sát bằng mắt thường, ít liên đới logic:
- `AreaCameraManagementPage.css` L195: Icon header `.transfer-box-header__icon`.
- `AreaListPage.css` L38: Quầng sáng trang trí `.area-ambient__orb--1`.
- `AreaListPage.css` L213: Nhãn vai trò người dùng `.area-sidebar__user-role`.
- `AreaListPage.css` L588, L592: Icon rỗng dữ liệu `.area-empty-state__icon`.
- `AreaListPage.css` L717: Mã code khu vực `.zone-details__code`.
- `AreaListPage.css` L1885: Chữ nút chuyển tầng `.zone-canvas-empty__switch-btn`.
- `ManageAccountPage.css` L1442: Icon template download `.account-bulk-template-icon`.

---

## 3.4. Phân Loại Toàn Bộ Vị Trí Theo VAI TRÒ (Functional Roles)

Nhằm phục vụ lộ trình tách và thay thế chuẩn hóa, toàn bộ các vị trí được phân nhóm theo 5 vai trò chức năng:

| STT | File | Dòng | Selector / Phần tử | Thuộc tính | Vai trò chức năng | Token đích |
|---|---|---|---|---|---|---|
| 1-10 | `theme.css` | 32-36, 141-145 | `:root` & `[data-theme="dark"]` | `--theme-primary*` | Định nghĩa token hệ thống | Sẽ deprecated sau khi dọn xong |
| 11 | `AreaCameraManagementPage.css` | 195 | `.transfer-box-header__icon` | `color` | Chữ / icon / spinner | `--brand-text` |
| 12 | `CameraDetailPage.css` | 575 | `.help-icon-wrapper:hover .help-icon` | `color` | Chữ / icon / spinner | `--brand-text` |
| 13 | `ManageAccountPage.jsx` | 1031 | Inline style preview avatar | `border` | Viền / đường kẻ | `--brand-subtle-border` |
| 14 | `ManageAccountPage.jsx` | 1039 | Inline style link thay ảnh | `color` | Chữ / icon / spinner | `--brand-text` |
| 15 | `ManageAccountPage.jsx` | 1367 | Inline style bulk zip dropzone | `border` | Viền / đường kẻ | `--brand-subtle-border` |
| 16 | `ManageAccountPage.css` | 153 | `.account-tab-btn--active` | `color` | Chữ / icon / spinner | `--brand-text` |
| 17 | `ManageAccountPage.css` | 154 | `.account-tab-btn--active` | `border-bottom-color` | Viền / đường kẻ | `--brand-subtle-border` |
| 18 | `ManageAccountPage.css` | 174 | `.account-tab-btn--active .account-tab-badge` | `color` | Chữ / icon / spinner | `--brand-text` |
| 19 | `ManageAccountPage.css` | 337 | `.account-table tbody tr:hover` | `background-color` | Nền mờ / hover / selected | `--brand-subtle` |
| 20 | `ManageAccountPage.css` | 508 | `.account-action-btn:hover` | `color` | Chữ / icon / spinner | `--brand-text` |
| 21 | `ManageAccountPage.css` | 656 | `.account-page-btn--active` | `background-color` | Nền solid (nút chính) | `--brand-* + --theme-on-brand` |
| 22 | `ManageAccountPage.css` | 658 | `.account-page-btn--active` | `border-color` | Viền / đường kẻ | `--brand-subtle-border` |
| 23 | `ManageAccountPage.css` | 848 | `.account-dropzone:hover, --active` | `border-color` | Viền / đường kẻ | `--brand-subtle-border` |
| 24 | `ManageAccountPage.css` | 853 | `.account-dropzone__icon` | `color` | Chữ / icon / spinner | `--brand-text` |
| 25 | `ManageAccountPage.css` | 1268 | `.account-modal-btn--primary` | `background-color` | Nền solid (nút chính) | `--brand-* + --theme-on-brand` |
| 26 | `ManageAccountPage.css` | 1273 | `.account-modal-btn--primary:hover` | `background-color` | Nền solid (nút chính) | `--brand-* + --theme-on-brand` |
| 27 | `ManageAccountPage.css` | 1430 | `.account-bulk-template-icon` | `color` | Chữ / icon / spinner | `--brand-text` |
| 28 | `ManageAccountPage.css` | 1455 | `.account-bulk-download-btn` | `color` | Chữ / icon / spinner | `--brand-text` |
| 29 | `ManageAccountPage.css` | 1456 | `.account-bulk-download-btn` | `background-color` | Nền mờ / hover / selected | `--brand-subtle` |
| 30 | `ManageAccountPage.css` | 1457 | `.account-bulk-download-btn` | `border` | Viền / đường kẻ | `--brand-subtle-border` |
| 31 | `ManageAccountPage.css` | 1465 | `.account-bulk-download-btn:hover` | `background-color` | Nền mờ / hover / selected | `--brand-subtle` |
| 32 | `ManageAccountPage.css` | 1466 | `.account-bulk-download-btn:hover` | `border-color` | Viền / đường kẻ | `--brand-subtle-border` |
| 33 | `ManageAccountPage.css` | 1541 | `.account-bulk-pill--active` | `background-color` | Nền solid (nút chính) | `--brand-* + --theme-on-brand` |
| 34 | `ManageAccountPage.css` | 1542 | `.account-bulk-pill--active` | `border-color` | Viền / đường kẻ | `--brand-subtle-border` |
| 35 | `AreaListPage.css` | 38 | `.area-ambient__orb--1` | `background` | Nền mờ / hover / selected | `--brand-subtle` |
| 36 | `AreaListPage.css` | 132 | `.area-sidebar__nav-item:hover` | `background` | Nền mờ / hover / selected | `--brand-subtle` |
| 37 | `AreaListPage.css` | 133 | `.area-sidebar__nav-item:hover` | `color` | Chữ / icon / spinner | `--brand-text` |
| 38 | `AreaListPage.css` | 134 | `.area-sidebar__nav-item:hover` | `border-color` | Viền / đường kẻ | `--brand-subtle-border` |
| 39 | `AreaListPage.css` | 138 | `.area-sidebar__nav-item--active` | `background` | Nền mờ / hover / selected | `--brand-subtle` |
| 40 | `AreaListPage.css` | 139 | `.area-sidebar__nav-item--active` | `color` | Chữ / icon / spinner | `--brand-text` |
| 41 | `AreaListPage.css` | 140 | `.area-sidebar__nav-item--active` | `border-color` | Viền / đường kẻ | `--brand-subtle-border` |
| 42 | `AreaListPage.css` | 145 | `.area-sidebar__nav-item--active svg` | `color` | Chữ / icon / spinner | `--brand-text` |
| 43 | `AreaListPage.css` | 213 | `.area-sidebar__user-role` | `color` | Chữ / icon / spinner | `--brand-text` |
| 44 | `AreaListPage.css` | 337 | `.area-floor-tab--active` | `background` | Nền solid (nút chính) | `--brand-* + --theme-on-brand` |
| 45 | `AreaListPage.css` | 338 | `.area-floor-tab--active` | `border-color` | Viền / đường kẻ | `--brand-subtle-border` |
| 46 | `AreaListPage.css` | 356 | `.zone-toolbar__add, .area-toolbar__create-btn` | `background` | Nền solid (nút chính) | `--brand-* + --theme-on-brand` |
| 47 | `AreaListPage.css` | 368 | `.zone-toolbar__add:hover, .area-toolbar__create-btn:hover` | `background` | Nền solid (nút chính) | `--brand-* + --theme-on-brand` |
| 48 | `AreaListPage.css` | 401 | `.area-control-btn:hover` | `background` | Nền mờ / hover / selected | `--brand-subtle` |
| 49 | `AreaListPage.css` | 402 | `.area-control-btn:hover` | `border-color` | Viền / đường kẻ | `--brand-subtle-border` |
| 50 | `AreaListPage.css` | 403 | `.area-control-btn:hover` | `color` | Chữ / icon / spinner | `--brand-text` |
| 51 | `AreaListPage.css` | 588 | `.area-empty-state__icon` | `background` | Nền mờ / hover / selected | `--brand-subtle` |
| 52 | `AreaListPage.css` | 592 | `.area-empty-state__icon` | `color` | Chữ / icon / spinner | `--brand-text` |
| 53 | `AreaListPage.css` | 717 | `.zone-details__code` | `color` | Chữ / icon / spinner | `--brand-text` |
| 54 | `AreaListPage.css` | 780 | `.zone-btn--primary` | `background` | Nền solid (nút chính) | `--brand-* + --theme-on-brand` |
| 55 | `AreaListPage.css` | 786 | `.zone-btn--primary:hover` | `background` | Nền solid (nút chính) | `--brand-* + --theme-on-brand` |
| 56 | `AreaListPage.css` | 1885 | `.zone-canvas-empty__switch-btn` | `color` | Chữ / icon / spinner | `--brand-text` |
| 57 | `AreaListPage.css` | 2014 | `.zone-rail-item--selected` | `background` | Nền mờ / hover / selected | `--brand-subtle` |
| 58 | `AreaListPage.css` | 2015 | `.zone-rail-item--selected` | `border-color` | Viền / đường kẻ | `--brand-subtle-border` |
| 59 | `AreaListPage.css` | 2191 | `.zone-btn-action--primary` | `background` | Nền mờ / hover / selected | `--brand-subtle` |
| 60 | `AreaListPage.css` | 2192 | `.zone-btn-action--primary` | `border-color` | Viền / đường kẻ | `--brand-subtle-border` |
| 61 | `AreaListPage.css` | 2193 | `.zone-btn-action--primary` | `color` | Chữ / icon / spinner | `--brand-text` |
| 62 | `AreaListPage.css` | 2197 | `.zone-btn-action--primary:hover` | `background` | Nền mờ / hover / selected | `--brand-subtle` |
| 63 | `AreaListPage.css` | 2198 | `.zone-btn-action--primary:hover` | `border-color` | Viền / đường kẻ | `--brand-subtle-border` |

### Tổng hợp theo 5 nhóm vai trò:
1. **Chữ / icon / spinner (`--brand-text`):** 18 vị trí.
2. **Nền solid (nút chính) (`--brand-* + --theme-on-brand`):** 9 vị trí.
3. **Viền / đường kẻ (`--brand-subtle-border`):** 14 vị trí.
4. **Focus ring (`--focus-ring`):** Toàn bộ các kiểu focus ring trên các ô nhập liệu/dropdown.
5. **Nền mờ / hover / selected (`--brand-subtle`):** 12 vị trí.
*(Cộng thêm 10 vị trí khai báo token hệ thống trong `theme.css`)*.

---

## 4. Trả Lời 4 Câu Hỏi Chuyên Sâu

### Câu 1: `--theme-primary` còn được dùng cho mục đích nào NGOÀI màu nhận diện thương hiệu không?
**CÓ.** Phân tích thực tế cho thấy `--theme-primary` đang bị dùng lẫn lộn cho nhiều mục đích chức năng:
1. **Màu liên kết hành động (Clickable Link / Action):** `ManageAccountPage.jsx` L1040 ("Chọn ảnh khác"), `ManageAccountPage.css` L520 (`.account-action-btn:hover`), `AreaListPage.css` L1885 (nút chuyển tầng).
2. **Màu định danh kỹ thuật / Code Badge:** `AreaListPage.css` L717 (`.zone-details__code` dùng font monospace hiển thị mã khu vực).
3. **Màu nhãn vai trò tài khoản (Role Label):** `AreaListPage.css` L213 (`.area-sidebar__user-role` hiển thị vai trò Admin/Manager).
4. **Màu chỉ báo hỗ trợ (Help Tooltip Icon):** `CameraDetailPage.css` L569 (icon dấu hỏi khi hover).
5. **Màu trạng thái rỗng (Empty State Accent):** `AreaListPage.css` L588, L592 (vòng tròn và icon khi chưa có dữ liệu).
6. **Màu hiệu ứng quầng sáng nền (Ambient Glow):** `AreaListPage.css` L38.

> **Kết luận:** Khi thay thế, không nên thay mù quáng tất cả thành brand primary. Ví dụ, text code badge nên dùng token text kỹ thuật, link action nên dùng token interactive riêng.

---

### Câu 2: Nếu đổi `--theme-primary` sang hệ cyan, có chỗ nào sẽ bị trùng màu với các trạng thái khác không?
**CÓ NGUY CƠ TRÙNG MÀU LỚN, CỤ THỂ:**
1. **Trùng với `--theme-info` (Xanh da trời / Sky Blue):**
   - Hiện tại trong `theme.css`:
     - Light mode: `--theme-info: #0284c7`
     - Dark mode: `--theme-info: #38bdf8`
   - Nếu đổi `--theme-primary` sang hệ Cyan (`--brand-blue: #3ba8d9` hoặc `--brand-cyan: #7ed3f2`), màu nhận diện thương hiệu sẽ **hầu như trùng hoàn toàn với màu trạng thái Info**. Người dùng sẽ không phân biệt được đâu là tag trạng thái thông báo thông tin (Info badge) và đâu là nút bấm thương hiệu chính (Primary action).
2. **Nhầm lẫn thị giác với `--theme-success` ở Dark Mode:**
   - Ở Dark Mode, `--theme-success` được thiết kế là màu ngọc/mint sáng (`#34d399`). Màu `--brand-cyan` (`#7ed3f2`) có độ bão hòa và độ sáng khá tiệm cận với `#34d399` trên màn hình OLED hoặc độ tương phản yếu. Trong bảng phân quyền khu vực hoặc trạng thái camera (nơi có cả badge "Đang hoạt động" màu lục và icon chọn phòng màu cyan), người dùng rất dễ nhầm lẫn hai trạng thái này.
3. **Warning và Danger:** Không bị trùng màu vì thuộc dải quang phổ vàng cam và đỏ hoàn toàn tách biệt.

---

### Câu 3: Đề xuất thứ tự xử lý: file nào trước, vì sao?

Ta đề xuất lộ trình xử lý chia làm 4 giai đoạn an toàn:

1. **Giai đoạn 1 — File đơn lẻ, rủi ro thấp nhất (Xử lý ngay):**
   - `AreaCameraManagementPage.css` (1 vị trí: icon header L195)
   - `CameraDetailPage.css` (1 vị trí: help icon hover L569)
   - *Lý do:* Mỗi file chỉ có đúng 1 điểm, độc lập, không ảnh hưởng logic form hay cấu trúc trang, có thể kiểm chứng trực quan tức thì.
2. **Giai đoạn 2 — Chuẩn hóa ManageAccountPage (Màn hình form & quản lý):**
   - `ManageAccountPage.jsx` (3 vị trí inline)
   - `ManageAccountPage.css` (17 vị trí)
   - *Lý do:* Màn hình này chứa các thành phần chuẩn như Button, Pagination, Dropzone, Tab. Đây là ứng viên hoàn hảo để refactor chuyển sang dùng bộ component chuẩn `src/components/ui/` (`Button`, `Pagination`, `Badge`) vừa được tạo. Sau khi chuyển sang UI kit, phần lớn các dòng CSS viết tay dùng `--theme-primary` ở file này sẽ tự động biến mất một cách sạch sẽ.
3. **Giai đoạn 3 — Trang bản đồ phức tạp `AreaListPage.css` (24 vị trí):**
   - *Lý do:* Đây là file lớn nhất (2303 dòng), giao diện phức tạp với canvas bản đồ, floor tab, sidebar và nhiều lớp màu nền. Cần xử lý riêng lẻ từng nhóm (sidebar nav, floor tabs, control buttons) kèm chụp ảnh kiểm tra ở cả 2 mode để đảm bảo độ tương phản text và icon.
4. **Giai đoạn 4 — Quyết định kiến trúc cho `theme.css`:**
   - *Lý do:* Chỉ xử lý khi các file màn hình đã được cô lập. Quyết định xem có nên giữ `--theme-primary` như một màu accent riêng biệt (hoặc trỏ sang `--brand-blue`), đồng thời phân tách rạch ròi giữa Primary Brand Action và Info Semantic Status.

---

### Câu 4: Tổng cộng có MẤY kiểu input focus khác nhau trong toàn hệ thống?

Qua quét toàn diện mã nguồn CSS trong `frontend/src/styles/` và các component UI, toàn hệ thống hiện đang có **7 KIỂU INPUT FOCUS KHÁC NHAU**:

#### Kiểu 1: Focus Ring Xanh Dương Cũ (Blue 500)
- **Công thức:**
  ```css
  border-color: var(--theme-border-focus); /* #3b82f6 (light) / #60a5fa (dark) */
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.15);
  outline: none;
  ```
- **Nơi sử dụng:**
  - `ManageAccountPage.css` dòng 218 (`.account-filter-select:focus`)
  - `ManageAccountPage.css` dòng 260 (`.account-search__input:focus`)
  - `ManageAccountPage.css` dòng 1159 (`.account-form-input:focus`, `.account-form-select:focus`)

#### Kiểu 2: Focus Ring Brand Blue + Cyan Tint 2px (Phổ biến nhất)
- **Công thức:**
  ```css
  outline: none;
  border-color: var(--brand-blue);
  box-shadow: 0 0 0 2px rgba(59, 168, 217, 0.15);
  ```
- **Nơi sử dụng:**
  - `AreaCameraManagementPage.css` dòng 108 (`.area-select-dropdown:focus`), dòng 256 (`.transfer-search-wrapper input:focus`)
  - `CameraListPage.css` dòng 149 (`.search-box input:focus`), dòng 175 (`.filter-dropdowns select:focus`)
  - `AreaListPage.css` dòng 1039 (`.area-form-input:focus`)
  - `AccessRequestPage.css` dòng 152 (`.arp-input:focus`, `.arp-select:focus`, `.arp-textarea:focus`)
  - `AccessRequestReviewPage.css` dòng 213 (`.arr-search-input:focus`), dòng 588 (`.arr-textarea:focus`)

#### Kiểu 3: Border Only — Không có Box-Shadow Glow Ring
- **Công thức:**
  ```css
  outline: none;
  border-color: var(--brand-blue);
  ```
- **Nơi sử dụng:**
  - `AccessHistoryPage.css` dòng 132 (`.ahp-select:focus`)

#### Kiểu 4: Border Brand Blue + Blue 600 Ring 3px
- **Công thức:**
  ```css
  outline: none;
  border-color: var(--brand-blue);
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.1);
  ```
- **Nơi sử dụng:**
  - `CameraCreateModal.css` dòng 136 (`.form-group input:focus`, `textarea:focus`, `select:focus`)

#### Kiểu 5: Accent Border + Accent Ring Token
- **Công thức:**
  ```css
  outline: none;
  border-color: var(--accent); /* var(--brand-cyan) */
  box-shadow: 0 0 0 3px var(--accent-ring); /* var(--brand-subtle-border) */
  ```
- **Nơi sử dụng:**
  - `AiSettingsPage.css` dòng 241-242 (`.ai-fps-input:focus`)

#### Kiểu 6: Brand Cyan Border + Glow Ring (Màn hình Login Độc Lập)
- **Công thức:**
  ```css
  outline: none;
  border-color: var(--brand-cyan);
  box-shadow: 0 0 0 3px rgba(126, 211, 242, 0.15);
  ```
- **Nơi sử dụng:**
  - `LoginPage.css` dòng 318-319 (`.login-card__input:focus`)

#### Kiểu 7: Token Chuẩn UI Kit Mới (Đã chuẩn hóa trong bộ UI Kit)
- **Công thức:**
  ```css
  outline: none;
  border-color: var(--brand-blue);
  box-shadow: 0 0 0 2px var(--brand-glow);
  ```
- **Nơi sử dụng:**
  - `frontend/src/components/ui/Input.css` dòng 45-46 (`.ui-input:focus`)
  - `frontend/src/components/ui/Select.css` dòng 47-48 (`.ui-select:focus`)

---

## 5. Đề Xuất Chiến Lược Quy Hoạch Dài Hạn

1. **Về Input Focus:** Đồng bộ toàn bộ các màn hình về **Kiểu 7** (`border-color: var(--brand-blue); box-shadow: 0 0 0 2px var(--brand-glow)`), ngoại trừ màn Login giữ phong cách riêng.
2. **Về `--theme-primary`:** 
   - Thay thế các nút hành động chính, tab active bằng token thương hiệu `--brand-blue` / `--brand-gradient`.
   - Giữ nguyên các ngữ cảnh trạng thái thông tin bằng token `--theme-info` để tránh xung đột ngữ nghĩa.
   - Thay thế các nút phân trang, bảng biểu bằng các component trong `src/components/ui/`.
