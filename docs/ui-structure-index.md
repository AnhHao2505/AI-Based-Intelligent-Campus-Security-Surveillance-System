# Chỉ mục cấu trúc UI frontend

> File tra cứu kỹ thuật dành cho AI agents trong các phiên làm việc tiếp theo.  
> Không chứa đánh giá chủ quan hay đề xuất. Chỉ phản ánh dữ liệu hiện trạng mã nguồn.

---

## Mục lục
- [1. Bảng route](#1-bảng-route)
- [2. Bảng màn hình](#2-bảng-màn-hình)
- [3. Bảng component dùng chung](#3-bảng-component-dùng-chung)
- [4. Bản đồ CSS](#4-bản-đồ-css)
- [5. Danh mục token trong theme.css](#5-danh-mục-token-trong-themecss)
- [6. Quy ước đang tồn tại trong code](#6-quy-ước-đang-tồn-tại-trong-code)
- [7. Điểm cần biết trước khi sửa UI](#7-điểm-cần-biết-trước-khi-sửa-ui)
- [8. Trạng thái build](#8-trạng-thái-build)

---

## 1. Bảng route

- Nguồn cấu hình router: [`frontend/src/App.jsx:57-192`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx#L57-L192)
- Nguồn bảo vệ route (Guard): [`frontend/src/components/ProtectedRoute.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ProtectedRoute.jsx)
- Nguồn điều hướng giao diện (Sidebar): [`frontend/src/components/layout/Sidebar.jsx:74-209`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/Sidebar.jsx#L74-L209)
- Hằng số định danh vai trò: [`frontend/src/constants/roles.js`](file:///Users/anhhao/Documents/SEP/frontend/src/constants/roles.js) (`ADMIN`, `FACILITY_MANAGER`, `GUARD`, `NORMAL_USER`)
- Trạng thái role cũ: Role `INTERNAL_GUARD` và `OUTSOURCED_GUARD` đã được gộp thành `GUARD` tại database migration V27. Trên toàn bộ mã nguồn frontend (`frontend/src/`), hai định danh cũ **không còn xuất hiện ở bất kỳ vị trí nào**.

| Path | Component page | File | Role được phép truy cập | Có trong Sidebar? | Ghi chú |
|---|---|---|---|---|---|
| `/login` | `LoginPage` | [`frontend/src/pages/LoginPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx) | Mọi người dùng (Public) | Không | Màn hình đăng nhập độc lập, giao diện nền tối cố định |
| `/unauthorized` | `UnauthorizedPage` | [`frontend/src/pages/UnauthorizedPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/UnauthorizedPage.jsx) | Mọi người dùng (Public) | Không | Màn hình báo lỗi không đủ quyền truy cập |
| `/dev/ui-kit` | `UiKitPage` | [`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) | Mọi người dùng (Dev only) | Không | Trang thư viện linh kiện mẫu (Dev preview mode) |
| `/` | `RootRoute` (Redirect) | [`frontend/src/App.jsx:27-33`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx#L27-L33) | Người dùng đã đăng nhập | Không | `NORMAL_USER` chuyển hướng sang `/access-requests`; các role khác chuyển sang `/dashboard` |
| `/dashboard` | `DashboardRoute` (`DashboardPage`) | [`frontend/src/pages/DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx) | `ADMIN`, `FACILITY_MANAGER`, `GUARD` | Có | `NORMAL_USER` truy cập route này sẽ tự động bị điều hướng sang `/access-requests` |
| `/admin/areas` | `AreaListPage` | [`frontend/src/pages/areas/AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx) | `ADMIN`, `FACILITY_MANAGER` | Có (Chỉ hiện cho `ADMIN`) | Trong `Sidebar.jsx`, link chỉ render khi `isAdmin = true`; `FACILITY_MANAGER` được phép vào theo route guard nhưng không có link trên menu |
| `/admin/areas/map` | `Navigate` (Redirect) | [`frontend/src/App.jsx:91-94`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx#L91-L94) | `ADMIN`, `FACILITY_MANAGER` | Không | Chuyển hướng nội bộ sang `/admin/areas?view=map` |
| `/cameras` | `CameraListPage` | [`frontend/src/pages/cameras/CameraListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx) | `ADMIN` | Có | Danh sách thiết bị camera |
| `/cameras/:id` | `CameraDetailPage` | [`frontend/src/pages/cameras/CameraDetailPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx) | `ADMIN` | Không | Màn hình chi tiết camera, điều hướng từ `CameraListPage` |
| `/admin/accounts` | `ManageAccountPage` | [`frontend/src/pages/accounts/ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx) | `ADMIN` | Có | Quản trị tài khoản người dùng, cán bộ và sinh viên |
| `/guard` | `GuardDashboardPage` | [`frontend/src/pages/guard/GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx) | `GUARD` | Có | Bàn làm việc giám sát an ninh trực tiếp của nhân viên bảo vệ |
| `/access-requests` | `AccessRequestPage` | [`frontend/src/pages/accessRequest/AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx) | `NORMAL_USER` | Có | Màn hình đăng ký và quản lý phiếu yêu cầu truy cập |
| `/access-history` | `AccessHistoryPage` | [`frontend/src/pages/accessHistory/AccessHistoryPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx) | `NORMAL_USER` | Có | Lịch sử quét nhận diện ra vào thực tế |
| `/notifications` | `NotificationsPage` | [`frontend/src/pages/notifications/NotificationsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx) | `NORMAL_USER` | Có | Trung tâm thông báo người dùng |
| `/admin/access-requests` | `AccessRequestReviewPage` | [`frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx) | `FACILITY_MANAGER`, `ADMIN` | Có (Chỉ hiện cho `FACILITY_MANAGER`) | Trong `Sidebar.jsx`, link chỉ render khi `isFacilityManager = true`; `ADMIN` được phép theo route guard nhưng không có link trên menu |
| `/admin/ai-settings` | `AiSettingsPage` | [`frontend/src/pages/ai/AiSettingsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx) | `ADMIN`, `FACILITY_MANAGER` | Có (Chỉ hiện cho `ADMIN`) | Trong `Sidebar.jsx`, mục "HỆ THỐNG" chỉ render khi `isAdmin = true` |
| `/admin/area-cameras` | `AreaCameraManagementPage` | [`frontend/src/pages/areas/AreaCameraManagementPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx) | `ADMIN`, `FACILITY_MANAGER` | Có (Chỉ hiện cho `ADMIN`) | Trong `Sidebar.jsx`, link chỉ render khi `isAdmin = true` |
| `*` | `RootRoute` (Fallback) | [`frontend/src/App.jsx:191`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx#L191) | Mọi người dùng | Không | Chuyển hướng tự động về trang chủ theo vai trò |

---

## 2. Bảng màn hình

Thống kê chi tiết 15 file page component trong `frontend/src/pages/`:

| Màn hình | File .jsx | File .css | Số dòng .jsx | Số dòng .css | Component ui/ đang dùng |
|---|---|---|---|---|---|
| `DashboardPage` | [`frontend/src/pages/DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx) | [`frontend/src/styles/DashboardPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/DashboardPage.css) | 479 | 698 | `DataTable` (bản local `data-table.jsx`) |
| `LoginPage` | [`frontend/src/pages/LoginPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx) | [`frontend/src/styles/LoginPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/LoginPage.css) | 398 | 423 | (không dùng) |
| `UnauthorizedPage` | [`frontend/src/pages/UnauthorizedPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/UnauthorizedPage.jsx) | [`frontend/src/styles/DashboardPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/DashboardPage.css) | 32 | 698 | (không dùng) |
| `UiKitPage` | [`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) | [`frontend/src/pages/_devPreview/UiKitPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.css) | 662 | 172 | `Badge`, `Button`, `Card`, `Input`, `Modal`, `Pagination`, `Select`, `Table` |
| `AccessHistoryPage` | [`frontend/src/pages/accessHistory/AccessHistoryPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx) | [`frontend/src/styles/AccessHistoryPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessHistoryPage.css) | 188 | 302 | (không dùng) |
| `AccessRequestPage` | [`frontend/src/pages/accessRequest/AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx) | [`frontend/src/styles/AccessRequestPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestPage.css) | 982 | 859 | (không dùng) |
| `AccessRequestReviewPage` | [`frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx) | [`frontend/src/styles/AccessRequestReviewPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestReviewPage.css) | 676 | 655 | (không dùng) |
| `ManageAccountPage` | [`frontend/src/pages/accounts/ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx) | [`frontend/src/styles/ManageAccountPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css) | 1990 | 1568 | (không dùng) |
| `AiSettingsPage` | [`frontend/src/pages/ai/AiSettingsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx) | [`frontend/src/styles/AiSettingsPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AiSettingsPage.css) | 238 | 404 | (không dùng) |
| `AreaCameraManagementPage` | [`frontend/src/pages/areas/AreaCameraManagementPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx) | [`frontend/src/styles/AreaCameraManagementPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaCameraManagementPage.css) | 314 | 509 | (không dùng) |
| `AreaListPage` | [`frontend/src/pages/areas/AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx) | [`frontend/src/styles/AreaListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css) | 1507 | 2302 | (không dùng) |
| `CameraDetailPage` | [`frontend/src/pages/cameras/CameraDetailPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx) | [`frontend/src/styles/CameraDetailPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraDetailPage.css) | 619 | 582 | (không dùng) |
| `CameraListPage` | [`frontend/src/pages/cameras/CameraListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx) | [`frontend/src/styles/CameraListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css) | 283 | 493 | (không dùng) |
| `GuardDashboardPage` | [`frontend/src/pages/guard/GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx) | [`frontend/src/styles/GuardDashboardPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/GuardDashboardPage.css) | 371 | 695 | (không dùng) |
| `NotificationsPage` | [`frontend/src/pages/notifications/NotificationsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx) | [`frontend/src/styles/NotificationsPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/NotificationsPage.css) | 174 | 282 | (không dùng) |

---

## 3. Bảng component dùng chung

Thống kê toàn bộ component tại `frontend/src/components/`:

| Component | File | Props công khai | Được import ở mấy nơi | Danh sách nơi import |
|---|---|---|---|---|
| `CameraCreateModal` | [`frontend/src/components/CameraCreateModal.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/CameraCreateModal.jsx) | `isOpen, onClose, onSuccess` | 1 | [`frontend/src/pages/cameras/CameraListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx) |
| `ProtectedRoute` | [`frontend/src/components/ProtectedRoute.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ProtectedRoute.jsx) | `children, allowedRoles` | 1 | [`frontend/src/App.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx) |
| `AppLayout` | [`frontend/src/components/layout/AppLayout.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/AppLayout.jsx) | `children` | 1 | [`frontend/src/App.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx) |
| `Sidebar` | [`frontend/src/components/layout/Sidebar.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/Sidebar.jsx) | `user, onLogout` | 1 | [`frontend/src/components/layout/AppLayout.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/AppLayout.jsx) |
| `Badge` | [`frontend/src/components/ui/Badge.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Badge.jsx) | `variant, icon: Icon, dot, className, children, ...rest` | 2 | [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js)<br>[`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) |
| `Button` | [`frontend/src/components/ui/Button.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Button.jsx) | `variant, size, icon: Icon, loading, disabled, onClick, type, className, children, ...rest` | 2 | [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js)<br>[`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) |
| `Card` | [`frontend/src/components/ui/Card.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Card.jsx) | `padding, accentColor, className, onClick, header, footer, children, style, ...rest` | 2 | [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js)<br>[`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) |
| `Input` | [`frontend/src/components/ui/Input.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Input.jsx) | `label, value, onChange, placeholder, error, hint, required, disabled, type, icon: Icon, id, name, className, ...rest` | 2 | [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js)<br>[`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) |
| `Modal` | [`frontend/src/components/ui/Modal.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Modal.jsx) | `isOpen, onClose, title, subtitle, icon: Icon, iconVariant, size, footer, closeOnBackdrop, className, children` | 2 | [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js)<br>[`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) |
| `Pagination` | [`frontend/src/components/ui/Pagination.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Pagination.jsx) | `currentPage, totalPages, totalElements, pageSize, onPageChange, itemLabel, className` | 2 | [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js)<br>[`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) |
| `Select` | [`frontend/src/components/ui/Select.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Select.jsx) | `label, value, onChange, options, placeholder, error, hint, required, disabled, id, name, className, ...rest` | 2 | [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js)<br>[`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) |
| `Table` | [`frontend/src/components/ui/Table.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Table.jsx) | `columns, data, loading, emptyText, emptyIcon: EmptyIcon, rowKey, onRowClick, className` | 2 | [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js)<br>[`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) |
| `DataTable` | [`frontend/src/components/ui/data-table.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/data-table.jsx) | `columns, data, className` | 1 | [`frontend/src/pages/DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx) |
| `Barrel index.js` | [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js) | `N/A (Tái xuất 8 UI components)` | 1 | [`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) |
| `WebRtcPlayer` | [`frontend/src/components/video/WebRtcPlayer.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/video/WebRtcPlayer.jsx) | `streamPath, host, autoPlay, muted, cameraName, onStatusChange` | 1 | [`frontend/src/pages/guard/GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx) |

*Ghi chú:* Toàn bộ 8 component trong `src/components/ui/` (`Button`, `Input`, `Select`, `Badge`, `Card`, `Modal`, `Table`, `Pagination`) hiện **chưa được tích hợp vào bất kỳ màn hình production nào** (chỉ đang phục vụ trang demo preview `UiKitPage.jsx`).

---

## 4. Bản đồ CSS

Toàn hệ thống có 28 file CSS. Chi tiết nạp và phạm vi ảnh hưởng:

| File CSS | Số dòng | Nạp ở đâu (import trong file nào) | Có scope không |
|---|---|---|---|
| [`frontend/src/components/ui/Badge.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Badge.css) | 122 | [`frontend/src/components/ui/Badge.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Badge.jsx) | Có (dùng prefix `.ui-badge`) |
| [`frontend/src/components/ui/Button.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Button.css) | 151 | [`frontend/src/components/ui/Button.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Button.jsx) | Có (dùng prefix `.ui-button`) |
| [`frontend/src/components/ui/Card.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Card.css) | 70 | [`frontend/src/components/ui/Card.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Card.jsx) | Có (dùng prefix `.ui-card`) |
| [`frontend/src/components/ui/Input.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Input.css) | 127 | [`frontend/src/components/ui/Input.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Input.jsx) | Có (dùng prefix `.ui-input`) |
| [`frontend/src/components/ui/Modal.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Modal.css) | 189 | [`frontend/src/components/CameraCreateModal.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/CameraCreateModal.jsx)<br>[`frontend/src/components/ui/Modal.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Modal.jsx) | Có (dùng prefix `.ui-modal`) |
| [`frontend/src/components/ui/Pagination.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Pagination.css) | 108 | [`frontend/src/components/ui/Pagination.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Pagination.jsx) | Có (dùng prefix `.ui-pagination`) |
| [`frontend/src/components/ui/Select.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Select.css) | 129 | [`frontend/src/components/ui/Select.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Select.jsx) | Có (dùng prefix `.ui-select`) |
| [`frontend/src/components/ui/Table.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Table.css) | 138 | [`frontend/src/components/ui/Table.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Table.jsx) | Có (dùng prefix `.ui-table`) |
| [`frontend/src/index.css`](file:///Users/anhhao/Documents/SEP/frontend/src/index.css) | 42 | [`frontend/src/main.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/main.jsx) | **Toàn cục (4 selector trần)** |
| [`frontend/src/pages/_devPreview/UiKitPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.css) | 172 | [`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) | Có (dùng prefix `.ui-kit-`) |
| [`frontend/src/styles/AccessHistoryPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessHistoryPage.css) | 302 | [`frontend/src/pages/accessHistory/AccessHistoryPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx) | Có (dùng prefix `.ahp-`) |
| [`frontend/src/styles/AccessRequestPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestPage.css) | 859 | [`frontend/src/pages/accessRequest/AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx) | Có (dùng prefix `.arp-`) |
| [`frontend/src/styles/AccessRequestReviewPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestReviewPage.css) | 655 | [`frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx) | Có (dùng prefix `.arr-`) |
| [`frontend/src/styles/AiSettingsPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AiSettingsPage.css) | 404 | [`frontend/src/pages/ai/AiSettingsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx) | Có (dùng prefix `.ai-`) |
| [`frontend/src/styles/AppLayout.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AppLayout.css) | 28 | [`frontend/src/components/layout/AppLayout.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/AppLayout.jsx) | Có (dùng prefix `.app-layout`) |
| [`frontend/src/styles/AreaCameraManagementPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaCameraManagementPage.css) | 509 | [`frontend/src/pages/areas/AreaCameraManagementPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx) | Có (dùng prefix `.transfer-`, `.area-`) |
| [`frontend/src/styles/AreaListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css) | 2302 | [`frontend/src/pages/areas/AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx) | Có (dùng prefix `.zone-`, `.area-`) |
| [`frontend/src/styles/CameraCreateModal.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraCreateModal.css) | 222 | [`frontend/src/components/CameraCreateModal.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/CameraCreateModal.jsx) | Có (dùng scope `.modal-overlay`, `.form-group`) |
| [`frontend/src/styles/CameraDetailPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraDetailPage.css) | 582 | [`frontend/src/pages/cameras/CameraDetailPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx) | **Toàn cục (1 selector trần)** |
| [`frontend/src/styles/CameraListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css) | 493 | [`frontend/src/pages/cameras/CameraListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx) | Có (dùng prefix `.camera-`, `.btn-`) |
| [`frontend/src/styles/DashboardPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/DashboardPage.css) | 698 | [`DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx)<br>[`UnauthorizedPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/UnauthorizedPage.jsx)<br>[`GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx) | Có (dùng prefix `.dashboard-`, `.dash-`) |
| [`frontend/src/styles/GuardDashboardPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/GuardDashboardPage.css) | 695 | [`frontend/src/pages/guard/GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx) | Có (dùng prefix `.guard-`, `.cam-`) |
| [`frontend/src/styles/LoginPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/LoginPage.css) | 423 | [`frontend/src/pages/LoginPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx) | Có (dùng prefix `.login-`) |
| [`frontend/src/styles/ManageAccountPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css) | 1568 | [`frontend/src/pages/accounts/ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx) | Có (dùng prefix `.account-`) |
| [`frontend/src/styles/NotificationsPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/NotificationsPage.css) | 282 | [`frontend/src/pages/notifications/NotificationsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx) | Có (dùng prefix `.notif-`) |
| [`frontend/src/styles/Sidebar.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/Sidebar.css) | 372 | [`frontend/src/components/layout/Sidebar.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/Sidebar.jsx) | Có (dùng prefix `.sidebar`) |
| [`frontend/src/styles/WebRtcPlayer.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/WebRtcPlayer.css) | 203 | [`frontend/src/components/video/WebRtcPlayer.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/video/WebRtcPlayer.jsx) | Có (dùng prefix `.player-`, `.cam-`) |
| [`frontend/src/styles/theme.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css) | 288 | Nạp toàn cục qua [`frontend/src/index.css:1`](file:///Users/anhhao/Documents/SEP/frontend/src/index.css#L1) | Token toàn cục + Utilities (`.btn-status--*`, `.input-focus`) |

### Danh sách selector toàn cục (Unscoped selectors có nguy cơ rò rỉ):
1. [`frontend/src/index.css:17`](file:///Users/anhhao/Documents/SEP/frontend/src/index.css#L17): `html` (Reset thuộc tính font, box-sizing, smooth scroll).
2. [`frontend/src/index.css:23`](file:///Users/anhhao/Documents/SEP/frontend/src/index.css#L23): `body` (Gán background, font-family, antialiased).
3. [`frontend/src/index.css:35`](file:///Users/anhhao/Documents/SEP/frontend/src/index.css#L35): `a` (Reset text-decoration, color inherit).
4. [`frontend/src/index.css:40`](file:///Users/anhhao/Documents/SEP/frontend/src/index.css#L40): `button` (Reset font-family inherit).
5. [`frontend/src/styles/CameraDetailPage.css:578`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraDetailPage.css#L578): `input[type="datetime-local"]::-webkit-calendar-picker-indicator` (Áp dụng bộ lọc đổi màu icon lịch cho mọi ô input datetime-local toàn ứng dụng sau khi màn hình camera detail được nạp).

---

## 5. Danh mục token trong theme.css

Định nghĩa tại [`frontend/src/styles/theme.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css).  
Phân tích chi tiết `--theme-primary` trỏ sang 👉 [`docs/theme-primary-audit.md`](file:///Users/anhhao/Documents/SEP/docs/theme-primary-audit.md).

| Token | Giá trị :root | Giá trị [data-theme="dark"] | Số nơi dùng | Nhóm |
|---|---|---|---|---|
| `--brand-blue` | `#3ba8d9` | `#3ba8d9` | 55 | `brand` |
| `--brand-cyan` | `#7ed3f2` | `#7ed3f2` | 9 | `brand` |
| `--brand-glow` | `rgba(59, 168, 217, 0.35)` | `rgba(59, 168, 217, 0.35)` | 28 | `brand` |
| `--brand-gradient` | `linear-gradient(135deg, #7ed3f2 0%, #3ba8d9 55%, #7c5cd6 100%)` | `linear-gradient(135deg, #7ed3f2 0%, #3ba8d9 55%, #7c5cd6 100%)` | 22 | `brand` |
| `--brand-on-gradient` | `#ffffff` | `#0d1117` | 17 | `brand` |
| `--brand-subtle` | `rgba(59, 168, 217, 0.12)` | `rgba(59, 168, 217, 0.12)` | 54 | `brand` |
| `--brand-subtle-border` | `rgba(59, 168, 217, 0.25)` | `rgba(59, 168, 217, 0.25)` | 17 | `brand` |
| `--brand-text` | `#0e7490` | `#7ed3f2` | 76 | `brand` |
| `--brand-text-strong` | `#155e75` | `#a5e3f7` | 6 | `brand` |
| `--brand-violet` | `#7c5cd6` | `#7c5cd6` | 1 | `brand` |
| `--focus-ring` | `0 0 0 2px var(--brand-glow)` | *(kế thừa :root)* | 17 | `focus` |
| `--theme-active-dot` | `#16a34a` | `#34d399` | 3 | `theme` |
| `--theme-ambient-opacity` | `0.12` | `0.22` | 2 | `theme` |
| `--theme-bg-canvas` | `#f8fafc` | `#090d16` | 10 | `theme` |
| `--theme-bg-desc` | `#f8fafc` | `#1e293b` | 20 | `theme` |
| `--theme-bg-input` | `#ffffff` | `#1e293b` | 28 | `theme` |
| `--theme-bg-page` | `#f8fafc` | `#0b0f19` | 23 | `theme` |
| `--theme-bg-panel-right` | `#ffffff` | `#0f172a` | 1 | `theme` |
| `--theme-bg-sidebar` | `#ffffff` | `#0f172a` | 2 | `theme` |
| `--theme-bg-surface` | `#ffffff` | `#111827` | 156 | `theme` |
| `--theme-bg-surface-elevated` | `#ffffff` | `#1f2937` | 78 | `theme` |
| `--theme-border` | `#e2e8f0` | `#1e293b` | 242 | `theme` |
| `--theme-border-focus` | `#3b82f6` | `#60a5fa` | 0 ⚠️ *(Chưa dùng)* | `focus` |
| `--theme-border-hover` | `#cbd5e1` | `#334155` | 44 | `theme` |
| `--theme-canvas-dot` | `rgba(148, 163, 184, 0.35)` | `rgba(71, 85, 105, 0.35)` | 1 | `theme` |
| `--theme-card-shadow` | `0 1px 3px rgba(0, 0, 0, 0.05)...` | `0 4px 12px rgba(0, 0, 0, 0.35)` | 42 | `theme` |
| `--theme-card-shadow-hover` | `0 8px 16px -2px rgba(0, 0, 0, 0.08)...` | `0 10px 24px rgba(0, 0, 0, 0.5)` | 8 | `theme` |
| `--theme-danger` | `#ef4444` | `#f87171` | 139 | `theme` |
| `--theme-danger-bg` | `#fef2f2` | `rgba(239, 68, 68, 0.12)` | 34 | `theme` |
| `--theme-danger-border` | `#fca5a5` | `rgba(248, 113, 113, 0.35)` | 30 | `theme` |
| `--theme-danger-text` | `#991b1b` | `#fca5a5` | 30 | `theme` |
| `--theme-inactive-dot` | `#dc2626` | `#f87171` | 3 | `theme` |
| `--theme-info` | `#0284c7` | `#38bdf8` | 6 | `theme` |
| `--theme-info-bg` | `#f0f9ff` | `rgba(2, 132, 199, 0.12)` | 2 | `theme` |
| `--theme-info-border` | `#bae6fd` | `rgba(56, 189, 248, 0.35)` | 2 | `theme` |
| `--theme-info-text` | `#0369a1` | `#7dd3fc` | 2 | `theme` |
| `--theme-input-border` | `#cbd5e1` | `#334155` | 22 | `theme` |
| `--theme-level-priv-badge-bg` | `#fee2e2` | `rgba(239, 68, 68, 0.2)` | 0 ⚠️ *(Chưa dùng)* | `theme` |
| `--theme-level-priv-bg` | `#fef2f2` | `rgba(239, 68, 68, 0.08)` | 1 | `theme` |
| `--theme-level-priv-border` | `#fca5a5` | `rgba(248, 113, 113, 0.35)` | 1 | `theme` |
| `--theme-level-priv-text` | `#991b1b` | `#fca5a5` | 1 | `theme` |
| `--theme-level-pub-badge-bg` | `#dcfce7` | `rgba(16, 185, 129, 0.2)` | 0 ⚠️ *(Chưa dùng)* | `theme` |
| `--theme-level-pub-bg` | `#f0fdf4` | `rgba(16, 185, 129, 0.08)` | 1 | `theme` |
| `--theme-level-pub-border` | `#86efac` | `rgba(52, 211, 153, 0.35)` | 1 | `theme` |
| `--theme-level-pub-text` | `#166534` | `#6ee7b7` | 1 | `theme` |
| `--theme-level-semi-badge-bg` | `#fef3c7` | `rgba(245, 158, 11, 0.2)` | 0 ⚠️ *(Chưa dùng)* | `theme` |
| `--theme-level-semi-bg` | `#fffbeb` | `rgba(245, 158, 11, 0.08)` | 1 | `theme` |
| `--theme-level-semi-border` | `#fcd34d` | `rgba(251, 191, 36, 0.35)` | 1 | `theme` |
| `--theme-level-semi-text` | `#92400e` | `#fcd34d` | 1 | `theme` |
| `--theme-modal-backdrop` | `rgba(15, 23, 42, 0.45)` | `rgba(0, 0, 0, 0.7)` | 4 | `theme` |
| `--theme-modal-bg` | `#ffffff` | `#111827` | 12 | `theme` |
| `--theme-on-brand` | `var(--brand-on-gradient)` | *(kế thừa :root)* | 5 | `on-solid` |
| `--theme-on-danger` | `#0d1117` | *(kế thừa :root)* | 5 | `on-solid` |
| `--theme-on-info` | `#0d1117` | *(kế thừa :root)* | 0 ⚠️ *(Chưa dùng)* | `on-solid` |
| `--theme-on-success` | `#0d1117` | *(kế thừa :root)* | 5 | `on-solid` |
| `--theme-on-warning` | `#0d1117` | *(kế thừa :root)* | 1 | `on-solid` |
| `--theme-primary` | `#2563eb` | `#3b82f6` | 36 | `theme` |
| `--theme-primary-border` | `#bfdbfe` | `rgba(59, 130, 246, 0.3)` | 6 | `theme` |
| `--theme-primary-hover` | `#1d4ed8` | `#2563eb` | 3 | `theme` |
| `--theme-primary-light` | `#eff6ff` | `rgba(59, 130, 246, 0.15)` | 8 | `theme` |
| `--theme-primary-subtle` | `rgba(37, 99, 235, 0.08)` | `rgba(59, 130, 246, 0.12)` | 2 | `theme` |
| `--theme-success` | `#16a34a` | `#34d399` | 82 | `theme` |
| `--theme-success-bg` | `#f0fdf4` | `rgba(16, 185, 129, 0.12)` | 20 | `theme` |
| `--theme-success-border` | `#86efac` | `rgba(52, 211, 153, 0.35)` | 20 | `theme` |
| `--theme-success-text` | `#166534` | `#6ee7b7` | 18 | `theme` |
| `--theme-text-disabled` | `#94a3b8` | `#64748b` | 12 | `theme` |
| `--theme-text-muted` | `#64748b` | `#94a3b8` | 146 | `theme` |
| `--theme-text-primary` | `#0f172a` | `#f8fafc` | 183 | `theme` |
| `--theme-text-secondary` | `#475569` | `#cbd5e1` | 91 | `theme` |
| `--theme-warning` | `#f59e0b` | `#fbbf24` | 37 | `theme` |
| `--theme-warning-bg` | `#fffbeb` | `rgba(245, 158, 11, 0.12)` | 12 | `theme` |
| `--theme-warning-border` | `#fcd34d` | `rgba(251, 191, 36, 0.35)` | 9 | `theme` |
| `--theme-warning-text` | `#92400e` | `#fcd34d` | 8 | theme |

*Tổng số token khai báo:* 73 token.  
*Số token chưa nơi nào sử dụng (0 nơi dùng):* 5 token (`--theme-border-focus`, `--theme-level-priv-badge-bg`, `--theme-level-pub-badge-bg`, `--theme-level-semi-badge-bg`, `--theme-on-info`).

---

## 6. Quy ước đang tồn tại trong code

### A. Quy ước đặt tên class CSS
- **BEM có prefix theo màn hình (Chiếm đa số - 10 file):**
  - Prefix `arp-`: [`frontend/src/styles/AccessRequestPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestPage.css) (`.arp-card`, `.arp-form-group`, `.arp-input`)
  - Prefix `arr-`: [`frontend/src/styles/AccessRequestReviewPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestReviewPage.css) (`.arr-search-input`, `.arr-btn--approve-modal`)
  - Prefix `ahp-`: [`frontend/src/styles/AccessHistoryPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessHistoryPage.css) (`.ahp-filter-btn`, `.ahp-select`)
  - Prefix `zone-` & `area-`: [`frontend/src/styles/AreaListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css) (`.zone-toolbar__add`, `.area-sidebar__nav-item`)
  - Prefix `account-`: [`frontend/src/styles/ManageAccountPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css) (`.account-table`, `.account-tab-btn--active`)
  - Prefix `ai-`: [`frontend/src/styles/AiSettingsPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AiSettingsPage.css) (`.ai-settings-card`, `.ai-fps-input`)
  - Prefix `ui-`: Toàn bộ 8 file trong `src/components/ui/` (`.ui-button`, `.ui-input__field`)
- **Class tên generic không có prefix (Nguy cơ xung đột cao - 3 file):**
  - [`frontend/src/styles/DashboardPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/DashboardPage.css): `.dashboard`, `.header`, `.title`, `.stat-card`, `.badge`, `.status-pill`
  - [`frontend/src/styles/GuardDashboardPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/GuardDashboardPage.css): `.active`, `.disabled`, `.camera-grid`, `.tab-btn`
  - [`frontend/src/styles/CameraListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css): `.search-box`, `.filter-dropdowns`, `.font-mono`

### B. Quy ước đặt tên file
- **PascalCase (.jsx):** 14/15 page components (`DashboardPage.jsx`, `AreaListPage.jsx`...) và 13/14 component files (`Button.jsx`, `Sidebar.jsx`...).
- **kebab-case (.jsx):** Đúng 1 component: [`frontend/src/components/ui/data-table.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/data-table.jsx).
- **lowercase (.js):** [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js).
- **camelCase (.js):** Toàn bộ file trong `src/services/` (`areaService.js`, `cameraService.js`, `userService.js`) và `src/store/` (`useUiStore.js`).

### C. Cách gọi API
- **Service Layer pattern (11 trang):** Tách hàm gọi HTTP vào `src/services/` sử dụng instance `apiClient` (`axios`).  
  *Ví dụ:* [`ManageAccountPage.jsx:26`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L26) import `userService`, [`CameraListPage.jsx:11`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L11) import `cameraService`.
- **TanStack React Query kết hợp Service (1 trang):**  
  *Ví dụ:* [`DashboardPage.jsx:21`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx#L21) dùng `useQuery` bọc quanh `areaService.getDashboardSummary()`.
- **Dữ liệu tĩnh / Mock tại chỗ (không gọi API - 3 trang):**  
  `AccessHistoryPage.jsx` (L15-46 mảng mock data), `NotificationsPage.jsx` (L11-54 mock data), `UiKitPage.jsx`.

### D. Cách xử lý loading state
- **Spinner CSS xoay vòng (6 trang):**  
  *Ví dụ:* [`AreaListPage.jsx:1367`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L1367) (`.zone-loading-spinner`), [`CameraListPage.jsx:153`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L153) (`.camera-spinner`).
- **Khối văn bản "Đang tải dữ liệu..." (10 trang):**  
  *Ví dụ:* [`AccessRequestReviewPage.jsx:455`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx#L455), [`AccessRequestPage.jsx:623`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L623).
- **Skeleton Shimmer Loading (2 trang):**  
  *Ví dụ:* [`ManageAccountPage.jsx:1140`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L1140) (`.account-skeleton-row`), [`UiKitPage.jsx:572`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx#L572).
- **Không có chỉ báo loading (2 trang):** `UnauthorizedPage.jsx`, `GuardDashboardPage.jsx`.

### E. Cách xử lý lỗi và thông báo
- **State Inline Alert Box (11 trang):** Khai báo `const [error, setError] = useState(null)` và render thẻ div thông báo lỗi cục bộ trên đầu trang/form.  
  *Ví dụ:* [`ManageAccountPage.jsx:61`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L61), [`AreaListPage.jsx:66`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L66).
- **Toast thông báo (Sonner Toast) (2 trang):**  
  *Ví dụ:* [`ManageAccountPage.jsx:413`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L413) (`toast.success()`), [`LoginPage.jsx:89`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L89) (`toast.error()`).
- **Hộp thoại trình duyệt native `window.alert()` (2 trang):**  
  *Ví dụ:* [`CameraListPage.jsx:111`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L111) (`alert('Xóa camera thành công!')`), [`UiKitPage.jsx:266`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx#L266).

### F. Cách quản lý form
- **Thư viện chuẩn `react-hook-form` + `zod` (Chỉ 1 trang):**  
  *Ví dụ:* [`LoginPage.jsx:31`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L31) dùng `useForm` và `zodResolver`.
- **Tập trung `useState` dạng Object (1 trang):**  
  *Ví dụ:* [`AreaListPage.jsx:92`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L92) dùng `const [zoneFormData, setZoneFormData] = useState({...})`.
- **Phân tán hàng chục `useState` rời rạc (11 trang - nguy cơ re-render và khó bảo trì):**  
  - [`ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx): **38 hook `useState` riêng lẻ** cho từng trường input!
  - [`AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx): **22 hook `useState` riêng lẻ**.
  - [`AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx): **22 hook `useState` riêng lẻ**.
  - [`AccessRequestReviewPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx): **15 hook `useState` riêng lẻ**.

### G. Cách đọc vai trò người dùng trong component
- **Đọc từ context `useAuth()` (3 trang + Sidebar):**  
  *Ví dụ:* [`DashboardPage.jsx:28`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx#L28) (`const { user } = useAuth(); user?.role`), [`AreaListPage.jsx:54`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L54), [`Sidebar.jsx:42`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/Sidebar.jsx#L42) (`user?.role || user?.role_type`).
- **Uỷ thác hoàn toàn cho `ProtectedRoute` tại router (11 trang):** Hầu hết các trang không kiểm tra role nội bộ mà dựa vào prop `allowedRoles` tại [`App.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/App.jsx).

---

## 7. Điểm cần biết trước khi sửa UI

### A. Inline Style trong file `.jsx`
Toàn dự án có **195 vị trí** dùng `style={{ ... }}` trong 15 file `.jsx`. Phân bố tập trung:
- [`frontend/src/pages/accounts/ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx): **95 vị trí** (nhiều inline styles điều khiển border, padding, color, width).
  - Vị trí quan trọng: L1031 (`border: '2px solid var(--theme-primary, #3b82f6)'`), L1039 (`color: 'var(--theme-primary, #3b82f6)'`), L1367 (`border: bulkZipFile ? '2px solid var(--theme-primary, #3b82f6)' : ...`).
- [`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx): **21 vị trí**.
- [`frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx): **20 vị trí** (L442, L483, L510...).
- [`frontend/src/pages/accessRequest/AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx): **16 vị trí** (L584, L939, L945...).
- [`frontend/src/pages/cameras/CameraDetailPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx): **10 vị trí** (L450, L475, L510...).
- [`frontend/src/pages/UnauthorizedPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/UnauthorizedPage.jsx): **6 vị trí** (L18, L19, L20, L21, L22, L25).
- [`frontend/src/pages/guard/GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx): **5 vị trí**.
- [`frontend/src/pages/DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx): **5 vị trí**.
- [`frontend/src/components/ProtectedRoute.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ProtectedRoute.jsx): **4 vị trí** (L9, L18, L19, L28 - inline styles cho spinner loading).
- [`frontend/src/context/AuthContext.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/context/AuthContext.jsx): **4 vị trí** (L99, L108, L109, L118 - inline styles cho loading screen).
- [`frontend/src/components/ui/Table.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Table.jsx): **4 vị trí** (L53, L72 - inline styles căn lề cột `textAlign`).
- [`frontend/src/pages/areas/AreaListPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx): **3 vị trí**.
- [`frontend/src/pages/accessHistory/AccessHistoryPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx): **1 vị trí**.
- [`frontend/src/pages/ai/AiSettingsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx): **1 vị trí**.
- [`frontend/src/pages/LoginPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx): **1 vị trí**.

### B. File CSS vượt ngưỡng 1000 dòng
Hai file CSS có quy mô rất lớn, nguy cơ xung đột và khó kiểm soát hiển thị cao:
1. [`frontend/src/styles/AreaListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css): **2.302 dòng** (giao diện vẽ polygon canvas, sidebar, floor tabs, drawer chi tiết).
2. [`frontend/src/styles/ManageAccountPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css): **1.568 dòng** (bảng dữ liệu, dropzone tải ảnh, modal thêm tài khoản, modal import hàng loạt).

### C. Component nhận vượt quá 8 props
Các component có chữ ký hàm nhận nhiều hơn 8 props:
1. [`frontend/src/components/ui/Input.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Input.jsx): **14 props** (`label, value, onChange, placeholder, error, hint, required, disabled, type, icon, id, name, className, ...rest`)
2. [`frontend/src/components/ui/Select.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Select.jsx): **13 props** (`label, value, onChange, options, placeholder, error, hint, required, disabled, id, name, className, ...rest`)
3. [`frontend/src/components/ui/Modal.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Modal.jsx): **11 props** (`isOpen, onClose, title, subtitle, icon, iconVariant, size, footer, closeOnBackdrop, className, children`)
4. [`frontend/src/components/ui/Button.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Button.jsx): **10 props** (`variant, size, icon, loading, disabled, onClick, type, className, children, ...rest`)
5. [`frontend/src/components/ui/Card.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Card.jsx): **9 props** (`padding, accentColor, className, onClick, header, footer, children, style, ...rest`)

### D. Số lượng sử dụng `!important` trong CSS
Tổng cộng có **43 vị trí** sử dụng cờ `!important` trong CSS (chủ yếu nhằm ghi đè các trạng thái `active`, `hover`, `focus` hoặc modal):
- `theme.css`: 2 vị trí (L285, L286 trong `.input-focus`).
- `CameraListPage.css`: 2 vị trí (L233, L433).
- `AreaListPage.css`: 14 vị trí (L339, L453, L454, L817, L1497, L1498, L2014, L2015...).
- `ManageAccountPage.css`: 17 vị trí (L153, L154, L656, L658, L1158, L1162...).
- `AccessRequestReviewPage.css`: 8 vị trí (L532, L533, L534, L541, L542, L543...).

### E. Thang bậc `z-index` trong CSS
Hệ thống hiện sử dụng **7 bậc `z-index` cứng** (sắp xếp tăng dần):
1. `z-index: 0` (3 nơi dùng): Nền canvas/orb mờ (ví dụ: [`CameraListPage.css:20`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css#L20)).
2. `z-index: 1` (13 nơi dùng): Lớp phủ thông thường, icon đặt bên trong ô input (ví dụ: [`CameraListPage.css:50`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css#L50), [`Input.css:47`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Input.css#L47)).
3. `z-index: 5` (1 nơi dùng): Lớp điều khiển nổi trên luồng video (ví dụ: [`WebRtcPlayer.css:136`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/WebRtcPlayer.css#L136)).
4. `z-index: 10` (2 nơi dùng): Thanh công cụ cố định trên canvas bản đồ (ví dụ: [`AreaListPage.css:62`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L62), [`AreaListPage.css:379`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L379)).
5. `z-index: 100` (3 nơi dùng): Dropdown filter thả xuống, thanh thông tin nổi (ví dụ: [`CameraDetailPage.css:540`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraDetailPage.css#L540)).
6. `z-index: 1000` (6 nơi dùng): Lớp phủ nền mờ modal (backdrop) và drawer kéo ra (ví dụ: [`AreaListPage.css:866`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L866), [`Modal.css:16`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Modal.css#L16)).
7. `z-index: 2000` (2 nơi dùng): Modal xác nhận cấp cao / popup thông báo đè lên modal (ví dụ: [`AreaListPage.css:1320`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L1320)).

---

## 8. Trạng thái build

Kiểm tra tại thư mục `frontend/`:

### A. TypeScript Typecheck
- Lệnh thực thi: `npm run typecheck` (`tsc --noEmit`)
- Kết quả: **PASS (Exit code: 0)**
- Số lỗi: **0 lỗi**

### B. Vite Production Build
- Lệnh thực thi: `npm run build` (`vite build`)
- Kết quả: **PASS (Exit code: 0)**
- Thời gian build: ~320ms
- Kích thước bundle:
  - `dist/index.html`: `0.69 kB` (gzip: `0.47 kB`)
  - `dist/assets/index-*.css`: `197.73 kB` (gzip: `29.24 kB`)
  - `dist/assets/index-*.js`: `1,110.78 kB` (gzip: `312.95 kB`)
- Cảnh báo (Warning):
  `(!) Some chunks are larger than 500 kB after minification. Consider using dynamic import() to code-split the application (dist/assets/index-*.js is 1,110.78 kB).`

---

*Dữ liệu lập chỉ mục tính đến commit:* `3e5fb51`  
*Ngày tạo:* 16/09/2026
