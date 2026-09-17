# Chỉ mục cấu trúc UI frontend

> File tra cứu kỹ thuật dành cho AI agents trong các phiên làm việc tiếp theo.  
> Không chứa đánh giá chủ quan hay đề xuất. Chỉ phản ánh dữ liệu hiện trạng mã nguồn.

---

## Mục lục
- [Đính chính so với bản commit c73fba2](#đính-chính-so-với-bản-commit-c73fba2)
- [1. Bảng route](#1-bảng-route)
- [2. Bảng màn hình](#2-bảng-màn-hình)
- [3. Bảng component dùng chung](#3-bảng-component-dùng-chung)
- [4. Bản đồ CSS](#4-bản-đồ-css)
- [5. Danh mục token trong theme.css](#5-danh-mục-token-trong-themecss)
- [6. Quy ước đang tồn tại trong code](#6-quy-ước-đang-tồn-tại-trong-code)
- [7. Điểm cần biết trước khi sửa UI](#7-điểm-cần-biết-trước-khi-sửa-ui)
- [8. Điểm chưa xác minh được](#8-điểm-chưa-xác-minh-được)
- [9. Trạng thái build](#9-trạng-thái-build)

---

## Đính chính so với bản commit `c73fba2`

| Số cũ | Số đúng |
|---|---|
| Sonner toast 2 trang | 1 trang ([`LoginPage.jsx:7, 59`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L7)). [`ManageAccountPage.jsx:273`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L273) dùng hàm `showToast()` tự viết với state nội bộ, render `.account-toast` tại [`ManageAccountPage.jsx:517`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L517), không phải Sonner |
| Không có chỉ báo loading: 2 trang | 1 trang ([`UnauthorizedPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/UnauthorizedPage.jsx)). [`GuardDashboardPage.jsx:26, 241`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L26) có chỉ báo loading qua state `camerasLoading` |
| Form dạng object tập trung: 1 trang | 2 trang ([`AreaListPage.jsx:92, 112`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L92) có `zoneFormData` và `formData`; [`ManageAccountPage.jsx:78-83`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L78-L83) có `createForm`) |
| 195 inline style | 196 vị trí `style={{` trên toàn dự án (71 vị trí chứa giá trị màu, 125 vị trí thuần bố cục/kích thước) |

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
| `DashboardPage` | [`frontend/src/pages/DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx) | [`frontend/src/styles/DashboardPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/DashboardPage.css) | 480 | 698 | `DataTable` (bản local `data-table.jsx`) |
| `LoginPage` | [`frontend/src/pages/LoginPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx) | [`frontend/src/styles/LoginPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/LoginPage.css) | 398 | 423 | (không dùng) |
| `UnauthorizedPage` | [`frontend/src/pages/UnauthorizedPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/UnauthorizedPage.jsx) | [`frontend/src/styles/DashboardPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/DashboardPage.css) | 32 | 698 | (không dùng) |
| `UiKitPage` | [`frontend/src/pages/_devPreview/UiKitPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx) | [`frontend/src/pages/_devPreview/UiKitPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.css) | 663 | 172 | `Badge`, `Button`, `Card`, `Input`, `Modal`, `Pagination`, `Select`, `Table` |
| `AccessHistoryPage` | [`frontend/src/pages/accessHistory/AccessHistoryPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx) | [`frontend/src/styles/AccessHistoryPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessHistoryPage.css) | 188 | 302 | (không dùng) |
| `AccessRequestPage` | [`frontend/src/pages/accessRequest/AccessRequestPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx) | [`frontend/src/styles/AccessRequestPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestPage.css) | 982 | 859 | (không dùng) |
| `AccessRequestReviewPage` | [`frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx) | [`frontend/src/styles/AccessRequestReviewPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestReviewPage.css) | 676 | 655 | (không dùng) |
| `ManageAccountPage` | [`frontend/src/pages/accounts/ManageAccountPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx) | [`frontend/src/styles/ManageAccountPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css) | 1991 | 1568 | (không dùng) |
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
| [`frontend/src/components/ui/Modal.css`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Modal.css) | 189 | [`frontend/src/components/ui/Modal.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Modal.jsx) | Có (dùng prefix `.ui-modal`) |
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
| `--theme-warning-text` | `#92400e` | `#fcd34d` | 8 | `theme` |

### Phân tích token chưa dùng và hiện trạng dead code
1. **5 token hoàn toàn chưa có nơi nào sử dụng (0 nơi dùng toàn hệ thống):**
   - `--theme-border-focus` ([`theme.css:41`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css#L41))
   - `--theme-level-priv-badge-bg` ([`theme.css:76`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css#L76))
   - `--theme-level-pub-badge-bg` ([`theme.css:74`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css#L74))
   - `--theme-level-semi-badge-bg` ([`theme.css:75`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css#L75))
   - `--theme-on-info` ([`theme.css:98`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css#L98)) — không có nơi dùng ngoài `theme.css`, và cũng không có nơi dùng bên trong `theme.css`.
2. **Hiện trạng nhóm token `--theme-on-*`:**
   - `--theme-on-brand`: **5 nơi dùng** ngoài `theme.css` ([`AreaListPage.css:90, 192`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L90), [`Sidebar.css:119, 233, 298`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/Sidebar.css#L119)).
   - `--theme-on-danger`: **4 nơi dùng** ngoài `theme.css` ([`AreaListPage.css:1222, 2209, 2233`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L1222), [`AccessRequestReviewPage.css:545`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestReviewPage.css#L545)).
   - `--theme-on-success`: **4 nơi dùng** ngoài `theme.css` ([`CameraListPage.css:425`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css#L425), [`AreaListPage.css:1794`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L1794), [`CameraDetailPage.css:198`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraDetailPage.css#L198), [`AccessRequestReviewPage.css:534`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AccessRequestReviewPage.css#L534)).
   - `--theme-on-warning`: **0 nơi dùng** ngoài `theme.css` (chỉ được tham chiếu 1 lần bên trong [`theme.css:269`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css#L269) cho utility class `.btn-status--warning`).
   - `--theme-on-info`: **0 nơi dùng** toàn diện (cả trong và ngoài `theme.css`).
3. **Hiện trạng lớp utility class trong `theme.css`:**
   - Class `.btn-status--*` (`.btn-status--success`, `.btn-status--danger`, `.btn-status--warning` tại [`theme.css:216-277`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css#L216-L277)): **0 nơi dùng** trong mã nguồn JSX/CSS khác.
   - Class `.input-focus` / `.focus-ring:focus` ([`theme.css:282-287`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/theme.css#L282-L287)): **0 nơi dùng** trong mã nguồn JSX/CSS khác.
   - *Kết luận:* Các token `--theme-on-brand`, `--theme-on-danger`, `--theme-on-success` đã được các file CSS màn hình nhận nuôi trực tiếp. Ngược lại, hai utility `.btn-status--*`, `.input-focus` và token `--theme-on-warning`, `--theme-on-info` là dead code thực tế.

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
- **PascalCase (.jsx):** **15/15 (100%)** page components (`DashboardPage.jsx`, `LoginPage.jsx`, `UnauthorizedPage.jsx`, `UiKitPage.jsx`, `AccessHistoryPage.jsx`, `AccessRequestPage.jsx`, `AccessRequestReviewPage.jsx`, `ManageAccountPage.jsx`, `AiSettingsPage.jsx`, `AreaCameraManagementPage.jsx`, `AreaListPage.jsx`, `CameraDetailPage.jsx`, `CameraListPage.jsx`, `GuardDashboardPage.jsx`, `NotificationsPage.jsx`).
- **PascalCase (.jsx) component:** 13/14 component files (`Badge.jsx`, `Button.jsx`, `Card.jsx`, `Input.jsx`, `Modal.jsx`, `Pagination.jsx`, `Select.jsx`, `Table.jsx`, `CameraCreateModal.jsx`, `ProtectedRoute.jsx`, `AppLayout.jsx`, `Sidebar.jsx`, `WebRtcPlayer.jsx`).
- **kebab-case (.jsx):** Đúng 1 component ngoại lệ: [`frontend/src/components/ui/data-table.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/data-table.jsx).
- **lowercase (.js):** [`frontend/src/components/ui/index.js`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/index.js).
- **camelCase (.js):** Toàn bộ file trong `src/services/` (`areaService.js`, `cameraService.js`, `userService.js`) và `src/store/` (`useUiStore.js`).

### C. Cách gọi API
- **Service Layer pattern (11 trang):** Tách hàm gọi HTTP vào `src/services/` sử dụng instance `apiClient` (`axios`).  
  *Ví dụ:* [`ManageAccountPage.jsx:26`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L26) import `userService`, [`CameraListPage.jsx:11`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L11) import `cameraService`.
- **TanStack React Query kết hợp Service (1 trang):**  
  *Ví dụ:* [`DashboardPage.jsx:30`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx#L30) dùng `useQuery` bọc quanh `areaService.getAreas()`.
- **Dữ liệu tĩnh / Mock tại chỗ (không gọi API - 3 trang):**  
  [`AccessHistoryPage.jsx:15-46`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx#L15-L46) (mảng mock data), [`NotificationsPage.jsx:11-54`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx#L11-L54) (mock data), `UiKitPage.jsx`.

### D. Bảng đối chiếu hiện trạng 15 màn hình (Loading, Thông báo, Form, Role)

| Page | Loading | Thông báo lỗi / thành công | Quản lý form | Đọc role |
|---|---|---|---|---|
| `DashboardPage` | TanStack Query `isLoading: areaLoading` ([L30](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx#L30)), text `"..."` ([L122](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx#L122)), text `"Đang tải dữ liệu..."` ([L305](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx#L305)) | `không có` | `không có` (Trang chỉ đọc; 6 `useState` lưu cờ kết nối module và tab KPI [L41](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx#L41)) | Context `useAuth()` ([L28](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx#L28)), so sánh `user?.role === 'ADMIN' \|\| user?.role === 'FACILITY_MANAGER'` ([L29](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx#L29)) để bật `enabled` query |
| `LoginPage` | State `loading` ([L30](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L30)), text `"Đang đăng nhập..."` ([L169](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L169)) | Thư viện Sonner `toast.success()` ([L59](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L59)), `toast.error()` ([L89](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L89)); State inline alert `error` ([L29](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L29), render [L129](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L129)) | Thư viện `react-hook-form` + `zodResolver` ([L33-40](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L33-L40)); 11 `useState` cho mode và tab đăng nhập ([L25](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L25)) | Đọc từ phản hồi backend `res?.user?.role` ([L57](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L57)) và tham số bypass `handleRoleBypass(role)` ([L72](file:///Users/anhhao/Documents/SEP/frontend/src/pages/LoginPage.jsx#L72)) để phân luồng điều hướng |
| `UnauthorizedPage` | `không có` | `không có` (Thông báo tĩnh 403 trong JSX [L16](file:///Users/anhhao/Documents/SEP/frontend/src/pages/UnauthorizedPage.jsx#L16)) | `không có` | `không có` |
| `UiKitPage` | Props loading mẫu của component `Button` ([L221](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx#L221)), skeleton demo của `Table` ([L572](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx#L572)) | Trình duyệt native `alert()` ([L266](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx#L266)); Props error/success mẫu của `Input` ([L267](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx#L267)), `Select` ([L305](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx#L305)), `Badge` ([L342](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx#L342)) | Phân tán 6 `useState` lưu giá trị tương tác mẫu của input, select, modal ([L39-44](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx#L39-L44)) | `không có` (Chỉ chứa mảng tĩnh `roleOptions` cho select demo [L147](file:///Users/anhhao/Documents/SEP/frontend/src/pages/_devPreview/UiKitPage.jsx#L147)) |
| `AccessHistoryPage` | State `loading` ([L13](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx#L13)), icon quay `.ahp-spin` ([L105](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx#L105)), text `"Đang tải lịch sử truy cập..."` ([L115](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx#L115)) | State inline alert `error` ([L14](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx#L14), render [L118](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx#L118)) | Phân tán 5 `useState` cho từ khóa tìm kiếm và bộ lọc ([L10-14](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessHistory/AccessHistoryPage.jsx#L10-L14)) | `không có` (Ủy thác hoàn toàn cho `ProtectedRoute`) |
| `AccessRequestPage` | State `loadingAreas` ([L25](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L25)), `loadingHistory` ([L48](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L48)), text `"Đang tải dữ liệu..."` ([L623](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L623)) | State inline banner `successMsg` ([L354](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L354)), `errorMsg` ([L361](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L361)) | Phân tán 22 `useState` rời rạc ([L21-50](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L21-L50)), submit qua `<form onSubmit={handleSubmit}>` ([L368](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestPage.jsx#L368)) | `không có` (Ủy thác hoàn toàn cho `ProtectedRoute`) |
| `AccessRequestReviewPage` | State `loading` ([L26](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx#L26)), `actionLoading` ([L38](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx#L38)), text `"Đang tải dữ liệu phê duyệt..."` ([L455](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx#L455)) | State inline banner `successMsg` ([L204](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx#L204)), `errorMsg` ([L212](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx#L212)) | Phân tán 15 `useState` rời rạc cho filter, pagination và modal phê duyệt/từ chối ([L23-45](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accessRequest/AccessRequestReviewPage.jsx#L23-L45)) | `không có` (Ủy thác hoàn toàn cho `ProtectedRoute`) |
| `ManageAccountPage` | State `loading` ([L68](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L68)), `isBatchesLoading` ([L99](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L99)), `isBatchDetailsLoading` ([L104](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L104)); Skeleton row `.account-skeleton-row` ([L1140](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L1140)) | Toast tùy biến nội bộ `showToast()` ([L273](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L273), render `.account-toast` tại [L517](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L517)); State inline alert `error` ([L69](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L69), render [L528](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L528)) | Tập trung Object `createForm` ([L78-83](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L78-L83)); Phân tán 38 `useState` rời rạc cho toàn bộ tab, modal, file upload ([L55-108](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L55-L108)); Submit qua `<form onSubmit={handleSubmitCreate}>` ([L926](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L926)) và `<form onSubmit={handleSubmitBulkImport}>` ([L1305](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L1305)) | Context `useAuth()` ([L52](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L52)); Dùng hằng số `ROLES` để lọc role hợp lệ khi tạo tài khoản hệ thống `SYSTEM_STAFF_ROLES` ([L46-48](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L46-L48)) và gán `role` mặc định ([L82](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L82), [L369](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L369)) |
| `AiSettingsPage` | State `loading` ([L10](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx#L10)), icon quay `.ai-settings-spinner` ([L104](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx#L104)) | State inline alert `error` ([L12](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx#L12), render [L112](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx#L112)), `successMsg` ([L13](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx#L13), render [L118](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx#L118)) | Phân tán 8 `useState` rời rạc cho các tham số cấu hình ([L15-22](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx#L15-L22)); Submit qua `<form onSubmit={handleSubmit}>` ([L124](file:///Users/anhhao/Documents/SEP/frontend/src/pages/ai/AiSettingsPage.jsx#L124)) | `không có` (Ủy thác hoàn toàn cho `ProtectedRoute`) |
| `AreaCameraManagementPage` | State `loadingAreas` ([L13](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx#L13)), `loadingCameras` ([L14](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx#L14)), text `"Đang tải dữ liệu..."` ([L141](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx#L141)) | State inline alert `error` ([L20](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx#L20), render [L123](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx#L123)), `successMsg` ([L21](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx#L21), render [L129](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx#L129)) | Phân tán 11 `useState` rời rạc quản lý selection transfer list ([L12-24](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaCameraManagementPage.jsx#L12-L24)) | `không có` (Ủy thác hoàn toàn cho `ProtectedRoute`) |
| `AreaListPage` | State `loading` ([L85](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L85)), `modalLoading` ([L107](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L107)); Spinner CSS `.zone-loading-spinner` ([L1367](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L1367)), text `"Đang tải..."` ([L1368](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L1368)) | State inline alert `modalError` ([L108](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L108), render [L534](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L534)), `modalSuccess` ([L109](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L109), render [L528](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L528)) | Tập trung Object `formData` ([L112](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L112)), `zoneFormData` ([L92](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L92)); Kết hợp 22 `useState` rời rạc điều khiển polygon canvas, floor tabs, drawer ([L83-120](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L83-L120)) | Context `useAuth()` ([L68](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L68)), kiểm tra `isAdmin = user?.role === 'ADMIN'` ([L69](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L69)) để điều khiển hiển thị quyền tạo/sửa khu vực và vẽ polygon ([L609](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L609), [L923](file:///Users/anhhao/Documents/SEP/frontend/src/pages/areas/AreaListPage.jsx#L923)) |
| `CameraDetailPage` | State `loading` ([L33](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx#L33)), `logsLoading` ([L42](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx#L42)), text `"Đang tải thông tin camera..."` ([L137](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx#L137)) | State inline alert `error` ([L35](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx#L35), render [L143](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx#L143)), `successMsg` ([L36](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx#L36), render [L152](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx#L152)) | Phân tán 12 `useState` rời rạc ([L32-47](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx#L32-L47)); Submit qua `<form>` cấu hình ([L325](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx#L325)) và `<form>` chỉnh sửa ([L410](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraDetailPage.jsx#L410)) | `không có` (Ủy thác hoàn toàn cho `ProtectedRoute`) |
| `CameraListPage` | State `loading` ([L32](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L32)), `actionLoadingId` ([L45](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L45)), icon quay `.camera-spinner` ([L153](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L153)) | Trình duyệt native `alert()` ([L90](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L90), [L111](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L111)); State inline alert `error` ([L33](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L33), render [L142](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L142)) | Phân tán 11 `useState` rời rạc ([L31-45](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L31-L45)); Submit filter qua `<form onSubmit={handleSearchSubmit}>` ([L122](file:///Users/anhhao/Documents/SEP/frontend/src/pages/cameras/CameraListPage.jsx#L122)) | `không có` (Ủy thác hoàn toàn cho `ProtectedRoute`) |
| `GuardDashboardPage` | State `camerasLoading` ([L26](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L26)), text `"Đang tải danh sách camera..."` ([L244](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L244)), text `"Đang tải..."` ([L272](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L272)) | `không có` (Lỗi WebSocket/STOMP chỉ ghi nhận qua `console.error` [L47](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L47), `console.warn` [L126](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L126)) | `không có` (Dùng 7 `useState` lưu mảng camera, active cell, drawer incident [L22-29](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L22-L29)) | `không có` (Ủy thác hoàn toàn cho `ProtectedRoute`) |
| `NotificationsPage` | State `loading` ([L16](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx#L16)), text `"Đang tải thông báo..."` ([L129](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx#L129)) | `không có` | `không có` (Dùng 2 `useState` lưu bộ lọc tab [L14-15](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx#L14-L15)) | `không có` (Ủy thác hoàn toàn cho `ProtectedRoute`) |

---

## 7. Điểm cần biết trước khi sửa UI

### A. Inline Style trong file `.jsx`
Toàn dự án có **196 vị trí** dùng `style={{ ... }}` trong 15 file `.jsx` và `.js`.
- **Phân loại bản chất:**
  - **71 vị trí chứa giá trị màu:** Dùng mã hex `#...`, hàm màu `rgb()`, `rgba()`, hoặc biến màu CSS `var(--theme`, `var(--brand`.
  - **125 vị trí thuần bố cục:** Chỉ thiết lập kích thước, khoảng đệm, căn lề, hiển thị (như `margin: '14px 20px 0 20px'`, `maxHeight: '65vh'`, `overflowY: 'auto'`, `textAlign: 'right'`, `display: 'flex'`, `width: '100%'`).
- **Phân biệt với tài liệu cũ:** Con số 196 này khác với "3 vị trí inline style" trong các tài liệu kiểm toán trước (`docs/theme-primary-audit.md`) — 3 vị trí đó chỉ tính riêng 3 vị trí JSX sử dụng token `--theme-primary` tại [`ManageAccountPage.jsx:1031, 1039, 1367`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/accounts/ManageAccountPage.jsx#L1031). Riêng `ManageAccountPage.jsx` hiện chiếm 95/196 vị trí `style={{` (trong đó có 29 vị trí chứa màu).

### B. Phạm vi kiểm tra của hook pre-commit
Kịch bản chặn hồi quy mã màu [`scripts/check-colors.sh`](file:///Users/anhhao/Documents/SEP/scripts/check-colors.sh) chỉ kiểm tra các file có phần mở rộng `*.css` trong `frontend/src` (loại trừ `LoginPage.css` và `theme.css`). Kịch bản **hoàn toàn không quét các file `.jsx` hay `.js`**. Do đó, toàn bộ 71 vị trí inline style chứa màu trong `.jsx` hiện nằm ngoài tầm kiểm soát của hook pre-commit và sẽ không bị chặn nếu phát sinh mã màu viết cứng tại JSX.

### C. Các màn hình không có thông báo lỗi cho người dùng
Bốn màn hình hiện không hiển thị bất kỳ thông báo lỗi trực quan nào trên giao diện khi gặp sự cố:
1. [`DashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx): **Thiếu tính năng xử lý lỗi** do hoàn toàn không destructure `isError` hay `error` từ `useQuery` tại dòng [L30](file:///Users/anhhao/Documents/SEP/frontend/src/pages/DashboardPage.jsx#L30).
2. [`GuardDashboardPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx): Nuốt lỗi kết nối STOMP/WebSocket và lỗi parse JSON vào `console.error` ([L47](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L47), [L117](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L117)) và `console.warn` ([L126](file:///Users/anhhao/Documents/SEP/frontend/src/pages/guard/GuardDashboardPage.jsx#L126)) mà không có chỉ báo lỗi trên màn hình giám sát.
3. [`UnauthorizedPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/UnauthorizedPage.jsx): Chỉ render khối giao diện tĩnh 403.
4. [`NotificationsPage.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/pages/notifications/NotificationsPage.jsx): Không có cơ chế bắt và hiển thị lỗi tải thông báo (chỉ render trạng thái trống).

### D. File CSS vượt ngưỡng 1000 dòng
Hai file CSS có quy mô rất lớn, nguy cơ xung đột và khó kiểm soát hiển thị cao:
1. [`frontend/src/styles/AreaListPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css): **2.302 dòng** (giao diện vẽ polygon canvas, sidebar, floor tabs, drawer chi tiết).
2. [`frontend/src/styles/ManageAccountPage.css`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/ManageAccountPage.css): **1.568 dòng** (bảng dữ liệu, dropzone tải ảnh, modal thêm tài khoản, modal import hàng loạt).

### E. Component nhận vượt quá 8 props
Các component có chữ ký hàm nhận nhiều hơn 8 props:
1. [`frontend/src/components/ui/Input.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Input.jsx): **14 props** (`label, value, onChange, placeholder, error, hint, required, disabled, type, icon, id, name, className, ...rest`)
2. [`frontend/src/components/ui/Select.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Select.jsx): **13 props** (`label, value, onChange, options, placeholder, error, hint, required, disabled, id, name, className, ...rest`)
3. [`frontend/src/components/ui/Modal.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Modal.jsx): **11 props** (`isOpen, onClose, title, subtitle, icon, iconVariant, size, footer, closeOnBackdrop, className, children`)
4. [`frontend/src/components/ui/Button.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Button.jsx): **10 props** (`variant, size, icon, loading, disabled, onClick, type, className, children, ...rest`)
5. [`frontend/src/components/ui/Card.jsx`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Card.jsx): **9 props** (`padding, accentColor, className, onClick, header, footer, children, style, ...rest`)

### F. Số lượng sử dụng `!important` trong CSS
Tổng cộng có **43 vị trí** sử dụng cờ `!important` trong CSS (chủ yếu nhằm ghi đè các trạng thái `active`, `hover`, `focus` hoặc modal):
- `theme.css`: 2 vị trí (L285, L286 trong `.input-focus`).
- `CameraListPage.css`: 2 vị trí (L233, L433).
- `AreaListPage.css`: 14 vị trí (L339, L453, L454, L817, L1497, L1498, L2014, L2015...).
- `ManageAccountPage.css`: 17 vị trí (L153, L154, L656, L658, L1158, L1162...).
- `AccessRequestReviewPage.css`: 8 vị trí (L532, L533, L534, L541, L542, L543...).

### G. Thang bậc `z-index` trong CSS
Hệ thống hiện sử dụng **7 bậc `z-index` cứng** (sắp xếp tăng dần):
1. `z-index: 0` (3 nơi dùng): Nền canvas/orb mờ (ví dụ: [`CameraListPage.css:20`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css#L20)).
2. `z-index: 1` (13 nơi dùng): Lớp phủ thông thường, icon đặt bên trong ô input (ví dụ: [`CameraListPage.css:50`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraListPage.css#L50), [`Input.css:47`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Input.css#L47)).
3. `z-index: 5` (1 nơi dùng): Lớp điều khiển nổi trên luồng video (ví dụ: [`WebRtcPlayer.css:136`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/WebRtcPlayer.css#L136)).
4. `z-index: 10` (2 nơi dùng): Thanh công cụ cố định trên canvas bản đồ (ví dụ: [`AreaListPage.css:62`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L62), [`AreaListPage.css:379`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L379)).
5. `z-index: 100` (3 nơi dùng): Dropdown filter thả xuống, thanh thông tin nổi (ví dụ: [`CameraDetailPage.css:540`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/CameraDetailPage.css#L540)).
6. `z-index: 1000` (6 nơi dùng): Lớp phủ nền mờ modal (backdrop) và drawer kéo ra (ví dụ: [`AreaListPage.css:866`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L866), [`Modal.css:16`](file:///Users/anhhao/Documents/SEP/frontend/src/components/ui/Modal.css#L16)).
7. `z-index: 2000` (2 nơi dùng): Modal xác nhận cấp cao / popup thông báo đè lên modal (ví dụ: [`AreaListPage.css:1320`](file:///Users/anhhao/Documents/SEP/frontend/src/styles/AreaListPage.css#L1320)).

---

## 8. Điểm chưa xác minh được

| Điểm | Lý do | Cách xác minh |
|---|---|---|
| Nguy cơ phân giải import lệch hoa thường sau khi đổi tên file trên macOS APFS | Hệ thống tệp APFS mặc định không phân biệt hoa thường (case-insensitive). Việc `npm run build` thành công trên máy local không đảm bảo các câu lệnh import sẽ resolve chính xác trên môi trường phân biệt hoa thường (case-sensitive như Linux ext4) | Chạy `npm run build` trong container Linux (Docker) hoặc pipeline CI chạy trên môi trường Ubuntu/Linux |
| Nguồn gốc và giá trị thực tế của trường `role_type` ([`Sidebar.jsx:42`](file:///Users/anhhao/Documents/SEP/frontend/src/components/layout/Sidebar.jsx#L42)) | Mã nguồn frontend không có bất kỳ dòng lệnh nào tự gán trường `role_type`. Dữ liệu `user` đến trực tiếp từ phản hồi backend (`GET /api/auth/me`, `POST /api/auth/login`) hoặc cache `localStorage` | Kiểm tra phản hồi thực tế của `GET /api/auth/me` trên môi trường đang chạy hoặc kiểm tra đối tượng DTO phía backend Java |

---

## 9. Trạng thái build

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

*Dữ liệu lập chỉ mục tính đến commit:* `c73fba2`  
*Ngày cập nhật:* 17/09/2026
