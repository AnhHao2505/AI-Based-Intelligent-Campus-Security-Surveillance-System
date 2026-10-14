# Thiết kế Tính năng: Tab Nhật ký Ra vào (Camera Access Logs Timeline)

- **Ngày tạo**: 2026-10-14
- **Trạng thái**: Đã thống nhất thiết kế (Approved Design)
- **Tác giả**: Antigravity & User

---

## 1. Bối cảnh & Mục tiêu (Context & Motivation)

### 1.1 Vấn đề hiện tại
- Hệ thống AI Camera hiện tại chỉ phát cảnh báo (Alert) và ghi nhận sự cố khi xảy ra vi phạm an ninh (`UNAUTHORIZED_ACCESS`, `UNKNOWN_PERSON`, `AFTER_HOURS_PRESENCE`).
- Khi một người **được cấp quyền hợp lệ** (như sinh viên đúng cấp, người có đơn duyệt bảo lãnh, nhân sự phụ trách hoặc khách đúng giờ) bước qua camera, AI Camera xử lý thành công nhưng giao diện web hoàn toàn không có phản hồi ("Silent Success").
- Điều này gây cảm giác nghi ngờ: người dùng và người đánh giá không thể biết camera AI có thực sự đang chạy ngầm, nhận diện khuôn mặt và kiểm tra quyền hay không.

### 1.2 Mục tiêu tính năng
- Xây dựng một Tab thứ 4 mang tên **"Nhật ký ra vào" (`CameraAccessLogsTab`)** trong trang Chi tiết Camera (`CameraDetailPage`).
- Lưu vết toàn bộ lượt quét mặt qua camera (cả **HỢP LỆ** lẫn **TỪ CHỐI / CẢNH BÁO**).
- Cập nhật thời gian thực qua **WebSocket** (`/topic/cameras/{cameraCode}/access-logs`), giúp dòng sự kiện tự động trượt lên màn hình ngay khi người bước qua camera.
- Phân loại trạng thái và màu sắc trực quan, bám sát **Bảng Ma trận Phân loại Kiểm soát Ra vào** của đồ án:
  - 🟢 `AUTHORIZED_ACCESS` (Hợp lệ - Xanh lá `#10b981`)
  - 🔴 `UNAUTHORIZED_ACCESS` (Sai quyền / Vượt cấp - Đỏ `#ef4444`)
  - 🟠 `ACCESS_OUT_OF_TIME` (Sai khung giờ: sớm, trễ, quá hạn - Vàng cam `#f59e0b`)
  - 🟣 `UNKNOWN_PERSON` (Người lạ / Chưa có dữ liệu khuôn mặt - Tím `#8b5cf6`)
  - 🟡 `INTRUSION` (Xâm nhập ban đêm 22:00 - 06:00 - Vàng đậm `#eab308`)

---

## 2. Thiết kế Kiến trúc (Architecture)

```
[ AI Camera Stream Worker ]
          │ (1) match_face & check_camera_access
          ▼
[ POST /api/access-control/check-entry ]
          │ (2) AccessDecisionService.checkCameraEntry
          ▼
┌─────────────────────────────────────────────────────────────┐
│                 CameraAccessLogService                      │
│                                                             │
│  - Phân loại kết quả (5 nhóm chuẩn Ma trận)                 │
│  - Debounce Cache (chống spam cùng 1 người trong 30s)        │
│  - Lưu vào PostgreSQL (camera_access_logs)                  │
│  - Bắn WebSocket: /topic/cameras/{cameraCode}/access-logs   │
└─────────────────────────────────────────────────────────────┘
          │ (3) WebSocket STOMP
          ▼
[ Frontend: CameraAccessLogsTab.jsx (Timeline View) ]
```

---

## 3. Mô hình CSDL (Database Schema - Flyway V69)

Bảng mới: `camera_access_logs`

```sql
CREATE TABLE camera_access_logs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    camera_code VARCHAR(50) NOT NULL,
    area_id UUID NOT NULL REFERENCES areas(id),
    user_id UUID REFERENCES users(id),
    user_code VARCHAR(50),
    full_name VARCHAR(150),
    user_access_level INT,
    access_result VARCHAR(30) NOT NULL, -- AUTHORIZED_ACCESS, UNAUTHORIZED_ACCESS, ACCESS_OUT_OF_TIME, UNKNOWN_PERSON, INTRUSION
    access_source VARCHAR(30),         -- ACCESS_LEVEL, ACCESS_REQUEST, GUEST_VISIT, ASSIGNED_PERSONNEL, NONE
    source_reference_id UUID,          -- ID của AccessRequest hoặc Assignment nếu có
    reason_details TEXT,               -- Lý do thẩm định
    logged_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_access_logs_camera_time ON camera_access_logs (camera_code, logged_at DESC);
CREATE INDEX idx_access_logs_result ON camera_access_logs (access_result);
CREATE INDEX idx_access_logs_user ON camera_access_logs (user_code);
```

---

## 4. Thiết kế Phía Backend (Spring Boot)

### 4.1 Quy tắc Phân loại Kết quả (Result Classification Mapping)
Dựa theo kết quả trả về từ `AccessDecisionService.checkCameraEntry` và thời gian hiện tại:
1. Nếu `allowed == true`:
   - Phân loại: `AUTHORIZED_ACCESS`
2. Nếu `allowed == false`:
   - Nếu trong khung giờ giới nghiêm ban đêm (22:00 - 06:00):
     $\rightarrow$ `INTRUSION`
   - Nếu `userCode` không tìm thấy trong hệ thống:
     $\rightarrow$ `UNKNOWN_PERSON`
   - Nếu lý do từ chối liên quan đến thời gian ("ngoài khung giờ", "chưa tới giờ", "hết hạn"):
     $\rightarrow$ `ACCESS_OUT_OF_TIME`
   - Còn lại (vượt cấp, chưa duyệt đơn, tài khoản bị khoá):
     $\rightarrow$ `UNAUTHORIZED_ACCESS`

### 4.2 Cơ chế Chống spam (Debounce)
- Sử dụng bộ đệm in-memory (ConcurrentHashMap với TTL 30 giây).
- Khóa debounce: `String cacheKey = cameraCode + ":" + userCode`.
- Nếu sự kiện tiếp theo cùng người xuất hiện tại cùng camera trong vòng 30s, bỏ qua không ghi trùng lặp vào CSDL, chỉ ghi nhận lượt mới sau khi hết hạn 30s.

### 4.3 REST API Endpoints
- `GET /api/cameras/{cameraCode}/access-logs`:
  - **Query Params**:
    - `page` (default 0), `size` (default 20)
    - `result` (optional: `AUTHORIZED_ACCESS`, `UNAUTHORIZED_ACCESS`, v.v.)
    - `keyword` (optional: tìm theo tên hoặc mã người dùng)
    - `from`, `to` (optional: lọc theo khoảng thời gian)
  - **Response**: `ApiResponse<Page<CameraAccessLogResponse>>`

- `GET /api/cameras/{cameraCode}/access-logs/stats`:
  - Trả về số lượng tổng kết trong ngày: `totalToday`, `authorizedToday`, `unauthorizedToday`, `outOfTimeToday`, `unknownToday`, `intrusionToday`.

---

## 5. Thiết kế Giao diện Phía Frontend (`CameraAccessLogsTab.jsx`)

### 5.1 Vị trí tích hợp
- Nằm trong `CameraDetailPage.jsx` tại vị trí Tab thứ 4:
  `[ Thông tin chung ] [ Cấu hình Stream ] [ Vùng Giám Sát ] [ Nhật ký ra vào ]`

### 5.2 Các thành phần giao diện
1. **Live Header & Stats Bar**:
   - Chấm tròn xanh nhấp nháy: `● AI Live Monitoring (Kết nối trực tiếp)`
   - Nút làm mới thủ công và hiển thị tổng số lượt trong ngày.
2. **Filter Pills Bar (Bộ lọc 5 màu)**:
   - 🔘 `Tất cả` (Màu Slate `#64748b`)
   - 🟢 `Hợp lệ` (Màu Emerald `#10b981`)
   - 🔴 `Sai quyền` (Màu Rose `#ef4444`)
   - 🟠 `Sai giờ` (Màu Amber `#f59e0b`)
   - 🟣 `Người lạ` (Màu Violet `#8b5cf6`)
   - 🟡 `Ngoài giờ` (Màu Gold `#eab308`)
   - Ô Input tìm kiếm nhanh (Search by Name / User Code).
3. **Activity Timeline Feed**:
   - Trục thời gian dọc (Vertical line) với chấm trạng thái màu sắc tương ứng.
   - Mỗi lượt quét là 1 thẻ card:
     - Header: Giờ quét (`hh:mm:ss`), Badge phân loại màu rực rỡ, Badge Nguồn thẩm quyền (`Cấp độ`, `Đơn nhóm`, `Khách`).
     - Body: Tên người dùng, Mã số, Cấp độ truy cập.
     - Footer: Mô tả căn cứ lý do (*"Đơn nhóm #RQ-02 bảo lãnh bởi..."*).
   - Khi có sự kiện mới tới từ WebSocket: Thẻ mới trượt nhẹ từ trên xuống (smooth slide-down animation) và sáng viền trong 2 giây.

---

## 6. Chiến lược Kiểm thử (Testing Strategy)

1. **Unit Tests (Backend)**:
   - `CameraAccessLogServiceTest`:
     - Test ghi log khi `checkCameraEntry` thành công.
     - Test debounce chống spam 30s.
     - Test phân loại chính xác 5 mã kết quả theo bảng ma trận.
   - `CameraAccessLogControllerTest`:
     - Test phân trang và lọc theo `result`, `keyword`.
2. **Integration Verification (End-to-End)**:
   - Khởi động Camera stream $\rightarrow$ người quét mặt.
   - Xác nhận log được ghi vào DB.
   - Xác nhận WebSocket bắn ra payload hợp lệ và frontend Timeline cập nhật tức thì.
