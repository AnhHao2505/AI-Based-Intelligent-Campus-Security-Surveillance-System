# Báo Cáo Phân Tích Hiện Trạng Giao Diện (UI Audit)
**Hệ thống:** ICSSS (Intelligent Campus Security Surveillance System) — Khuôn viên FPTU Tân Uyên  
**Thời gian thực hiện:** Tháng 09/2026  
**Mục tiêu:** Đánh giá toàn diện kiến trúc frontend, hiện trạng công nghệ, giao diện, luồng dữ liệu, rủi ro kỹ thuật và xây dựng lộ trình nâng cấp / chuẩn hoá giao diện người dùng.

---

## A. NỀN TẢNG CÔNG NGHỆ

### 1. Framework và Version
* **Bằng chứng:** [`frontend/package.json`](file:///Users/anhhao/Documents/SEP/frontend/package.json#L17-L26)
* **Core Framework:** 
  * `react`: `^19.2.8`
  * `react-dom`: `^19.2.8`
* **Routing:** 
  * `react-router-dom`: `^7.18.2`
* **Build Tool / Bundler:** 
  * `vite`: `^8.2.0`
  * `@vitejs/plugin-react`: `^6.0.4`
* **Kết luận:** Đây là ứng dụng **SPA (Single Page Application)** xây dựng trên nền **Vite**. Không phải Next.js (không dùng SSR/SSG/App Router) và không phải Create React App (CRA - không có `react-scripts`).

---

### 2. Ngôn Ngữ và Cấu Hình Strict
* **Bằng chứng:** 
  * [`frontend/package.json`](file:///Users/anhhao/Documents/SEP/frontend/package.json#L5) (`"type": "module"`)
  * Kiểm tra thư mục nguồn: Toàn bộ mã nguồn trong [`frontend/src`](file:///Users/anhhao/Documents/SEP/frontend/src) sử dụng định dạng `.js` và `.jsx`.
  * Không tồn tại bất kỳ file cấu hình TypeScript nào (`tsconfig.json`, `tsconfig.node.json`, `jsconfig.json`).
* **Mức strict trong tsconfig:** **Không xác định được** (do dự án sử dụng JavaScript thuần 100%, không cấu hình TypeScript compiler).
* **Ghi chú thêm:** Trong `devDependencies` có `@types/react` (`^19.2.17`) và `@types/react-dom` (`^19.2.3`) chỉ nhằm mục đích hỗ trợ gợi ý mã nguồn (IntelliSense) cho IDE. Dự án sử dụng linter siêu tốc `oxlint` (`^1.75.0`) cấu hình tại [`.oxlintrc.json`](file:///Users/anhhao/Documents/SEP/frontend/.oxlintrc.json).

---

### 3. Thư Viện UI
* **Bằng chứng:** [`frontend/package.json`](file:///Users/anhhao/Documents/SEP/frontend/package.json#L12-L20) và rà soát toàn bộ component tree.
* **Component Library:** **Không sử dụng bất kỳ thư viện UI component nào** (hoàn toàn không có Ant Design, MUI, Bootstrap, Chakra UI, Radix UI, Headless UI, styled-components hay CSS Modules).
* **Hình thức styling:** **100% CSS thuần (Vanilla CSS)**. Mỗi trang hoặc component import một file `.css` tương ứng trực tiếp.
* **Tất cả các thư viện phụ trợ giao diện & chức năng hiện có:**
  1. `lucide-react` (`^1.33.0`): Thư viện icon vector chính thức, được sử dụng xuyên suốt tất cả các trang và thanh điều hướng.
  2. `@react-oauth/google` (`^0.13.5`): SDK nút đăng nhập Google OAuth, tích hợp tại [`App.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx#L3) và [`LoginPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L7).
  3. `heic2any` (`^0.0.4`): Thư viện chuyển đổi định dạng ảnh Apple HEIC sang JPEG phía trình duyệt khi người dùng upload ảnh chân dung nhận diện khuôn mặt tại [`AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L8).
  4. `@stomp/stompjs` (`^7.3.0`): Thư viện WebSocket client giao thức STOMP nhận cảnh báo an ninh thời gian thực tại [`GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L2).

---

### 4. Hiện Trạng TailwindCSS
* **Bằng chứng:** [`frontend/package.json`](file:///Users/anhhao/Documents/SEP/frontend/package.json)
* **Kết luận:** **Chưa có TailwindCSS**. 
  * Không có package `tailwindcss`, `postcss`, hay `autoprefixer` trong `dependencies` hoặc `devDependencies`.
  * Không có file cấu hình `tailwind.config.js` hay `postcss.config.js`.

---

## B. HIỆN TRẠNG GIAO DIỆN

### 5. Danh Sách Toàn Bộ Route / Page
* **Bằng chứng định tuyến:** [`frontend/src/App.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx#L58-L194)

| STT | Route Path | Đường Dẫn File | Phân Quyền (RBAC) | Mô Tả Chức Năng (1 Dòng) |
|---|---|---|---|---|
| 1 | `/login` | [`frontend/src/pages/LoginPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx) | Public | Xác thực đăng nhập bằng email/mật khẩu hoặc Google OAuth, kiêm màn hình đổi mật khẩu khi có token. |
| 2 | `/unauthorized` | [`frontend/src/pages/UnauthorizedPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/UnauthorizedPage.jsx) | Public / Authed | Hiển thị cảnh báo từ chối truy cập (HTTP 403) khi người dùng không đủ quyền hạn. |
| 3 | `/` | [`frontend/src/App.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx#L26-L32) (`RootRoute`) | Authenticated | Điều hướng tự động: người dùng thường (`NORMAL_USER`) sang `/access-requests`, các role quản trị sang `/dashboard`. |
| 4 | `/dashboard` | [`frontend/src/pages/DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx) | `ADMIN`, `FACILITY_MANAGER`, `SECURITY_GUARD` | Bảng điều khiển trung tâm khuôn viên, thống kê khu vực, trạng thái kết nối hệ thống con và cảnh báo an ninh. |
| 5 | `/admin/areas` | [`frontend/src/pages/areas/AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx) | `ADMIN`, `FACILITY_MANAGER` | Quản lý danh mục khu vực an ninh (CRUD) và vẽ/chỉnh sửa toạ độ đa giác (polygon) trực tiếp trên bản đồ tầng canvas. |
| 6 | `/admin/areas/map` | [`frontend/src/App.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx#L92-L94) | `ADMIN`, `FACILITY_MANAGER` | Route tiện ích tự động chuyển hướng (`Navigate`) về `/admin/areas?view=map`. |
| 7 | `/cameras` | [`frontend/src/pages/cameras/CameraListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx) | `ADMIN` | Quản lý danh sách camera giám sát, tìm kiếm, lọc trạng thái hoạt động/kết nối, thêm mới và ngưng hoạt động camera. |
| 8 | `/cameras/:id` | [`frontend/src/pages/cameras/CameraDetailPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx) | `ADMIN` | Xem chi tiết thông số camera, khu vực phụ trách và xem trực tiếp luồng video streaming qua WebRTC. |
| 9 | `/admin/accounts` | [`frontend/src/pages/accounts/ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx) | `ADMIN` | Quản lý tập trung tài khoản người dùng thường và tài khoản nội bộ hệ thống (tạo mới, sửa, đổi mật khẩu, khoá/mở khoá). |
| 10 | `/guard` | [`frontend/src/pages/guard/GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx) | `INTERNAL_GUARD`, `OUTSOURCED_GUARD` | Console giám sát trực tiếp cho nhân viên bảo vệ, nhận cảnh báo an ninh tức thời qua WebSocket và phát chuông báo động. |
| 11 | `/access-requests` | [`frontend/src/pages/accessRequest/AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx) | `NORMAL_USER` | Giao diện cho người dùng thường tạo yêu cầu ra vào kèm ảnh khuôn mặt và tra cứu lịch sử yêu cầu của chính mình. |
| 12 | `/access-history` | [`frontend/src/pages/accessHistory/AccessHistoryPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx) | `NORMAL_USER` | Tra cứu lịch sử quẹt thẻ/nhận diện ra vào khuôn viên của cá nhân (màn hình giao diện tĩnh, chờ kết nối API MF4). |
| 13 | `/notifications` | [`frontend/src/pages/notifications/NotificationsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx) | `NORMAL_USER` | Trung tâm nhận thông báo trạng thái phê duyệt đơn hoặc nhắc nhở an ninh (màn hình tĩnh, chờ kết nối API). |
| 14 | `/admin/access-requests` | [`frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx) | `FACILITY_MANAGER`, `ADMIN` | Bảng điều khiển xét duyệt hoặc từ chối các yêu cầu cấp quyền ra vào từ người dùng thường. |
| 15 | `/admin/ai-settings` | [`frontend/src/pages/ai/AiSettingsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx) | `ADMIN`, `FACILITY_MANAGER` | Cấu hình tham số động cơ AI nhận diện khuôn mặt (ngưỡng tương đồng `faceMatchThreshold` và `inferenceFps`). |
| 16 | `/admin/area-cameras` | [`frontend/src/pages/areas/AreaCameraManagementPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx) | `ADMIN`, `FACILITY_MANAGER` | Giao diện hai danh sách chuyển giao (transfer boxes) quản lý liên kết nhiều - nhiều giữa Camera và Khu vực an ninh. |
| 17 | `*` | [`frontend/src/App.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx#L193) | Mọi người dùng | Fallback route tự động điều hướng các URL không tồn tại về `RootRoute`. |

---

### 6. Danh Mục Component Tái Sử Dụng & Hiện Trạng Trùng Lặp
* **Bằng chứng cấu trúc thư mục:** [`frontend/src/components`](file:///Users/anhhao/Documents/SEP/frontend/src/components)
* **Toàn bộ component dùng chung hiện có (chỉ có 5 file duy nhất):**
  1. [`frontend/src/components/layout/AppLayout.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/AppLayout.jsx): Khung sườn bao quanh trang quản trị (render Sidebar bên trái và nội dung Outlet bên phải).
  2. [`frontend/src/components/layout/Sidebar.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/Sidebar.jsx): Menu điều hướng dọc, tự động điều chỉnh menu theo vai trò người dùng (RBAC).
  3. [`frontend/src/components/ProtectedRoute.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ProtectedRoute.jsx): Guard component kiểm tra đăng nhập và đối chiếu quyền hạn truy cập route.
  4. [`frontend/src/components/cameras/CameraCreateModal.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/cameras/CameraCreateModal.jsx): Hộp thoại tạo mới camera.
  5. [`frontend/src/components/video/WebRtcPlayer.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/video/WebRtcPlayer.jsx): Trình phát luồng video WebRTC kết nối tới media server.

* **Thực trạng thiếu hụt Base Components & Sự trùng lặp nghiêm trọng:**
  * **Button, Input, Select, Badge, Card:** **0 component tái sử dụng**. Tất cả các trang đều tự viết thẻ `<button>`, `<input>`, `<select>` với class CSS đặt tên riêng rẽ theo từng màn hình (ví dụ: `.arp-btn`, `.account-btn`, `.zone-btn`, `.camera-btn`, `.arp-input`, `.account-form-input`, `.zone-input`...).
  * **Modal Dialog (Hộp thoại):** Bị phân mảnh thành **5 biến thể độc lập** viết tay trực tiếp bên trong JSX của từng file thay vì dùng chung 1 modal base:
    1. Modal tạo camera: [`CameraCreateModal.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/cameras/CameraCreateModal.jsx) (class `.modal-backdrop`, `.modal-content`).
    2. Modal thêm/sửa khu vực: [`AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L1318) (class `.zone-modal-overlay`, `.zone-modal`).
    3. Hàng loạt modal quản lý tài khoản: [`ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L1010) (4 modal riêng biệt cho tạo user hệ thống, tạo user thường, reset mật khẩu, vô hiệu hoá dùng class `.account-modal-overlay`, `.account-modal`).
    4. Modal nhập lý do từ chối đơn: [`AccessRequestReviewPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx#L435) (class `.arr-modal-overlay`, `.arr-modal`).
    5. Modal xem chi tiết đơn: [`AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L834) (class `.arp-modal-overlay`, `.arp-modal`).
  * **Bảng dữ liệu (Table):** **5 biến thể lặp lại** với cấu trúc HTML thẻ `<table>` tương tự nhau nhưng CSS tách rời hoàn toàn:
    1. `.account-table` trong [`ManageAccountPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css#L318)
    2. `.camera-table` trong [`CameraListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css#L160)
    3. `.zone-table` trong [`AreaListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L650)
    4. `.arr-table` trong [`AccessRequestReviewPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestReviewPage.css#L220)
    5. `.arp-table` trong [`AccessRequestPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestPage.css#L660)
  * **Thanh phân trang (Pagination):** **4 biến thể lặp lại**:
    1. Phân trang nâng cao có dấu `...`: [`ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L855) (`.account-pagination`, `.account-page-btn`).
    2. Phân trang số trang: [`AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L770) (`.arp-pagination`, `.arp-page-btn`).
    3. Phân trang cơ bản Trước/Sau: [`CameraListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L265) (`.camera-pagination`).
    4. Phân trang đơn giản: [`AccessRequestReviewPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx#L405) (`.arr-pagination`).

---

### 7. Định Nghĩa Màu Sắc, Font, Spacing & Design Token
* **Màu sắc (Colors):**
  * **Định nghĩa tập trung:** Đã có hệ thống design token tại [`frontend/src/styles/theme.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css#L6-L190) bao gồm các biến CSS cho chế độ sáng (`[data-theme="light"]`) và chế độ tối (`[data-theme="dark"]`):
    * Nền: `--theme-bg-page`, `--theme-bg-surface`, `--theme-bg-canvas`, `--theme-bg-sidebar`...
    * Chữ: `--theme-text-primary`, `--theme-text-secondary`, `--theme-text-muted`...
    * Thương hiệu & Điểm nhấn: `--theme-primary`, `--brand-cyan`, `--brand-blue`, `--brand-violet`, `--brand-gradient`...
    * Trạng thái an ninh: `--theme-level-pub-*`, `--theme-level-semi-*`, `--theme-level-priv-*`...
  * **Thực trạng Hardcode rải rác:** Mặc dù đã có token, **vẫn còn hơn 228 vị trí mã màu hex bị hardcode trực tiếp** trong các file CSS.
  * **Ví dụ cụ thể:**
    * [`ManageAccountPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css#L387-L413): Hardcode màu nền và chữ của huy hiệu vai trò (`#ede9fe`, `#5b21b6`, `#dbeafe`, `#1e40af`, `#e0f2fe`, `#0369a1`).
    * [`ManageAccountPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css#L525-L545): Hardcode màu trạng thái hoạt động (`#2563eb`, `#d97706`, `#16a34a`, `#dc2626`).
    * [`CameraDetailPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraDetailPage.css#L185): Hardcode màu chữ `color: #0d1117;`.
* **Font chữ:**
  * Định nghĩa tập trung tại [`frontend/src/index.css`](file:///Users/anhhao/Documents/SEP/frontend/src/index.css#L5-L23): 
    * Nhúng Google Fonts: `@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap');`
    * Áp dụng toàn cục: `font-family: 'Inter', 'Segoe UI', system-ui, -apple-system, sans-serif;`
  * Một số file CSS phụ trợ tự viết font monospace riêng (`font-family: monospace` hoặc `'JetBrains Mono'`) cho thông số kỹ thuật camera.
* **Khoảng cách (Spacing):**
  * **HOÀN TOÀN KHÔNG CÓ DESIGN TOKEN CHO SPACING**.
  * Trong [`theme.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css) không có bất kỳ biến nào như `--spacing-sm`, `--spacing-md`, `--gap-*`, `--radius-*`.
  * Toàn bộ 100% khoảng cách lề (margin, padding), khoảng cách lưới (gap), bo góc (border-radius) đều bị **hardcode tự do bằng px hoặc rem** trong từng file CSS riêng rẽ (ví dụ: chỗ dùng `padding: 12px 16px`, chỗ dùng `padding: 14px 20px`, chỗ dùng `gap: 8px`, `gap: 12px`, `gap: 16px`...).

---

### 8. Thống Kê File CSS/SCSS và Dung Lượng
* **Bằng chứng:** Chạy lệnh rà soát toàn bộ thư mục [`frontend/src`](file:///Users/anhhao/Documents/SEP/frontend/src).
* **Số lượng file:**
  * File CSS (`.css`): **19 file**
  * File SCSS (`.scss`): **0 file**
* **Tổng số dòng code CSS:** **10,753 dòng**
* **Tổng dung lượng CSS:** **223,593 bytes (~223.6 KB / 218.35 KiB)**
* **Chi tiết từng file theo thứ tự dung lượng:**

| STT | Tên File | Đường Dẫn Cụ Thể | Số Dòng | Dung Lượng (Bytes) |
|---|---|---|---|---|
| 1 | `AreaListPage.css` | [`frontend/src/styles/AreaListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css) | 2,302 | 47,344 |
| 2 | `ManageAccountPage.css` | [`frontend/src/styles/ManageAccountPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css) | 1,577 | 34,146 |
| 3 | `AccessRequestPage.css` | [`frontend/src/styles/AccessRequestPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestPage.css) | 859 | 17,784 |
| 4 | `DashboardPage.css` | [`frontend/src/styles/DashboardPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/DashboardPage.css) | 718 | 14,002 |
| 5 | `GuardDashboardPage.css` | [`frontend/src/styles/GuardDashboardPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/GuardDashboardPage.css) | 695 | 13,452 |
| 6 | `AccessRequestReviewPage.css` | [`frontend/src/styles/AccessRequestReviewPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestReviewPage.css) | 654 | 12,978 |
| 7 | `AreaCameraManagementPage.css`| [`frontend/src/styles/AreaCameraManagementPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaCameraManagementPage.css)| 507 | 11,932 |
| 8 | `CameraDetailPage.css` | [`frontend/src/styles/CameraDetailPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraDetailPage.css) | 576 | 11,564 |
| 9 | `CameraListPage.css` | [`frontend/src/styles/CameraListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css) | 492 | 9,952 |
| 10 | `AiSettingsPage.css` | [`frontend/src/styles/AiSettingsPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AiSettingsPage.css) | 403 | 9,204 |
| 11 | `LoginPage.css` | [`frontend/src/styles/LoginPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/LoginPage.css) | 409 | 8,132 |
| 12 | `theme.css` | [`frontend/src/styles/theme.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css) | 189 | 6,421 |
| 13 | `Sidebar.css` | [`frontend/src/styles/Sidebar.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/Sidebar.css) | 300 | 5,895 |
| 14 | `AccessHistoryPage.css` | [`frontend/src/styles/AccessHistoryPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessHistoryPage.css) | 301 | 5,618 |
| 15 | `NotificationsPage.css` | [`frontend/src/styles/NotificationsPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/NotificationsPage.css) | 282 | 5,157 |
| 16 | `CameraCreateModal.css` | [`frontend/src/styles/CameraCreateModal.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraCreateModal.css) | 222 | 4,345 |
| 17 | `WebRtcPlayer.css` | [`frontend/src/styles/WebRtcPlayer.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/WebRtcPlayer.css) | 203 | 4,335 |
| 18 | `index.css` | [`frontend/src/index.css`](file:///Users/anhhao/Documents/SEP/frontend/src/index.css) | 40 | 793 |
| 19 | `AppLayout.css` | [`frontend/src/styles/AppLayout.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AppLayout.css) | 24 | 539 |
| **Tổng** | **19 file** | | **10,753** | **223,593** |

---

## C. DỮ LIỆU VÀ STATE

### 9. Công Cụ Gọi API
* **Bằng chứng:** [`frontend/src/api/apiClient.js`](file:///Users/anhhao/Documents/SEP/frontend/src/api/apiClient.js#L1-L99)
* **Hình thức hiện tại:** Sử dụng **`fetch` thuần (Browser Native Fetch API)** được đóng gói bên trong module tiện ích trung tâm `apiClient.js`.
* **Cơ chế hoạt động:**
  * Hàm cốt lõi `apiFetch(path, options)` tự động lấy JWT `accessToken` từ `localStorage` đính kèm vào header `Authorization: Bearer ...`.
  * Tự động đặt `Content-Type: application/json` nếu body là JSON thông thường và bỏ qua nếu là `FormData`.
  * Bắt mã HTTP 401: Xoá token khỏi `localStorage` và điều hướng người dùng về trang `/login`.
  * Export các hàm tiện ích theo HTTP method: `apiGet`, `apiPost`, `apiPut`, `apiPatch`, `apiDelete`.
* **Thư viện bên ngoài:** **Không sử dụng** Axios, TanStack React Query, SWR hay RTK Query.

---

### 10. Quản Lý State (State Management)
* **Bằng chứng:**
  * Context API toàn cục:
    1. [`frontend/src/context/AuthContext.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/context/AuthContext.jsx): Lưu trữ trạng thái phiên đăng nhập (`user`, `token`, các hàm `login`, `loginWithGoogle`, `logout`).
    2. [`frontend/src/context/ThemeContext.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/context/ThemeContext.jsx): Lưu trữ trạng thái giao diện sáng/tối (`theme`: 'light' | 'dark', đồng bộ với `localStorage` và gán thuộc tính `document.documentElement.setAttribute('data-theme', theme)`).
* **Quản lý state tại các màn hình:** **100% sử dụng `useState`, `useEffect`, `useCallback`, `useMemo` rải rác cục bộ** trong từng component.
* **Không sử dụng:** Redux, Redux Toolkit, Zustand, MobX, Recoil hay Jotai.
* **Đánh giá:** Ứng dụng không có tầng quản lý server-state tập trung (như React Query). Dữ liệu sau khi fetch không được lưu vào bộ nhớ đệm (cache), dẫn đến việc mỗi lần người dùng chuyển trang hoặc đổi tab, component lại phải gọi lại toàn bộ các API tương ứng từ đầu.

---

### 11. Xử Lý Form và Validation
* **Cơ chế xử lý Form:** 
  * Sử dụng React Controlled Components truyền thống: Quản lý từng trường bằng `useState` lẻ hoặc một object `formData`.
  * Không sử dụng thư viện form như `react-hook-form` hay `formik`. Không sử dụng schema validator như `zod` hay `yup`.
* **Cơ chế Validation:** 
  * Hoàn toàn là **validation thủ công (imperative validation)** bằng các câu lệnh điều kiện `if/else` chạy khi người dùng submit form hoặc khi rời ô nhập (`onBlur`).
* **Bằng chứng cụ thể theo từng màn hình:**
  * Form tài khoản: [`ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L140-L240) có các hàm `validateForm`, `validateSystemForm`, `validateResetPasswordForm` kiểm tra rỗng, regex định dạng email, độ dài mật khẩu >= 8 ký tự, có chữ hoa, thường và số.
  * Form yêu cầu ra vào: [`AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L170-L260) kiểm tra ngày bắt đầu không được trong quá khứ, ngày kết thúc phải sau ngày bắt đầu, bắt buộc chọn ít nhất 1 khu vực, kiểm tra kích thước ảnh upload <= 5MB và đúng định dạng ảnh.
  * Form vẽ khu vực & an ninh: [`AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx) và [`areaHelpers.js`](file:///Users/anhhao/Documents/SEP/frontend/src/utils/areaHelpers.js#L90-L150) kiểm tra mã khu vực hợp lệ, số lượng đỉnh đa giác từ 3-20 đỉnh, toạ độ chuẩn hoá [0.0, 1.0], không tự cắt nhau, và bắt buộc nhập lý do giải trình nếu hạ cấp mức độ an ninh khu vực (`ERR_AREA_007`).

---

### 12. Hiện Trạng Realtime và Polling
* **WebSocket:** **Đã có**.
  * **Màn hình sử dụng:** [`frontend/src/pages/guard/GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L78-L136).
  * **Giao thức:** STOMP qua WebSocket sử dụng thư viện `@stomp/stompjs`.
  * **Cấu hình:** Kết nối tới `ws://localhost:8080/ws-security` với nhịp tim (heartbeat) 4000ms, tự động kết nối lại (`reconnectDelay: 4000`).
  * **Kênh lắng nghe (Channel):** `/topic/security-alerts`. Khi nhận tin nhắn JSON từ Backend, giao diện tự chuẩn hoá URL ảnh chứng cứ từ MinIO (`localhost:9000`), cập nhật danh sách cảnh báo vi phạm trực tiếp lên đầu bảng và kích hoạt âm thanh báo động còi hú bằng Web Audio API synthesizer.
* **Server-Sent Events (SSE):** **Không có** (không có đối tượng `EventSource` trong codebase).
* **Polling:** **Không sử dụng polling định kỳ**. Không có bất kỳ lệnh `setInterval` nào trong toàn bộ mã nguồn frontend. Các lệnh `setTimeout` chỉ được dùng cho mục đích đóng thông báo toast (sau 3 đến 6 giây) hoặc độ trễ thử lại kết nối WebRTC.

---

### 13. Phân Trang, Sắp Xếp và Lọc (Client vs. Server)
* **Bằng chứng phân tích:**

| Màn Hình | Phân Trang (Pagination) | Lọc (Filtering) | Sắp Xếp (Sorting) | Ghi Chú Kỹ Thuật |
|---|---|---|---|---|
| **Quản lý tài khoản** (`/admin/accounts`) | **SERVER** | **SERVER** | **SERVER** | [`ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L296-L305): Gửi `page`, `size`, `keyword`, `accountType`, `isActive`, `sort: 'createdAt,desc'`. Phân trang Spring Data. |
| **Danh sách camera** (`/cameras`) | **SERVER** | **SERVER** | **SERVER** | [`CameraListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L51-L60): Gửi `page`, `size: 10`, `search`, `status`, `operationalStatus`. |
| **Duyệt đơn truy cập** (`/admin/access-requests`) | **SERVER** | **SERVER** | **SERVER** | [`AccessRequestReviewPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx#L53-L65): Gửi `page`, `size: 10`, `status: 'PENDING' \| 'APPROVED' \| 'REJECTED'`. |
| **Lịch sử đơn của tôi** (`/access-requests`) | **SERVER** | **SERVER** | **SERVER** | [`AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L138-L155): Gửi `page`, `status` về backend cá nhân. |
| **Quản lý khu vực** (`/admin/areas`) | **KHÔNG CÓ** | **CLIENT** | **CLIENT** | [`AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L131-L217): Gọi lấy 100 bản ghi (`size: 100`), sau đó dùng `useMemo` lọc theo toà nhà và tầng trong bộ nhớ trình duyệt. |
| **Gán camera khu vực** (`/admin/area-cameras`) | **KHÔNG CÓ** | **CLIENT** | **CLIENT** | [`AreaCameraManagementPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx#L31-L118): Lấy toàn bộ danh sách camera và khu vực, lọc tìm kiếm realtime ở cả 2 cột bằng Javascript array filter. |
| **Lịch sử truy cập** (`/access-history`) | *Chưa nối API* | *Chưa nối API* | *Chưa nối API* | [`AccessHistoryPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx#L20-L26): Giao diện rỗng, chờ backend MF4. |
| **Thông báo** (`/notifications`) | *Chưa nối API* | *Chưa nối API* | *Chưa nối API* | [`NotificationsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx#L18-L25): Giao diện rỗng, chờ backend Notifications. |

---

## D. RỦI RO KỸ THUẬT

### 14. Vị Trí UI Gắn Chặt Với Business Logic (Dễ Vỡ Khi Chạm Vào)
Có 5 vị trí cốt lõi chứa logic phức tạp gắn chặt vào giao diện:
1. **Thuật toán đồ hoạ Canvas & Sơ đồ toạ độ đa giác:**
   * **Vị trí:** [`AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx) (hơn 1,500 dòng logic) và [`areaHelpers.js`](file:///Users/anhhao/Documents/SEP/frontend/src/utils/areaHelpers.js).
   * **Nguy cơ:** Tính toán hình học đa giác chuẩn hoá `[0.0, 1.0]`, thuật toán Ray-Casting kiểm tra điểm nằm trong đa giác, thuật toán kiểm tra giao đoạn thẳng (`segmentsIntersect`), kiểm tra đa giác tự cắt chéo (`hasSelfIntersection`) và kiểm tra chồng lấn diện tích giữa các khu vực (`polygonsOverlap`). Tỉ lệ co giãn giữa kích thước ảnh mặt bằng gốc và kích thước phần tử `<canvas>` trên màn hình rất nhạy cảm; bất kỳ thay đổi nào về CSS bố cục (flexbox/grid/padding) có thể làm lệch điểm click chuột và toạ độ vùng an ninh.
2. **Luồng Video Trực Tiếp WebRTC Streaming:**
   * **Vị trí:** [`WebRtcPlayer.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/video/WebRtcPlayer.jsx) và [`CameraDetailPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx).
   * **Nguy cơ:** Quản lý vòng đời `RTCPeerConnection`, trao đổi SDP Offer/Answer thông qua HTTP POST tới RTSP bridge, lắng nghe sự kiện ICE candidate, và gán luồng `MediaStream` vào thẻ `<video>`. Nếu tái cấu trúc UI làm component bị mount/unmount bất thường hoặc re-render liên tục sẽ gây đứt kết nối luồng camera và rò rỉ socket/bộ nhớ.
3. **Kết Nối Báo Động STOMP WebSocket & Web Audio API:**
   * **Vị trí:** [`GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L78-L136).
   * **Nguy cơ:** Console của bảo vệ phụ thuộc vào kết nối liên tục với broker Spring Boot, đồng thời tự động tổng hợp tần số âm thanh báo động còi hú bằng Web Audio `OscillatorNode`. Nếu sửa UI làm lỗi kết nối WebSocket, bảo vệ sẽ bị mất toàn bộ cảnh báo xâm nhập trái phép thời gian thực.
4. **Xử Lý Chuyển Đổi Ảnh HEIC & Hồ Sơ Nhận Diện Khuôn Mặt:**
   * **Vị trí:** [`AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx).
   * **Nguy cơ:** Quản lý file ảnh chân dung đa luồng, chuyển đổi client-side từ file ảnh iPhone (.heic) sang Blob JPEG thông qua thư viện `heic2any`, kiểm tra tỷ lệ ảnh và dung lượng trước khi nộp đơn cấp quyền ra vào.
5. **File Quản Lý Tài Khoản Khổng Lồ (Monolithic Component ~2,000 dòng):**
   * **Vị trí:** [`ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx) (1,992 dòng code, 86 KB).
   * **Nguy cơ:** File chứa đồng thời 4 luồng modal phức tạp, xử lý debounce timer 300ms, quản lý đồng thời hai loại tài khoản (Normal User vs System Account) với các bộ validation và permission riêng biệt. Chạm vào cấu trúc layout rất dễ gây xung đột state và lỗi submit form.

---

### 15. Kiểm Thử Cho UI (UI Tests)
* **Bằng chứng:** 
  * [`frontend/package.json`](file:///Users/anhhao/Documents/SEP/frontend/package.json#L6-L11): Trong `scripts` chỉ có `dev`, `build`, `lint`, `preview`. Hoàn toàn không có lệnh `test`.
  * Tìm kiếm file test trên toàn bộ thư mục `frontend`: **0 file** (không có bất kỳ file `*.test.jsx`, `*.test.js`, `*.spec.jsx`, `*.spec.js`).
  * Không có thư viện kiểm thử nào được cài đặt (không có Jest, Vitest, Cypress, Playwright hay React Testing Library).
* **Kết luận:** **Dự án chưa có bất kỳ bài kiểm thử nào cho giao diện (0% UI Test Coverage)**.

---

### 16. Các Màn Hình Đang Dở Dang / Chưa Hoàn Thiện
Hiện có 3 màn hình đang ở trạng thái chưa hoàn thiện dữ liệu:
1. [`frontend/src/pages/accessHistory/AccessHistoryPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx#L20-L26):
   * Mới chỉ dựng giao diện tĩnh với trạng thái rỗng (Empty State).
   * Hàm `fetchHistory()` hoàn toàn để trống kèm ghi chú: `// TODO: nối API khi backend có bảng lịch sử nhận diện (thuộc MF4)`.
2. [`frontend/src/pages/notifications/NotificationsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx#L18-L34):
   * Mới chỉ dựng khung giao diện tĩnh.
   * Các hàm nghiệp vụ `fetchNotifications()`, `handleMarkAllAsRead()`, `handleItemClick()` đều là hàm rỗng kèm ghi chú: `// chưa hiện thực`.
3. [`frontend/src/pages/DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx#L28-L48):
   * Mới chỉ kết nối dữ liệu thực tế cho số lượng Khu vực (`getAreas`).
   * Các thẻ KPI còn lại đều hardcode cờ ngắt kết nối: `cameras` (`isConnected: false, reason: 'Cần kết nối module Camera'`), `faceProfiles` (`isConnected: false`), `incidents` (`isConnected: false`).
   * Danh sách 4 hệ thống con (`subsystems`) đều hiển thị trạng thái giả định `not_connected`. Các panel mini-map và nhật ký an ninh đều để mảng rỗng (`miniMapData: null`, `attentionEvents: []`, `securityLogs: []`).

---

## ĐỀ XUẤT HÀNH ĐỘNG

### 1. Nên Migrate Từng Module Hay Làm Mới Toàn Bộ (Rewrite)?
* **Khuyến nghị:** **MIGRATE TỪNG MODULE (Phased / Incremental Migration)**.
* **Lý do dựa trên codebase thực tế (không dựa trên nguyên tắc chung):**
  1. **Logic chuyên sâu đã hoạt động ổn định:** Các module cốt lõi như tính toán toạ độ đa giác canvas ([`AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx)), luồng bắt tay SDP WebRTC ([`WebRtcPlayer.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/video/WebRtcPlayer.jsx)), và lắng nghe sự kiện socket bảo vệ ([`GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx)) đã được tích hợp chặt chẽ với backend Spring Boot và media server. Đập đi xây lại toàn bộ (Big Bang Rewrite) sẽ đối mặt rủi ro tái phát toàn bộ các lỗi hình học, lỗi rớt luồng camera và mất nhiều tuần kiểm thử lại thực địa.
  2. **Dự án hoàn toàn không có test UI bảo vệ (0% test):** Viết lại toàn bộ khi không có test tự động để đối chiếu hành vi sẽ chắc chắn gây ra lỗi hồi quy (regression bugs) mà không ai phát hiện được trước khi ra môi trường thật.
  3. **Độ lệch pha giữa các module backend:** Trong khi module Quản lý khu vực, Camera, Tài khoản đã có đầy đủ API, thì các module Lịch sử truy cập (MF4) và Thông báo vẫn chưa có backend. Migrate từng phần cho phép đồng bộ tiến độ song song với đội ngũ backend.

---

### 2. Thứ Tự Triển Khai & Đề Xuất Màn Hình Pilot

#### Bước Nền Tảng (Bắt buộc trước khi đụng vào màn hình):
* Thiết lập thư viện Base UI Kit nội bộ trong `src/components/common/` (hoặc cấu hình Tailwind/CSS token chuẩn): `Button`, `Input`, `Select`, `Modal`, `Table`, `Pagination`, `Badge`.
* Bổ sung bộ **Spacing Tokens** vào [`theme.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css) (`--spacing-xs`, `--spacing-sm`, `--spacing-md`, `--spacing-lg`, `--radius-*`) để chấm dứt tình trạng hardcode pixel rải rác.

#### Lộ trình 4 giai đoạn cụ thể:
* **Giai đoạn 1 — MÀN HÌNH PILOT ĐẦU TIÊN:**
  * **Chọn màn hình:** [`AiSettingsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx) (`/admin/ai-settings`).
  * **Lý do chọn làm Pilot:**
    1. Dung lượng vừa vặn (chỉ 239 dòng code), logic độc lập, chỉ phụ thuộc vào 2 API đơn giản (`getAiConfig`, `updateAiConfig`).
    2. Chứa đầy đủ các pattern giao diện chuẩn: Card layout, Slider kéo thả, Input số, Nút Reset/Save, Thông báo lỗi/thành công, Trạng thái phát hiện thay đổi (dirty check).
    3. Hoàn toàn không dính líu đến Canvas toạ độ phức tạp, không có WebRTC và không có WebSocket.
    4. Rủi ro nghiệp vụ bằng 0 nếu phát sinh lỗi trong quá trình chuẩn hoá.
  * *Sau khi Pilot thành công:* Áp dụng ngay hệ thống component mới vào hoàn thiện 2 màn hình người dùng thường đang ở dạng khung rỗng: [`AccessHistoryPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx) và [`NotificationsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx).

* **Giai đoạn 2 — Chuẩn hoá các màn hình Bảng & CRUD chuẩn:**
  * Thứ tự: [`CameraListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx) $\rightarrow$ [`AccessRequestReviewPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx) $\rightarrow$ [`AreaCameraManagementPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx) $\rightarrow$ [`ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx).
  * *Lưu ý riêng cho `ManageAccountPage.jsx`:* Phải bóc tách file 1,992 dòng này thành các sub-components nhỏ (`UserTable`, `CreateSystemUserModal`, `CreateNormalUserModal`, `ResetPasswordModal`).

* **Giai đoạn 3 — Chuẩn hoá màn hình Form nghiệp vụ đặc thù:**
  * [`AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx): Chuẩn hoá giao diện upload nhiều ảnh khuôn mặt, tách logic convert ảnh HEIC thành custom hook riêng.

* **Giai đoạn 4 — Di chuyển các màn hình Kỹ thuật cao & Bàn giám sát cốt lõi:**
  * [`GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx) (Console bảo vệ, WebSocket, còi báo động).
  * [`CameraDetailPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx) (WebRTC live streaming).
  * [`AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx) (Canvas bản đồ mặt bằng & vẽ toạ độ đa giác).
  * [`DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx) (Tổng hợp sau khi tất cả các API vệ tinh đã sẵn sàng).

---

### 3. Danh Sách Rủi Ro Cụ Thể và Phương Án Giảm Thiểu

| STT | Rủi Ro Kỹ Thuật Cụ Thể | Vị Trí Bị Ảnh Hưởng | Mức Độ | Phương Án Giảm Thiểu Chi Tiết |
|---|---|---|---|---|
| 1 | **Lệch toạ độ điểm vẽ đa giác khi thay đổi Layout CSS** | [`AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx), [`AreaListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css) | **CỰC KỲ CAO** | Tuyệt đối không thay đổi cấu trúc canvas container. Đóng băng các hàm tính toán toạ độ trong [`areaHelpers.js`](file:///Users/anhhao/Documents/SEP/frontend/src/utils/areaHelpers.js). Thiết lập tỷ lệ hiển thị canvas cố định theo tỉ lệ ảnh gốc (`aspect-ratio`) trước khi chỉnh sửa CSS xung quanh. |
| 2 | **Đứt kết nối luồng video trực tiếp WebRTC** | [`WebRtcPlayer.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/video/WebRtcPlayer.jsx), [`CameraDetailPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx) | **CAO** | Đóng gói toàn bộ logic khởi tạo `RTCPeerConnection` và SDP signaling thành một custom hook độc lập `useWebRtcPlayer`. Đảm bảo hàm cleanup của `useEffect` dọn dẹp kết nối an toàn khi component re-render. |
| 3 | **Mất cảnh báo an ninh thời gian thực do đứt socket** | [`GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx) | **CAO** | Tách riêng client STOMP WebSocket thành một Service / Context độc lập (`SecurityAlertProvider`). Tách rời tầng nhận dữ liệu socket khỏi tầng hiển thị giao diện để việc thay đổi UI không gây re-connect socket. |
| 4 | **Lỗi hồi quy không thể phát hiện do thiếu kiểm thử** | Toàn bộ ứng dụng (16 màn hình) | **CAO** | Trước khi bắt đầu migrate, cài đặt ngay `Vitest` và `@testing-library/react`. Đặt quy tắc: Mỗi khi migrate một màn hình, bắt buộc phải viết kèm ít nhất 1 bài test kiểm tra render thành công và 1 bài test cho luồng submit form chính. |
| 5 | **Vỡ hiển thị giao diện Dark/Light mode do mã hex hardcode** | Hơn 228 vị trí trong 19 file CSS | **TRUNG BÌNH** | Triển khai quy tắc kiểm tra (linter/regex): Nghiêm cấm viết mã hex trực tiếp trong file CSS mới. Mọi màu sắc bắt buộc phải trỏ về biến token trong `theme.css`. |
