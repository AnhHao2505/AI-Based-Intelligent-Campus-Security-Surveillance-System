# Kế hoạch Triển khai: Tab Nhật ký Ra vào Camera (Camera Access Logs Timeline)

- **Ngày lập**: 2026-10-14
- **Dựa trên thiết kế**: [`docs/superpowers/specs/2026-10-14-camera-access-logs-timeline-design.md`](file:///D:/DoAnSE/AI-Based-Intelligent-Campus-Security-Surveillance-System/docs/superpowers/specs/2026-10-14-camera-access-logs-timeline-design.md)
- **Quy trình áp dụng**: TDD (Test-Driven Development), Verification Before Completion.
- **Trạng thái**: ✅ Hoàn thành 100% (All Tasks Verified)

---

## Danh sách Công việc (Task Breakdown)

### Phase 1: Database & Entities (CSDL & Cấu trúc thực thể)
- [x] **Task 1.1**: Tạo Flyway migration `V74__create_camera_access_logs.sql`.
- [x] **Task 1.2**: Tạo Enum `AccessResult` (`AUTHORIZED_ACCESS`, `UNAUTHORIZED_ACCESS`, `ACCESS_OUT_OF_TIME`, `UNKNOWN_PERSON`, `INTRUSION`).
- [x] **Task 1.3**: Tạo Entity `CameraAccessLog` mapping bảng `camera_access_logs`.
- [x] **Task 1.4**: Tạo `CameraAccessLogRepository` kế thừa `JpaRepository` với các hàm query phân trang và đếm số lượng theo ngày.
- [x] **Task 1.5**: Tạo các DTO `CameraAccessLogResponse` và `CameraAccessLogStatsResponse`.

### Phase 2: Backend Service & TDD (Logic nghiệp vụ & Ghi log tự động)
- [x] **Task 2.1 (TDD Red)**: Viết unit tests trước trong `CameraAccessLogServiceTest`:
  - Test ghi nhận log khi xét quyền thành công/thất bại.
  - Test cơ chế debounce 30s chống spam cùng 1 người tại cùng 1 camera.
  - Test logic phân loại 5 trạng thái (`AUTHORIZED_ACCESS`, `UNAUTHORIZED_ACCESS`, `ACCESS_OUT_OF_TIME`, `UNKNOWN_PERSON`, `INTRUSION`).
  - Test điều phối WebSocket `/topic/cameras/{cameraCode}/access-logs`.
- [x] **Task 2.2 (TDD Green)**: Viết code cho `CameraAccessLogService`:
  - Triển khai `recordAccessLog(...)`, `getLogs(...)`, `getStatsToday(...)`.
- [x] **Task 2.3**: Tích hợp gọi `CameraAccessLogService.recordAccessLog(...)` vào cuối hàm `checkCameraEntry` trong `AccessDecisionService`.
- [x] **Task 2.4**: Chạy toàn bộ test suite kiểm tra Red $\rightarrow$ Green (101/101 tests passed).

### Phase 3: REST API & Controller (Endpoint truy vấn log & Thống kê)
- [x] **Task 3.1 (TDD Red)**: Viết tests trong `CameraAccessLogControllerTest` (`GET /api/cameras/{cameraCode}/access-logs`, `GET /api/cameras/{cameraCode}/access-logs/stats`).
- [x] **Task 3.2 (TDD Green)**: Cài đặt `CameraAccessLogController`.
- [x] **Task 3.3**: Cấu hình phân quyền trong `SecurityConfig` (`@PreAuthorize("hasAnyRole('ADMIN', 'FACILITY_MANAGER', 'GUARD')")`).
- [x] **Task 3.4**: Chạy toàn bộ backend test `./mvnw.cmd test` xác nhận 100% pass.

### Phase 4: Frontend Service & Tab Component (Giao diện Timeline)
- [x] **Task 4.1**: Tạo `frontend/src/services/cameraAccessLogService.js` (gọi API lấy log & stats).
- [x] **Task 4.2**: Tạo Component `frontend/src/components/camera/CameraAccessLogsTab.jsx`:
  - Kết nối STOMP WebSocket lắng nghe `/topic/cameras/{cameraCode}/access-logs`.
  - Thanh Live Status nhấp nháy `● AI Live Monitoring (Trực tiếp)`.
  - Bộ 5 nút lọc kèm số đếm: `Tất cả` | 🟢 `Hợp lệ` | 🔴 `Sai quyền` | 🟠 `Sai giờ` | 🟣 `Người lạ` | 🟡 `Ngoài giờ`.
  - Ô tìm kiếm theo Tên / Mã.
  - Danh sách Timeline với trục dọc, chấm trạng thái, thẻ card trượt xuống (slide-down animation).
- [x] **Task 4.3**: Thêm CSS cho Timeline vào `CameraDetailPage.css` đảm bảo dark/light theme chuẩn tokens.
- [x] **Task 4.4**: Nhúng Tab thứ 4 vào `CameraDetailPage.jsx` và cập nhật danh sách tabs navigation.

### Phase 5: Verification & End-to-End Testing (Xác minh & Hoàn thành)
- [x] **Task 5.1**: Chạy `npm.cmd run build` trên Frontend xác nhận không lỗi build (`✓ built in 17.08s`).
- [x] **Task 5.2**: Chạy `./mvnw.cmd test` trên Backend xác nhận 100% pass (`BUILD SUCCESS`, 101 unit tests + 18 integration tests pass).
- [x] **Task 5.3**: Chạy test integration Python `test_access_decision_integration.py` xác nhận luồng tích hợp AI $\rightarrow$ Backend không bị ảnh hưởng (`9 tests OK in 6.206s`).
