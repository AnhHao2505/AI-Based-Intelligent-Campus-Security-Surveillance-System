# Báo Cáo Xây Dựng Base Component Kit (UI Kit)
**Hệ thống:** ICSSS / FPTU SecureVision  
**Thời gian:** Tháng 09/2026  
**Chế độ thực thi:** Unsupervised Mode (Chạy không giám sát, commit local từng component)  
**Nhánh Git:** `feat/ui-normal-user-screens`  
**Tag mốc an toàn:** `ui-before-component-kit`

---

## 1. BƯỚC 0 — DỌN DẸP & ĐỒNG BỘ TRƯỚC KHI BẮT ĐẦU

Trước khi khởi tạo component kit, các thay đổi chuẩn hoá văn bản tiếng Việt (Sentence Case) và đồng bộ màu nút từ phiên làm việc trước đó đã được hoàn tất và chia nhỏ thành các commit độc lập. Tại Bước 0:
* **Kiểm tra trạng thái**: Thư mục `docs/ui-audit.md` đang ở trạng thái untracked.
* **Commit tài liệu**:
  * Mã commit: `44ae144`
  * Message: `docs: add UI audit report`
* **Gán nhãn quay lui (Rollback tag)**:
  * Lệnh đã chạy: `git tag ui-before-component-kit`
  * Mục đích: Lưu lại điểm chốt an toàn trước khi thêm các file Base Component mới.

---

## 2. DANH SÁCH FILE & COMMIT ĐÃ TẠO

### A. Danh sách các file mới được tạo (19 files)
1. [`frontend/src/components/ui/Button.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Button.jsx)
2. [`frontend/src/components/ui/Button.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Button.css)
3. [`frontend/src/components/ui/Input.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Input.jsx)
4. [`frontend/src/components/ui/Input.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Input.css)
5. [`frontend/src/components/ui/Select.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Select.jsx)
6. [`frontend/src/components/ui/Select.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Select.css)
7. [`frontend/src/components/ui/Badge.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Badge.jsx)
8. [`frontend/src/components/ui/Badge.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Badge.css)
9. [`frontend/src/components/ui/Card.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Card.jsx)
10. [`frontend/src/components/ui/Card.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Card.css)
11. [`frontend/src/components/ui/Modal.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Modal.jsx)
12. [`frontend/src/components/ui/Modal.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Modal.css)
13. [`frontend/src/components/ui/Table.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Table.jsx)
14. [`frontend/src/components/ui/Table.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Table.css)
15. [`frontend/src/components/ui/Pagination.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Pagination.jsx)
16. [`frontend/src/components/ui/Pagination.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Pagination.css)
17. [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js) (Barrel export)
18. [`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx)
19. [`frontend/src/pages/_devPreview/UiKitPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.css)
*(Kèm chỉnh sửa đúng 1 dòng khai báo route `/dev/ui-kit` tại [`frontend/src/App.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx))*

### B. Chuỗi commit Git độc lập
| STT | Commit Hash | Commit Message | Nội Dung Chính |
|---|---|---|---|
| 0 | `44ae144` | `docs: add UI audit report` | Lưu trữ báo cáo UI Audit và tạo tag an toàn |
| 1 | `8d3e998` | `feat(ui): add Button component with brand gradient and variants` | Component Button (4 variants × 2 sizes, spinner loading, hover lift) |
| 2 | `5e34e8e` | `feat(ui): add Input component with label, error, and icon support` | Component Input (nhãn trên, 100% width, error, hint, icon trái) |
| 3 | `91e95ae` | `feat(ui): add Select component matching Input design` | Component Select (chevron down tùy biến, đồng bộ phong cách Input) |
| 4 | `d592e75` | `feat(ui): add Badge component with security levels and theme semantics` | Component Badge (3 màu an ninh cố định + 5 màu theme token) |
| 5 | `f3e5e44` | `feat(ui): add Card component with accent border strip and paddings` | Component Card (dải màu 3px bên trái, bo góc `0 10px 10px 0`, paddings sm/md) |
| 6 | `c52566d` | `feat(ui): add Modal component with 30x30 icon badge, sizes, and escape key` | Component Modal (badge icon 30×30, 4 sizes, escape key, backdrop lock) |
| 7 | `1429cba` | `feat(ui): add Table component with skeleton loading and empty state` | Component Table (header 11px chữ hoa, skeleton loading giữ header, monospace code) |
| 8 | `2800a3a` | `feat(ui): add Pagination component with 0-indexed Spring Boot mapping` | Component Pagination (nội bộ 0-indexed, thanh điều hướng active dùng `--brand-*`) |
| 9 | `fa33c75` | `feat(ui): export ui components from components/ui barrel` | Barrel export tập trung tại `src/components/ui/index.js` |
| 10 | `998f37c` | `feat(ui): add /dev/ui-kit preview page and register route` | Trang demo `/dev/ui-kit` kiểm thử toàn diện Light/Dark mode |

---

## 3. CHI TIẾT TỰ QUYẾT KHI TRÍCH XUẤT CODE TỪ CÁC FILE CSS GỐC

Trong quá trình trích xuất từ các file CSS hiện hữu ([`AreaListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css), [`CameraListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css), [`ManageAccountPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css)), nhiều thông số bị lệch pha. Dưới đây là các quyết định kỹ thuật đã đưa ra:

### 1. Button
* **Xung đột chiều cao & Padding:**
  * `AreaListPage.css`: Nút chính `.zone-toolbar__add-btn` dùng `height: 36px; padding: 0 18px; font-size: 13px;`
  * `ManageAccountPage.css`: Nút `.account-header__create-btn` dùng `padding: 10px 18px; font-size: 0.875rem;` (~39px).
  * `CameraListPage.css`: Dùng `padding: 8px 16px;` (~36px).
* **Quyết định chọn:**
  * **Size `md` (chuẩn):** `height: 38px; padding: 0 16px; font-size: 0.875rem (14px); border-radius: 8px;` — Đây là chuẩn công thái học tốt nhất khi đứng cạnh Input/Select (38px).
  * **Size `sm` (nhỏ):** `height: 32px; padding: 0 12px; font-size: 0.8125rem (13px); border-radius: 6px;` — Phù hợp dùng trong toolbar con hoặc bảng dữ liệu.

### 2. Input & Select
* **Xung đột chiều cao & Viền Focus:**
  * `AreaListPage.css` & `CameraListPage.css`: Dùng `box-shadow: 0 0 0 3px var(--theme-primary-subtle)` (tỏa viền xanh dương cũ lệch hệ màu).
  * `AiSettingsPage.css`: Dùng `box-shadow: 0 0 0 3px rgba(59, 168, 217, 0.25)`.
* **Quyết định chọn:**
  * Chiều cao chuẩn hóa: `height: 38px; padding: 0 12px; border-radius: 8px; font-size: 0.875rem;`.
  * Khi focus: `border-color: var(--brand-blue); box-shadow: 0 0 0 3px var(--brand-subtle);` — Đưa toàn bộ hiệu ứng viền focus về họ màu Cyan thương hiệu, chấm dứt việc lệch sang xanh dương.

### 3. Badge
* **Xung đột Bo góc (Border Radius):**
  * `ManageAccountPage.css`: Dùng `border-radius: 9999px;` (dạng viên thuốc/pill).
  * `CameraListPage.css` & `AreaListPage.css`: Dùng `border-radius: 6px;`.
* **Quyết định chọn:** Dùng `border-radius: 6px; height: 22px; padding: 0 8px; font-size: 0.75rem (12px); font-weight: 600;`. Dạng bo góc nhẹ này tạo cảm giác chuyên nghiệp, kỹ thuật hơn cho giao diện giám sát an ninh so với bo tròn pill.

### 4. Card
* **Xung đột Bo góc khi có Dải màu Accent:**
  * Quy ước bắt buộc: "Dải màu 3px BÊN TRÁI, border-radius 0 10px 10px 0".
* **Quyết định chọn:** Card thông thường dùng `border-radius: 10px;`. Khi có prop `accentColor`, tự động kích hoạt class `.ui-card--has-accent` áp dụng `border-left: 3px solid [accentColor]` và đặt `border-radius: 0 10px 10px 0;`.

### 5. Modal
* **Xung đột Kích thước Modal giữa các trang:**
  * `CameraCreateModal.css`: Dùng `max-width: 540px;`
  * `AreaListPage.css`: Dùng `max-width: 580px;`
  * `AccessRequestPage.css`: Dùng `max-width: 600px;`
* **Quyết định chọn:** Chuẩn hóa thành 4 kích thước cố định theo quy ước:
  * `sm`: `max-width: 420px` (Hộp thoại xác nhận, cảnh báo)
  * `md`: `max-width: 560px` (Form tạo mới cơ bản)
  * `lg`: `max-width: 680px` (Form phức tạp, giải trình)
  * `xl`: `max-width: 900px` (Bảng dữ liệu, sơ đồ chi tiết)

### 6. Table
* **Xung đột Header và Dòng:**
  * `AreaListPage.css`: Thẻ `th` có padding `10px 14px`.
  * `CameraListPage.css`: Thẻ `th` có padding `12px 16px`, font 11px chữ hoa.
* **Quyết định chọn:** Chuẩn hoá thẻ `th` theo đúng quy ước: `font-size: 11px; text-transform: uppercase; letter-spacing: 0.05em; color: var(--theme-text-muted); background: var(--theme-bg-surface-elevated); padding: 12px 16px;`.

### 7. Pagination
* **Xung đột Chỉ mục trang:**
  * `ManageAccountPage.jsx`: Nhận 0-index từ API Spring Boot nhưng render hiển thị +1.
  * `CameraListPage.jsx`: Dùng nút Trước/Sau đơn giản.
* **Quyết định chọn:** Cố định 100% nội bộ là **0-index**, tuyệt đối không hỗ trợ prop tuỳ biến 1-index để tránh lỗi lệch 1 trang (`off-by-one`) khi nối API Spring Boot. Hiển thị thông minh dấu `...` (ellipses) khi tổng số trang > 7.

---

## 4. NHỮNG CHỖ PHẢI VIẾT CỨNG MÃ MÀU VÌ THIẾU BIẾN TRONG THEME.CSS

Theo đúng nguyên tắc, 3 màu ngữ nghĩa an ninh là màu cố định, không phụ thuộc theme:
1. `#22c55e` (Xanh lá - Cấp độ an ninh `PUBLIC`)
2. `#fbbf24` (Vàng hổ phách - Cấp độ an ninh `SEMI_PRIVATE`)
3. `#f87171` (Đỏ san hô - Cấp độ an ninh `PRIVATE`)

Trong [`frontend/src/styles/theme.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css) chưa có các biến token như `--color-public`, `--color-semi-private`, `--color-private` dạng hex độc lập (chỉ có các biến tổ hợp như `--theme-level-pub-bg`, `--theme-level-pub-border`, `--theme-level-pub-text`). Do đó:
* Tại chấm tròn `.ui-badge__dot` của 3 variant này trong [`Badge.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Badge.css#L42-L68), mã hex `#22c55e`, `#fbbf24`, `#f87171` được viết trực tiếp.
* Hiệu ứng bóng đỏ khi hover nút danger: `rgba(239, 68, 68, 0.25)`.
* Hiệu ứng viền đỏ khi input lỗi focus: `rgba(239, 68, 68, 0.15)`.

*(Tất cả màu nền, màu chữ, viền còn lại đều trỏ 100% về biến `--theme-*` và `--brand-*`).*

---

## 5. CÁC CÂU HỎI & VẤN ĐỀ CẦN Ý KIẾN CỦA BẠN (GHI NHẬN TỪ CHẾ ĐỘ CHẠY KHÔNG GIÁM SÁT)

1. **Có nên bổ sung 3 biến màu an ninh độc lập vào `theme.css` trong tương lai không?**
   - Đề xuất: `--semantic-public: #22c55e;`, `--semantic-semi: #fbbf24;`, `--semantic-private: #f87171;` để không phải viết cứng mã hex khi vẽ canvas toạ độ hoặc vẽ dải màu Card.
2. **Quy chuẩn Input Focus Ring toàn hệ thống:**
   - Hiện tại `Input` và `Select` mới được gán focus ring màu Cyan `var(--brand-subtle)`. Bạn có muốn một đợt cập nhật riêng để thay thế toàn bộ `box-shadow: 0 0 0 3px var(--theme-primary-subtle)` ở `CameraListPage.css` và `AreaListPage.css` sang `var(--brand-subtle)` cho đồng bộ không?

---

## 6. HƯỚNG DẪN KIỂM THỬ (MANUAL TEST GUIDE)

Khởi chạy frontend tại `http://localhost:5173/`.  
Mở trực tiếp đường dẫn: **`http://localhost:5173/dev/ui-kit`** (Không cần đăng nhập).

### A. Kiểm tra chuyển đổi Theme (Light / Dark Mode)
* Ở góc trên bên phải màn hình, bấm vào nút **Chế độ: Light Mode / Dark Mode**.
* Toàn bộ nền trang, thẻ card, bảng, ô nhập, modal tự động đảo màu theo đúng token mà không cần reload trang.

### B. Kiểm tra độ tương phản Icon trên Nền ở CẢ HAI Theme (Điểm cốt lõi)
1. **Tại phần 5. Modal Component**:
   * Bấm nút **"Mở Modal MD (560px — Brand Icon)"**:
     * **Ở Light Mode**: Quan sát ô vuông 30×30 chứa icon Camera. Nền là màu cyan 12% rất nhạt (`--brand-subtle`), viền mảnh rõ. Icon Camera bên trong có màu **cyan đậm đầm mắt** (`--brand-text: #0e7490`). Icon nổi bật rõ nét trên nền trắng, **hoàn toàn không bị mờ nhạt hay lóa**.
     * **Chuyển sang Dark Mode** (hoặc mở lại khi ở Dark Mode): Nền ô vuông 30×30 chuyển thành cyan trầm tối (`#16293c`). Icon Camera tự động đổi thành **cyan sáng phát quang nhẹ** (`--brand-text: #7ed3f2`). Độ tương phản đạt ~9:1, đọc rất rõ, êm mắt.
   * Thử nghiệm phím **Escape**: Bấm phím `Esc` trên bàn phím xem modal có đóng ngay lập tức hay không.
   * Bấm nút **"Modal Chặn Backdrop Click (closeOnBackdrop=false)"**: Nhấp chuột ra vùng mờ bên ngoài — modal giữ nguyên không đóng, xác nhận tính năng chặn click backdrop hoạt động chính xác.
2. **Tại phần 3. Badge Component**:
   * Kiểm tra 3 huy hiệu an ninh `PUBLIC`, `SEMI_PRIVATE`, `PRIVATE`: Chấm tròn giữ nguyên màu chuẩn `#22c55e`, `#fbbf24`, `#f87171` ở cả light và dark mode.
   * Kiểm tra huy hiệu `Brand`: Chữ và icon có màu cyan đậm ở light mode, cyan sáng ở dark mode.

### C. Kiểm tra Input & Select
* Nhấp vào các ô nhập: Kiểm tra viền focus phát sáng màu cyan (`var(--brand-subtle)`), không bị viền xanh dương cũ.
* Quan sát ô nhập lỗi (Email): Có viền đỏ, icon cảnh báo và thông điệp lỗi màu đỏ hiển thị ngay dưới ô nhập.
* Nhấp vào ô Select: Dropdown mở ra mượt mà, mũi tên chevron down nằm ngay ngắn bên phải.

### D. Kiểm tra Table
* **Bảng có dữ liệu**: Quan sát cột Mã Camera (`CAM-T01-01`...) hiển thị bằng font Monospace (`JetBrains Mono`). Tiêu đề cột viết hoa 11px đúng chuẩn.
* **Bảng Loading**: Tiêu đề cột vẫn giữ nguyên, phần thân hiển thị 5 dòng Skeleton nhấp nháy chuyển động (shimmer animation), không dùng vòng xoay spinner.
* **Bảng Rỗng**: Tiêu đề cột vẫn giữ nguyên, phần thân hiển thị icon hộp rỗng kèm thông báo "Chưa có thiết bị camera nào...".

### E. Kiểm tra Pagination
* Quan sát khối "Hiển thị 1–10 trên tổng số 120 camera".
* Nút trang hiện tại (Active) được tô gradient tím-cyan kèm bóng sáng (`--brand-glow`).
* Tại mục **"Demo Tương Tác Trực Tiếp"**: Bấm qua lại giữa các trang 1, 2, 3... để xem số trang nội bộ (0-index) và số trang hiển thị (1-index) cập nhật realtime.

---

## 7. CÁC VỊ TRÍ TRONG CODE CŨ CHƯA THỂ DÙNG NGAY BASE COMPONENT NÀY

Liệt kê để chuẩn bị cho các giai đoạn refactor tiếp theo (không can thiệp trong đợt này):
1. **Bản đồ tầng và Canvas vẽ đa giác ([`AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx))**:
   * Các nút zoom in/zoom out, nút reset canvas, nút công cụ vẽ điểm đang nằm lồng trực tiếp bên trên thẻ `<canvas>` với CSS định vị tuyệt đối (`position: absolute`). Cần giữ nguyên để không làm lệch toạ độ chuột.
2. **Bảng phân quyền gán camera ([`AreaCameraManagementPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx))**:
   * Giao diện dạng hai hộp chuyển giao (Transfer List: danh sách khả dụng $\leftrightarrow$ danh sách đã gán), không phải dạng thẻ `<table>` truyền thống.
3. **Form Upload nhiều ảnh nhận diện khuôn mặt ([`AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx))**:
   * Dropzone kéo thả ảnh, xem trước thumbnail đa luồng và thanh tiến trình convert ảnh HEIC cần một component đặc thù riêng (`ImageUploadDropzone`).

---

## 8. TÌNH TRẠNG HOÀN TẤT CỦA TOÀN BỘ NHIỆM VỤ

* [x] **Bước 0**: Dọn dẹp, commit docs, tạo tag `ui-before-component-kit`.
* [x] **Component 1**: `Button` (đã commit `8d3e998`).
* [x] **Component 2**: `Input` (đã commit `5e34e8e`).
* [x] **Component 3**: `Select` (đã commit `91e95ae`).
* [x] **Component 4**: `Badge` (đã commit `d592e75`).
* [x] **Component 5**: `Card` (đã commit `f3e5e44`).
* [x] **Component 6**: `Modal` (đã commit `c52566d`).
* [x] **Component 7**: `Table` (đã commit `1429cba`).
* [x] **Component 8**: `Pagination` (đã commit `2800a3a`).
* [x] **Barrel File**: `src/components/ui/index.js` (đã commit `fa33c75`).
* [x] **Trang Demo**: `src/pages/_devPreview/UiKitPage.jsx` + route `/dev/ui-kit` (đã commit `998f37c`).
* [x] **Báo Cáo**: Hoàn tất 100% theo đúng mọi yêu cầu, không có phần nào bị bỏ dở.

---

# DỌN MÀU CÒN SÓT (VIỆC A, B, C, D)

## 1. TỔNG HỢP FILE ĐÃ SỬA & COMMIT

| Việc | Nội dung | Các file đã sửa | Commit |
|---|---|---|---|
| **Việc A** | Thay `var(--brand-cyan)` bằng `var(--brand-text)` cho các vị trí chữ, icon, spinner, nhãn slider | `AccessRequestReviewPage.css`<br>`AreaCameraManagementPage.css`<br>`DashboardPage.css`<br>`NotificationsPage.css`<br>`AreaListPage.css`<br>`AiSettingsPage.css` | `1978d31` (`fix(ui): dùng brand-text thay brand-cyan cho các icon còn lại`) |
| **Việc B** | Thay `rgba(59, 168, 217, 0.12)` và `rgba(59, 168, 217, 0.25)` viết cứng bằng `var(--brand-subtle)` và `var(--brand-subtle-border)` | `AccessHistoryPage.css`<br>`AccessRequestPage.css`<br>`AccessRequestReviewPage.css`<br>`AiSettingsPage.css`<br>`AreaCameraManagementPage.css`<br>`CameraListPage.css`<br>`DashboardPage.css`<br>`GuardDashboardPage.css`<br>`NotificationsPage.css`<br>`WebRtcPlayer.css` | `7f9c7e2` (`fix(ui): thay rgba viết cứng bằng token brand-subtle`) |
| **Việc C** | Thêm nhóm token `--theme-on-*` trên nền solid, utility classes `.btn-status--*` và dọn chữ viết cứng | `theme.css`<br>`CameraDetailPage.css`<br>`CameraListPage.css`<br>`Sidebar.css`<br>`AccessRequestReviewPage.css`<br>`AreaListPage.css` | `3a696be` (`style(theme): add on-solid status text tokens and status button utilities`) |
| **Việc D** | Xác minh baseline 63 vị trí, lập bảng 5 vai trò, gom 7 kiểu focus về `--focus-ring` (GĐ 1) và chuyển chữ/icon sang `--brand-text` (GĐ 2) | `docs/theme-primary-audit.md`<br>`theme.css`<br>`Input.css`, `Select.css`<br>`ManageAccountPage.css`<br>`AreaListPage.css`<br>`AreaCameraManagementPage.css`<br>`CameraDetailPage.css`<br>`CameraListPage.css`<br>`AccessRequestPage.css`<br>`AccessRequestReviewPage.css`<br>`AccessHistoryPage.css`<br>`CameraCreateModal.css`<br>`AiSettingsPage.css` | `0b97164` (`style(theme): unify focus ring to --focus-ring and migrate text icons to brand-text`) |

### Chi tiết xử lý ở Việc C & D:
1. **Tại Việc C (Token chữ trên nền solid):**
   - Đã tính toán tương phản WCAG 2.1:
     - `--theme-success` (#16a34a light, #34d399 dark): `#0d1117` đạt **5.74:1** (light) và **9.84:1** (dark). Cả 2 mode đều $\ge 4.5:1$ (vượt trội so với `#ffffff` chỉ đạt 3.30:1 ở light và 1.92:1 ở dark).
     - `--theme-danger` (#ef4444 light, #f87171 dark): `#0d1117` đạt **5.03:1** (light) và **6.84:1** (dark).
     - `--theme-warning` (#f59e0b light, #fbbf24 dark): `#0d1117` đạt **8.81:1** (light) và **11.34:1** (dark).
     - `--theme-info` (#0284c7 light, #38bdf8 dark): `#0d1117` đạt **4.62:1** (light) và **8.83:1** (dark).
     - Do các nền solid đều sáng hơn `#0d1117`, toàn bộ nhóm `--theme-on-success/danger/warning/info` chỉ cần khai báo tại `:root`, không cần override ở dark mode. Riêng `--theme-on-brand` trỏ về `var(--brand-on-gradient)` để tự đảo theo mode.
   - Đã thêm nhóm class `.btn-status--success / --danger / --warning` (xử lý đủ 3 state: default, hover, disabled).
2. **Tại Việc D (Chuẩn hóa input focus & Brand text):**
   - Đã thêm token `--focus-ring: 0 0 0 2px var(--brand-glow);` và class `.input-focus` dùng chung.
   - Đồng bộ toàn bộ 7 biến thể focus ring rải rác về `--focus-ring` chuẩn (ngoại trừ `LoginPage.css` giữ nguyên).
   - Đã chuyển 17 vị trí chữ/icon/spinner từ `var(--theme-primary)` sang `var(--brand-text)` (tương phản 5.37:1 ở light và 10.68:1 ở dark).
   - DỪNG LẠI sau Giai đoạn 2: còn 46 vị trí (nền solid, viền, nền mờ và 1 inline jsx) chờ đợt sau.

---

## 2. NHỮNG CHỖ ĐÃ DỪNG VÀ CẦN BẠN QUYẾT ĐỊNH

1. **46 vị trí còn lại của `--theme-primary` (Việc D - Giai đoạn 3 & 4):**
   - 9 vị trí nền solid nút chính: Nút phân trang active, nút Lưu modal, nút Thêm khu vực, v.v. (Cần đối chiếu screenshot trước/sau khi chuyển sang `--brand-gradient` / `--brand-blue`).
   - 14 vị trí viền/đường kẻ: Viền tab active, viền modal, viền dropzone (Đích: `--brand-subtle-border`).
   - 12 vị trí nền mờ / hover / selected: Dòng bảng hover, nền menu active (Đích: `--brand-subtle`).
   - 1 vị trí inline style trong JSX: `ManageAccountPage.jsx:1039` (Tuân thủ nghiêm ràng buộc không sửa file JSX).
   - 10 vị trí khai báo token gốc trong `theme.css`.
2. **Đưa variant="success" vào component Button:**
   - Dành cho đợt sau theo đúng ràng buộc số 2.


---

## 3. HƯỚNG DẪN TEST BẰNG MẮT (TIẾNG VIỆT)

### 3.1. Kiểm tra Việc A (Các icon chữ và spinner)
| Màn hình | Đường dẫn / Thao tác | Điểm cần nhìn (Light mode) | Điểm cần nhìn (Dark mode) |
|---|---|---|---|
| **Tổng quan yêu cầu truy cập** | Vào `/access-requests/review` | Nhìn ô thống kê tổng số "Tổng yêu cầu". Icon hồ sơ hiển thị màu xanh tím đậm rõ nét (`#0f766e`), nền xanh nhạt, không bị mờ nhạt chìm vào nền trắng. | Icon hiển thị màu cyan sáng (`#7ed3f2`), hài hòa trên nền tối. |
| **Quản lý Camera theo Khu vực** | Vào `/areas/cameras` | Nhìn icon Camera trên tiêu đề trang (`.area-camera-header__icon`) và biểu tượng xoay khi đang tải dữ liệu. Màu chữ sắc nét, đậm đà, dễ nhận diện. | Icon hiển thị sáng rõ với tone màu thương hiệu. |
| **Dashboard** | Vào `/dashboard` | Nhìn các icon trong tiêu đề của các thẻ thống kê (`.dash-card__icon-box`). Icon màu đậm rõ ràng, viền và nền ăn khớp. | Icon sáng rõ trên nền tối. |
| **Thông báo** | Vào `/notifications` | Các thông báo hệ thống mặc định (`.notif-icon-box--default`): icon chuông/hệ thống có màu chữ đậm, độ tương phản cao trên nền thẻ trắng. | Icon sáng rõ, không chói mắt. |
| **Cấu hình AI** | Vào `/ai-settings` | 1. Icon CPU/Brain trên tiêu đề header.<br>2. Nhãn giá trị phần trăm/FPS của các slider (`.ai-setting-block__badge`).<br>3. Spinner xoay khi tải.<br>Tất cả chữ số và icon đều là màu xanh đậm dễ đọc, không bị bạc màu. | Hiển thị cyan nổi bật trên nền xám đậm. |
| **Bản đồ khu vực** | Vào `/areas` | Nhìn dropdown chọn toà nhà trên thanh công cụ (`.zone-toolbar__building-icon`): icon toà nhà màu xanh đậm nét. | Icon màu cyan sáng. |

### 3.2. Kiểm tra Việc B (Nền mờ và viền mờ `brand-subtle`)
| Màn hình | Đường dẫn / Thao tác | Điểm cần nhìn (Light mode & Dark mode) |
|---|---|---|
| **Lịch sử truy cập** | Vào `/access-requests/history` | Bấm chọn các nút lọc trạng thái (`.ahp-filter-btn--active`). Nền nút chuyển sang màu xanh mờ dịu mắt chuẩn token `--brand-subtle`, không còn giá trị cứng. |
| **Đăng ký truy cập** | Vào `/access-requests` | 1. Các nút lọc danh sách khi kích hoạt (`.arp-filter-btn--active`).<br>2. Các tag nhóm đối tượng (`.arp-badge--group`) hiển thị viền mờ `--brand-subtle-border` thanh thoát, đồng đều. |
| **Duyệt yêu cầu** | Vào `/access-requests/review` | Avatar viết tắt của người gửi (`.arr-user-avatar`): nền tròn xanh mờ, viền mảnh chuẩn token. |
| **Dashboard** | Vào `/dashboard` | Huy hiệu trạng thái "Hệ thống bảo vệ đang bật" trên header (`.dashboard-header__badge`) có nền và viền đồng nhất với Design System. |
| **Camera theo khu vực** | Vào `/areas/cameras` | Badge đếm số lượng camera khả dụng/đã gán (`.badge-count`): viền bo tròn pill tròn trịa, nền mờ tinh tế. |

---

## 4. BÁO CÁO KHẢO SÁT VIỆC D: `--theme-primary`

Toàn bộ kết quả khảo sát kỹ thuật, bảng phân loại rủi ro 56 vị trí và câu trả lời chi tiết cho 4 câu hỏi chuyên sâu (bao gồm bảng thống kê 7 kiểu input focus trên toàn hệ thống) được lưu trữ tại file tài liệu chuyên biệt:  
👉 **[`docs/theme-primary-audit.md`](file:///Users/anhhao/Documents/SEP/docs/theme-primary-audit.md)**

