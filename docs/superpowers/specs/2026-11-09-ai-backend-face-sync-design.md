# Thiết kế Kỹ thuật: Bỏ Kết Nối DB Trực Tiếp Từ AI-Service, Đồng Bộ Qua Backend API (F5)

- **Ngày tạo**: 2026-11-09
- **Trạng thái**: Chờ duyệt (Pending Review / Builder Check)
- **Tác giả**: Antigravity & User
- **Phạm vi**: `backend` (Spring Boot), `ai-service` (FastAPI / Python)

---

## 1. Bối cảnh & Động lực (Context & Motivation)

### 1.1 Vấn đề hiện tại
1. **Vi phạm kiến trúc Microservices & Rò rỉ kết nối DB:**
   - `ai-service` hiện đang cấu hình trực tiếp thông tin kết nối PostgreSQL (`settings.DB_HOST`, `settings.POSTGRES_USER`...) và sử dụng thư viện `psycopg2` để truy vấn trực tiếp 7 bảng cơ sở dữ liệu của Backend (`face_data`, `users`, `guest_face_embeddings`, `guests`, `guest_visits`, `cameras`, `areas`).
   - Trong `shared_face_cache.py:72`, mỗi chu kỳ làm mới cache lại mở một kết nối raw TCP mới (`psycopg2.connect(**self.db_params)`) rồi đóng lại mà **không sử dụng Connection Pool**. Điều này dễ dẫn đến cạn kiệt pool kết nối của PostgreSQL (`max_connections`) khi có nhiều tiến trình hoặc tải cao.
2. **Xóa nhầm Cache Quyền Ra Vào (`access_cache.clear()`):**
   - Trong `face_matcher.py:107-108` và `main.py:561-570`, mỗi khi làm mới cache khuôn mặt, hệ thống lại gọi `self.access_cache.clear()`.
   - `access_cache` là bộ đệm lưu kết quả thẩm định ra vào từ backend (`checkEntry` API) trong 30 giây. Việc xóa trắng bộ đệm này khiến tất cả các camera worker đồng loạt gửi bão request HTTP `check-entry` về Spring Boot (hiện tượng Thundering Herd / Cache Stampede), gây giật lag luồng camera.
3. **Thiếu cơ chế đồng bộ tức thì cho Khách mời (Guests):**
   - Hiện tại Backend chỉ gọi webhook `/api/v1/faces/refresh-cache` khi thêm/sửa User (`UserService.java`), hoàn toàn chưa gọi khi phê duyệt hoặc nạp ảnh Khách mời (`GuestVisitService`, `GuestService`). Do đó, `ai-service` phải dựa vào vòng lặp quét DB định kỳ 30s để nhận diện khách mới.

---

## 2. Mục tiêu (Goals, Non-Goals, Anti-Goals)

### 2.1 Goals (Mục tiêu)
- **Loại bỏ 100% kết nối DB trực tiếp từ `ai-service`:** Toàn bộ dữ liệu vector khuôn mặt và cấu hình camera được cung cấp qua API nội bộ từ Spring Boot. Cấu hình DB và thư viện `psycopg2` trong `ai-service` có thể được loại bỏ hoặc đánh dấu không bắt buộc.
- **Tận dụng Connection Pool của Spring Boot (HikariCP):** Toàn bộ truy vấn SQL đối với `face_data`, `users`, `guest_face_embeddings`, `cameras`, `areas` được thực hiện thông qua Hibernate/JPA và HikariCP của Spring Boot.
- **Bảo mật qua API nội bộ:** Endpoint mới trên Spring Boot được đặt dưới đường dẫn `/api/internal/ai/sync-data`, bảo vệ bằng bộ lọc có sẵn `InternalServiceKeyFilter` kiểm tra header `X-Service-Key`.
- **Đồng bộ sự kiện toàn diện (Event-driven):** Bổ sung webhook gọi sang AI Service khi phê duyệt khách mời hoặc cập nhật trạng thái chuyến thăm của khách.
- **Khắc phục lỗi xóa cache quyền:** Giữ nguyên `access_cache` khi refresh danh sách khuôn mặt, tránh hiện tượng thundering herd lên Backend.
- **Giãn chu kỳ polling ngầm:** Tăng chu kỳ refresh dự phòng của background thread từ 30 giây lên 15 phút (900s), chỉ đóng vai trò nhịp tim (heartbeat) tự phục hồi.

### 2.2 Non-Goals (Ngoài phạm vi)
- Không thay đổi thuật toán trích xuất đặc trưng ArcFace ResNet-50 hay ma trận nhân vector BLAS GEMV trong AI Service.
- Không thay đổi nghiệp vụ phân loại vi phạm và quy tắc kiểm soát ra vào trong `AccessDecisionService`.
- Không áp dụng Change Data Capture (Debezium/Kafka) phức tạp trong giai đoạn này.

### 2.3 Anti-Goals (Điều cấm)
- Không làm tăng độ trễ (latency) của luồng nhận diện video `match_face` trên camera.
- Không làm sập hoặc treo AI Service nếu Backend tạm thời khởi động chậm hơn (phải có cơ chế retry / graceful degraded state, giữ cache cũ trong RAM).

---

## 3. Các hướng tiếp cận đã xem xét (Approaches Considered)

1. **Hướng 1: Hybrid REST (Snapshot toàn phần + Webhook tức thì + Nhịp tim dài) ⭐ [ĐƯỢC CHỌN]**
   - *Cách làm:* Backend cung cấp endpoint `GET /api/internal/ai/sync-data`. AI Service nạp toàn bộ qua HTTP khi khởi động, nhận webhook tức thì khi có biến động, và chạy heartbeat ngầm mỗi 15 phút để tự phục hồi.
   - *Đánh đổi:* Mỗi lần sync tải toàn bộ danh sách, nhưng với quy mô trường học (< 10.000 người), dữ liệu nén Gzip chỉ ~500KB - 1MB, xử lý trong < 50ms.
   - *Lý do chọn:* Tận dụng tối đa hạ tầng sẵn có (`InternalAiController`, `X-Service-Key`, Webhook), an toàn, tin cậy tuyệt đối và không lo lệch trạng thái.

2. **Hướng 2: Incremental Delta Sync (Đồng bộ vi sai theo Version/Timestamp)**
   - *Cách làm:* Backend tạo bảng Changelog/Audit, AI Service chỉ lấy các bản ghi thay đổi từ thời điểm `since`.
   - *Lý do loại:* Over-engineering đối với quy mô đồ án; phức tạp trong việc quản lý khách hết hạn tự nhiên (`expires_at < NOW()`) và dễ dẫn đến lệch dữ liệu nếu có gói tin bị lỗi.

3. **Hướng 3: Pure Event-Driven qua MQTT Broker**
   - *Cách làm:* Đẩy vector thay đổi qua topic MQTT của Mosquitto.
   - *Lý do loại:* AI Service khởi động lần đầu vẫn cần REST để lấy snapshot ban đầu; việc đẩy vector 512D qua MQTT dễ gây nghẽn message broker khi import danh sách người dùng số lượng lớn.

---

## 4. Chi tiết Thiết kế Kiến trúc (Detailed Technical Specification)

### 4.1 Phía Backend (Spring Boot)

#### 4.1.1 DTO Truyền tải Dữ liệu Đồng bộ (`AiSyncDataDto`)
Tạo DTO trong package `com.fa26se040.icss.dto.ai`:
```java
public record AiFaceItemDto(
    String code,
    String fullName,
    List<Float> embedding, // Vector 512 chiều chuẩn float
    boolean isActive,
    String role,
    Long expiresAt // Thời điểm hết hạn (epoch ms) đối với Khách mời; null đối với User dài hạn
) {}

public record AiSyncDataDto(
    List<AiFaceItemDto> faces,
    Map<String, String> cameraAreaMap, // UPPER(cameraCode) -> areaName
    Set<String> publicCameraCodes,      // UPPER(cameraCode)
    long timestamp
) {}
```

#### 4.1.2 Service tổng hợp dữ liệu (`AiSyncService`)
Tạo service `AiSyncService` trong `com.fa26se040.icss.service`:
- Truy vấn tất cả `FaceData` hợp lệ của Users (`deletedAt IS NULL`).
- Truy vấn tất cả `GuestFaceEmbedding` của Khách mời hợp lệ (`gv.status = 'APPROVED'`, `g.biometricStatus = 'PHOTO_READY'`, `expiresAt > NOW()`).
- Chuyển đổi định dạng vector pgvector sang `List<Float>`.
- Truy vấn bảng `Camera` và `Area` để xây dựng `cameraAreaMap` và `publicCameraCodes` (`area.areaLevel = 'PUBLIC' OR area.areaAccessLevel <= 1`).

#### 4.1.3 Endpoint nội bộ trong `InternalAiController.java`
Bổ sung endpoint được bảo vệ bởi `InternalServiceKeyFilter`:
- **Đường dẫn**: `GET /api/internal/ai/sync-data`
- **Header bắt buộc**: `X-Service-Key: <icss.internal.service-key>`
- **Phản hồi**: `ApiResponse<AiSyncDataDto>`

#### 4.1.4 Cơ chế Phát Sự Kiện Đồng Bộ Thống Nhất (`AiSyncNotificationService`)
- Xây dựng service dùng chung `AiSyncNotificationService` gom logic thông báo Dual-Transport (MQTT topic `campus/edge/events/faces` và HTTP webhook `/api/v1/faces/refresh-cache` sau khi transaction commit).
- Sử dụng service này thống nhất trong:
  1. `UserService`: Thay đổi tài khoản, đổi trạng thái active, xoá mềm.
  2. `FaceDataService`: Đăng ký hoặc cập nhật ảnh khuôn mặt người dùng.
  3. `GuestVisitService` & `GuestPhotoService`: Phê duyệt khách mời (`APPROVED`), chụp ảnh hoàn tất (`PHOTO_READY`), huỷ chuyến thăm.
- **Xử lý Khách mời hết hạn tự nhiên:** Nhờ trường `expiresAt` trong `AiFaceItemDto`, AI Service tự kiểm tra và loại bỏ khách hết hạn ngay trong RAM. Do đó, job định kỳ `completeApprovedVisits` **không cần** phát webhook mỗi phút, triệt tiêu nguy cơ bão request tải lại snapshot.

---

### 4.2 Phía AI Service (FastAPI / Python)

#### 4.2.1 Cập nhật `SharedFaceCache` (`ai-service/src/core/shared_face_cache.py`)
1. **Loại bỏ kết nối DB trực tiếp:**
   - Bỏ hàm `_get_connection()` sử dụng `psycopg2`.
   - Đánh dấu các thông số `db_host, db_port, db_user, db_pass, db_name` là không bắt buộc (deprecated).
2. **Nạp dữ liệu qua HTTP API:**
   - Trong `refresh_cache()`:
     - Sử dụng `get_http_session()` từ `src.utils.http_client`.
     - Gửi `GET {settings.BACKEND_API_URL}/api/internal/ai/sync-data` với header `{INTERNAL_SERVICE_KEY_HEADER: settings.INTERNAL_SERVICE_KEY}`.
     - Timeout: 10.0 giây.
     - Nhận payload JSON:
       - Với mỗi item trong `faces`: lưu `expires_at` (nếu có), chuyển `embedding` (danh sách float) thành `np.array(emb, dtype=np.float32)`. Chuẩn hóa L2 norm `norm = np.linalg.norm(vec); if norm > 1e-6: vec = vec / norm`.
       - Ghép toàn bộ thành ma trận numpy `cached_matrix = np.vstack(...)`.
       - Cập nhật `camera_area_map` và `public_camera_codes`.
       - Thực hiện hoán đổi snapshot nguyên tử (`Atomic Snapshot Swap`) như hiện tại.
3. **Cơ chế Fallback & Resilience:**
   - Nếu Backend không phản hồi hoặc HTTP lỗi: giữ nguyên cache hiện tại trong RAM, áp dụng exponential backoff và ghi log cảnh báo (không crash worker).
4. **Giãn chu kỳ Polling:**
   - Mặc định `cache_ttl = 900.0` (15 phút). Background loop chỉ đóng vai trò nhịp tim dự phòng khi có webhook bị thất lạc.

#### 4.2.2 Cập nhật `FaceMatcher` (`ai-service/src/core/face_matcher.py`)
- **Kiểm tra hạn sử dụng trong `match_face()`:**
  - Nếu đối tượng khớp có `expires_at` và `time.time() * 1000 > expires_at`, bỏ qua không công nhận đối tượng hợp lệ.
- **Sửa hàm `refresh_cache()`:**
  - **Xóa bỏ hoàn toàn lệnh:**
    ```python
    with self._access_cache_lock:
        self.access_cache.clear() # ĐÃ XÓA
    ```
  - `refresh_cache()` chỉ kích hoạt `self.cache.refresh_cache()` để cập nhật vector khuôn mặt.
  - Quyết định phân quyền (`access_cache`) tiếp tục được lưu giữ theo đúng thời hạn TTL (30s) của nó, không bị xoá đột ngột.

#### 4.2.3 Cập nhật `main.py` & `edge_agent.py`
- Trong `main.py` (`/api/v1/faces/refresh-cache`):
  - Chỉ gọi `get_shared_face_cache().refresh_cache()`.
  - **Xóa bỏ các đoạn vòng lặp duyệt qua `workers` để gọi `access_cache.clear()`**.
- Trong `edge_agent.py` (`_handle_face_cache_refresh`):
  - Thay vì vòng lặp gọi `refresh_cache()` trên từng worker pipeline, chỉ gọi `get_shared_face_cache().refresh_cache()` **đúng 1 lần** cho toàn tiến trình.

---

## 5. Kế hoạch Kiểm thử & Xác minh (Verification Plan)

### 5.1 Kiểm thử Backend (Spring Boot)
1. **Unit / Integration Test `InternalAiControllerTest`:**
   - Gửi GET `/api/internal/ai/sync-data` không có `X-Service-Key` $\rightarrow$ Trả về `401 Unauthorized` hoặc `403 Forbidden`.
   - Gửi GET `/api/internal/ai/sync-data` với `X-Service-Key` đúng $\rightarrow$ Trả về `200 OK`, JSON chứa đầy đủ `faces`, `cameraAreaMap`, `publicCameraCodes`.
2. **Webhook Trigger Test:**
   - Kiểm tra khi duyệt khách mời (`approveVisit`), webhook refresh cache sang AI service được kích hoạt sau khi commit transaction thành công.

### 5.2 Kiểm thử AI Service (Python)
1. **Unit Test `test_shared_face_cache_http_sync`:**
   - Mock response của HTTP session trả về danh sách vector và cấu hình camera.
   - Gọi `refresh_cache()`, kiểm tra `cached_faces`, `cached_matrix`, `camera_area_map`, `public_camera_codes` nạp chính xác vào RAM snapshot.
2. **Khắc phục Thundering Herd Test:**
   - Giả lập `access_cache` đang có dữ liệu quyền truy cập còn hạn.
   - Gọi `refresh_cache()` hoặc webhook `/api/v1/faces/refresh-cache`.
   - Khẳng định `access_cache` **không bị xóa** và vẫn trả về quyết định đúng thời hạn TTL.
3. **Degraded Mode / Backend Down Test:**
   - Giả lập HTTP Backend trả về lỗi 500 hoặc timeout 10s.
   - Khẳng định `refresh_cache()` trả về False nhưng không làm mất cache khuôn mặt đã có trong RAM và không gây crash pipeline.
