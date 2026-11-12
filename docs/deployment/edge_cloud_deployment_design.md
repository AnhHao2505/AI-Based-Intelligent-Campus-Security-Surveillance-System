# THIẾT KẾ TRIỂN KHAI HỆ THỐNG AN NINH THÔNG MINH (CLOUD–EDGE & RASPBERRY PI)
> **Dự án:** AI-Based Intelligent Campus Security Surveillance System for Restricted Area Access Control in Tân Uyên FPTU Campus  
> **Tài liệu:** Kiến trúc Triển khai Đám mây – Biên (Cloud–Edge Deployment Design)  
> **Tham chiếu cốt lõi:** Commit [`2300704a55598c1bfad64e36d057fc0b6fa79a60`](file:///c:/Projects/AI-Based-Intelligent-Campus-Security-Surveillance-System) *(Xác thực MQTT Broker, Phân quyền ACL và Lưu trữ Bền vững AlertOutbox SQLite)*

---

## 1. Tổng quan Kiến trúc (Architecture Overview)

Hệ thống được thiết kế theo mô hình **Cloud–Edge Hybrid (Biên – Đám mây kết hợp)** với nguyên tắc **100% Outbound-Only từ Edge (Không mở Port-Forwarding tại mạng trường học)**. 

### 1.1. Phân chia Trách nhiệm (Separation of Concerns)

| Thành phần | Vị trí Triển khai | Trách nhiệm chính | Đặc điểm mạng & Tài nguyên |
|---|---|---|---|
| **Camera IP Giám sát** | Mạng LAN Nội bộ Campus | Thu nhận hình ảnh, xuất luồng RTSP (H.264). | Dải IP cục bộ (`192.168.1.x`), không ra Internet. |
| **Edge AI Device (Raspberry Pi / Edge PC)** | Mạng LAN Nội bộ Campus | • Kéo luồng RTSP nội bộ qua LAN (0đ băng thông).<br>• Chạy YOLO26n + ROI Gate + Tracking tại chỗ.<br>• Ghi đệm video MP4 chuẩn 480p@15fps khi có sự cố.<br>• Quản lý hàng đợi SQLite **AlertOutbox** chống mất cảnh báo khi đứt mạng. | Đặt sau NAT/Firewall trường học; chỉ mở kết nối **Outbound** (đi ra ngoài) tới MQTT Broker và MinIO Cloud. |
| **MQTT Message Broker (Mosquitto 2.0)** | Cloud Server (VPS / Docker) | • Tiếp nhận sự kiện cảnh báo an ninh (`QoS 1`).<br>• Xác thực tài khoản (`icss-ai`, `icss-backend`).<br>• Kiểm soát phân quyền qua ACL (Access Control List).<br>• Lưu trữ hàng đợi tin nhắn bền vững (`persistence true`, `cleanSession=false`). | • Cổng `1883`: TCP nội bộ (chỉ bind `127.0.0.1` trên Host).<br>• Cổng `8883`: MQTTS (MQTT qua TLS bảo mật).<br>• Cổng `8884`: WSS (MQTT qua WebSockets + TLS). |
| **Object Storage (MinIO S3)** | Cloud Server (hoặc Supabase Storage) | • Lưu trữ vector ảnh khuôn mặt (`face-profiles`).<br>• Lưu trữ ảnh bằng chứng sự cố (`security-evidence`).<br>• Lưu trữ video giám sát MP4 (`camera-recordings`). | Hỗ trợ chuẩn S3 API, mã hóa phía máy chủ (`SSE-S3`). |
| **Backend (Spring Boot 3)** | Cloud Server (Render / Railway) | • Quản lý nghiệp vụ, xác thực JWT, phân quyền RBAC.<br>• Tiêu thụ sự kiện an ninh từ MQTT Broker (khử trùng theo `event_id`).<br>• Điều phối chuông báo, hàng đợi sự cố cho bảo vệ.<br>• Cung cấp REST API & WebSocket STOMP cho Client. | Kết nối PostgreSQL JDBC, tiêu thụ MQTT tin cậy. |
| **Cơ sở dữ liệu (PostgreSQL 16 + pgvector)** | Cloud Server (hoặc Supabase) | • Lưu trữ quan hệ thực thể nghiệp vụ.<br>• Tìm kiếm tương đồng vector khuôn mặt (HNSW Index). | Chạy nền tảng Cloud ổn định. |
| **Frontend & Mobile Client** | Vercel (Web) / Thiết bị di động | • Web SOC / Guard Dashboard (React 19 / Vite).<br>• Mobile App cho Bảo vệ thực địa (Flutter). | Gọi REST API HTTPS & WebSocket STOMP lên Cloud Backend. |

---

## 2. Sơ đồ Dòng Dữ liệu & Biên Giới Mạng (Data Flow & Network Boundaries)

```
       KHOẢNG CÁCH MẠNG NỘI BỘ (CAMPUS LAN)               │           HẠ TẦNG ĐÁM MÂY (CLOUD INFRASTRUCTURE)
                                                          │
  [ IP Camera IMOU ]                                      │
         │ (RTSP TCP nội bộ)                              │
         ▼                                                │
  ┌────────────────────────────────────────────────────┐  │
  │     EDGE DEVICE (RASPBERRY PI / EDGE PC)           │  │
  │                                                    │  │
  │  [ MediaMTX Gateway (Local) ]                      │  │
  │         │                                          │  │
  │         ▼                                          │  │
  │  [ AI Service (Python / OpenCV / YOLO11n) ]        │  │
  │         │                                          │  │
  │    (Phát hiện vi phạm: Xâm nhập / Người lạ)        │  │
  │         │                                          │  │
  │         ├──────────────────────────┐               │  │
  │         ▼                          ▼               │  │
  │  [ Ghi hình MP4 ]         [ AlertOutbox ]          │  │
  │  (480p@15fps + OSD)       (SQLite bền vững)        │  │
  │         │                          │               │  │
  └─────────┼──────────────────────────┼───────────────┘  │
            │                          │                  │
            │ Outbound S3 Upload       │ Outbound MQTT    │
            │ (MinIO API)              │ (QoS 1 Auth)     │
            │                          │                  │
════════════╪══════════════════════════╪══════════════════╧══════════════════════════════════════════════════════
            │ (Internet / NAT)         │ (Internet / NAT)
            ▼                          ▼
  ┌───────────────────┐      ┌────────────────────────────────────────────────────────┐
  │  Cloud MinIO S3   │      │        Cloud Eclipse Mosquitto Broker                  │
  │                   │      │                                                        │
  │ • security-       │      │  • Xác thực bắt buộc: allow_anonymous false            │
  │   evidence        │      │  • Phân quyền ACL: icss-ai (write) / icss-backend(read)│
  │ • camera-         │      │  • Persistence: lưu tin nhắn chờ vào đĩa               │
  │   recordings      │      └──────────────────────────┬─────────────────────────────┘
  └───────────────────┘                                 │
                                                        │ MQTT Subscriber (cleanSession=false)
                                                        ▼
                                             ┌────────────────────────────────────────┐
                                             │         Cloud Backend Subsystem        │
                                             │                                        │
                                             │  • Spring Boot 3 (MqttConfig)          │
                                             │  • Khử trùng theo event_id             │
                                             │  • Dispatch WebSocket STOMP            │
                                             └──────────────┬─────────────────────────┘
                                                            │
                                                            ▼ (HTTPS / WSS)
                                             ┌────────────────────────────────────────┐
                                             │    Client: Web SOC & Mobile Guard      │
                                             └────────────────────────────────────────┘
```

---

## 3. Thiết kế Thiết bị Biên với Raspberry Pi (Edge Device Deep-Dive)

### 3.1. Tại sao Raspberry Pi KHÔNG stream video lên Cloud?
1. **Tiết kiệm 99% Băng thông Internet**:
   - 1 Camera 1080p cần $2 - 4\text{ Mbps}$ upload liên tục.
   - 5 Camera cần $10 - 20\text{ Mbps}$ upload 24/7 ($\approx 3 - 6\text{ TB/tháng}$).
   - Với Edge AI, Pi chỉ gửi dữ liệu khi có sự cố: gói tin JSON cảnh báo chỉ $\approx 1\text{ KB}$ và 1 đoạn video clip ngắn khi xảy ra vi phạm.
2. **Cắt giảm Chi phí Cloud**:
   - Cloud không cần thuê GPU máy ảo đắt tiền ($100 - $500/tháng như AWS `g4dn.xlarge`).
   - Server Cloud chỉ cần VPS CPU thông thường để chạy Spring Boot, PostgreSQL, Mosquitto và MinIO.
3. **Độc lập và Tin cậy (Offline-First)**:
   - Dù mạng Internet trường học bị gián đoạn, Raspberry Pi vẫn giám sát, phát hiện người vi phạm và lưu vết bằng chứng bình thường tại chỗ.

### 3.2. Cấu hình Phần cứng Khuyến nghị cho Raspberry Pi

| Dòng Raspberry Pi | Phần cứng Bổ sung | Tốc độ Xử lý (FPS) | Mô hình Vận hành Phù hợp |
|---|---|---|---|
| **Raspberry Pi 5** *(Khuyến nghị chuẩn)* | **Raspberry Pi AI Kit** *(NPU Hailo-8L 13 TOPS hoặc Hailo-8 26 TOPS, kết nối qua PCIe)* | **$25 - 30\text{ FPS}$** (Xử lý mượt mà thời gian thực) | Chạy full-pipeline tại chỗ: Ingestion $\rightarrow$ Motion Gate $\rightarrow$ YOLO11n detection $\rightarrow$ ROI Access Gate $\rightarrow$ Tracking $\rightarrow$ Recording. |
| **Raspberry Pi 5 (CPU thuần)** | Tản nhiệt chủ động (Active Cooler), NVMe SSD qua PCIe HAT | **$8 - 12\text{ FPS}$** (1 luồng camera) | Áp dụng **Motion-Gated Pipeline**: Bình thường chỉ chạy OpenCV Motion Detector ($<5\%$ CPU); chỉ khi có chuyển động mới bật YOLO11n quét người. |
| **Raspberry Pi 4 (4GB / 8GB)** | Quạt làm mát, thẻ nhớ Class 10 / SSD USB 3.0 | **$2 - 5\text{ FPS}$** | Phù hợp làm **Edge Gateway + Event-Triggered**: Dò chuyển động tại chỗ $\rightarrow$ crop khung hình nghi vấn gửi lên Backend qua REST Outbound để AI Cloud hỗ trợ hoặc chạy YOLO Nano với chu kỳ trích xuất khung hình $2\text{ fps}$. |

---

## 4. Mặt phẳng Truyền tin Bền vững (Tham chiếu Commit `2300704a55598c1bfad64e36d057fc0b6fa79a60`)

Commit `2300704a55598c1bfad64e36d057fc0b6fa79a60` giải quyết triệt để 3 bài toán sống còn khi triển khai Edge-to-Cloud qua Internet: **Bảo mật Broker**, **Chống mất gói tin khi rớt mạng (Reliability)**, và **Đồng bộ trạng thái bền vững**.

### 4.1. Bảo mật Broker & Phân quyền ACL (Least-Privilege)

Để ngăn chặn việc thiết bị biên bị chiếm quyền điều khiển hoặc kẻ xấu xâm nhập mạng nội bộ giả mạo bản tin MQTT:
1. **Vô hiệu hóa truy cập ẩn danh (`mosquitto.conf`)**:
   ```conf
   listener 1883 0.0.0.0
   allow_anonymous false
   password_file /mosquitto/auth/passwd
   acl_file /mosquitto/auth/acl
   max_queued_messages 10000
   persistence true
   persistence_location /mosquitto/data/
   ```
2. **Khởi tạo động Mật khẩu an toàn (`mosquitto/init.sh`)**:
   - Sử dụng mật khẩu ngẫu nhiên sinh từ môi trường (`MQTT_AI_PASSWORD`, `MQTT_BACKEND_PASSWORD`).
   - Tạo file passwd với quyền hạn `0600` thuộc sở hữu của user `mosquitto`.
3. **Kiểm soát Truy cập chủ động (`mosquitto/config/acl`)**:
   ```acl
   # Edge AI: Chỉ có quyền WRITE vào topic cảnh báo và trạng thái camera
   user icss-ai
   topic write campus/alerts/incidents
   topic write campus/cameras/+/health

   # Backend: Chỉ có quyền READ từ topic cảnh báo an ninh
   user icss-backend
   topic read campus/alerts/incidents
   ```
   > **Lợi ích an ninh:** Dù ai đó chiếm được thiết bị Raspberry Pi tại hiện trường, họ chỉ có thể gửi cảnh báo vào topic quy định, **không thể nghe lén dữ liệu** của các camera khác và không thể can thiệp vào các kênh điều khiển khác.

### 4.2. Hàng đợi Bền vững SQLite trên Edge (`AlertOutbox`)

Khi mạng Internet trường học bị rớt hoặc Mosquitto Broker trên Cloud khởi động lại:
- Trước đây: Bản tin gửi đi bị lỗi, cảnh báo sự cố an ninh bị mất vĩnh viễn.
- Thiết kế mới (`ai-service/src/integration/alert_outbox.py`):
  1. **Write-Ahead Outbox**: Mọi cảnh báo phát hiện từ AI Service **bắt buộc được ghi xuống cơ sở dữ liệu SQLite cục bộ (`data/mqtt_outbox.sqlite3`) TRƯỚC KHI** gọi hàm publish mạng:
     ```sql
     CREATE TABLE IF NOT EXISTS outbox (
         event_id TEXT PRIMARY KEY,
         topic TEXT,
         payload TEXT,
         created_at REAL,
         attempts INTEGER,
         last_attempt_at REAL,
         last_error TEXT
     );
     ```
  2. **Xác nhận PUBACK 2 chiều (`_on_publish`)**:
     - Khi `client.publish(..., qos=1)` được gọi, hệ thống ánh xạ mã bản tin MQTT `mid` với `event_id`.
     - Chỉ khi Broker gửi lại gói tin **PUBACK** thành công, bản ghi trong SQLite mới được xóa (`DELETE FROM outbox WHERE event_id = ?`).
     - Hỗ trợ cơ chế `_early_acks` với TTL 10 giây để xử lý trường hợp mạng cực nhanh khiến PUBACK về trước khi hàm `publish()` trả về.
  3. **Luồng quét tự động (`_flush_loop`)**:
     - Tiến trình nền quét định kỳ mỗi 5 giây (`MQTT_OUTBOX_FLUSH_SECONDS`).
     - Khi phát hiện các bản tin quá hạn xác nhận (`MQTT_OUTBOX_INFLIGHT_TIMEOUT_SECONDS = 30s`), tự động đẩy lại theo đợt (`_FLUSH_BATCH_SIZE = 200`).
     - Ngay khi MQTT kết nối lại thành công (`_on_connect`), luồng lập tức được kích hoạt (`_wake.set()`) để đẩy toàn bộ cảnh báo tồn đọng lên Cloud mà không cần đợi hết chu kỳ 5 giây.
  4. **Cơ chế Giới hạn Đĩa (`purge`)**:
     - Giữ tối đa 10,000 dòng (`MQTT_OUTBOX_MAX_ROWS`) hoặc tối đa 24 giờ (`MQTT_OUTBOX_MAX_AGE_HOURS`), bảo vệ thẻ nhớ Raspberry Pi không bị tràn dung lượng trong trường hợp mất mạng nhiều ngày.

### 4.3. Đón nhận Tin cậy tại Backend (`MqttConfig.java`)

1. **Phiên Bền vững (`cleanSession = false`)**:
   - Backend sử dụng `clientId = "campus-security-backend"` cố định kết hợp `cleanSession=false`.
   - Khi Backend Cloud được deploy lại hoặc restart trong 1–2 phút, Mosquitto Broker tự động lưu lại toàn bộ các bản tin `QoS 1` trong hàng đợi của phiên. Khi Backend kết nối lại, Mosquitto tự động tống toàn bộ các sự cố tồn đọng sang Backend.
2. **Khử trùng Bản tin (Idempotency)**:
   - Backend sử dụng trường `event_id` (UUID duy nhất do Edge sinh ra) làm khóa định danh.
   - Khi Edge gửi lại bản tin do nghi ngờ mất gói tin, Backend kiểm tra `event_id` trong cơ sở dữ liệu để bỏ qua các bản tin trùng lặp, đảm bảo không tạo 2 sự cố giống nhau trên hệ thống.
3. **Lên lịch Kết nối lại Chống Deadlock**:
   - Paho v3 có hạn chế: nếu không kết nối được ngay từ lần đầu khởi động, cờ `automaticReconnect` sẽ không kích hoạt.
   - Giải pháp: Sử dụng `ScheduledExecutorService` (`mqtt-connect`) tự động thử lại sau mỗi 10 giây (`connectRetrySeconds`). Việc đăng ký `subscribeIncidents()` được tách khỏi callback Paho để tránh nghẽn thread.

### 4.4. Đánh giá Hiện trạng Triển khai trong Codebase (Codebase Implementation Status)

Đối chiếu khách quan giữa thiết kế kiến trúc mục tiêu và những gì đang thực sự có trong mã nguồn:

| Hạng mục thiết kế | Trạng thái trong Code | Vị trí kiểm chứng trong Codebase | Chi tiết kỹ thuật & Hướng hoàn thiện |
|---|:---:|---|---|
| **1. MQTT chạy trên nền kết nối TCP socket (100% Outbound, Full-Duplex)** | **ĐÃ GIẢI QUYẾT 100%** | • `ai-service/src/integration/mqtt_client.py`<br>• `backend/.../config/MqttConfig.java`<br>• `mosquitto/config/mosquitto.conf` | Cả Edge Python và Cloud Java đều mở kết nối Persistent TCP Socket ra Mosquitto Broker (`tcp://...:1883`), duy trì `keepalive=30s`. Thiết bị Edge chủ động mở kết nối Outbound đi ra, sau đó nhận dữ liệu 2 chiều qua `subscribe` hoàn toàn không cần mở port mạng tại trường. |
| **2. Cơ chế Outbound Sync khuôn mặt tại Edge** | **ĐÃ GIẢI QUYẾT 100%** | • `ai-service/src/core/shared_face_cache.py`<br>(Hàm `refresh_cache()`) | Khi Edge khởi động hoặc khi nhận tín hiệu làm mới qua MQTT `campus/edge/sync/faces`, Edge AI chủ động tạo kết nối Outbound gọi Backend API Cloud (`/api/internal/ai/sync-data`) để nạp vector 512 chiều vào RAM nội bộ, không đọc trực tiếp PostgreSQL (kết nối trực tiếp DB chỉ là phương án dự phòng cục bộ khi cấu hình). |
| **3. Đăng ký người dùng 100% trên Cloud Backend (Face Enrollment)** | **CHƯA ĐẦY ĐỦ (Đang ở chế độ Monolith / Local Dev)** | • `backend/.../service/FaceDataService.java`<br>(Dòng 88-101)<br>• `backend/.../service/GuestFaceEmbeddingClient.java` | **Hiện trạng:** Khi đăng ký người dùng/khách, Backend Spring Boot vẫn đang gọi HTTP REST trực tiếp sang AI Service (`POST /api/v1/faces/process-registration`).<br>**Vấn đề khi deploy:** Nếu tách Cloud Backend và Edge AI (Raspberry Pi), Backend Cloud không thể gọi HTTP Inbound vào Raspberry Pi.<br>**Giải pháp khi deploy Cloud:** Cần triển khai một container AI phụ siêu nhẹ (hoặc nhúng thư viện `onnxruntime` Java) chạy ngay trên Cloud chuyên xử lý trích xuất ảnh tĩnh, tách biệt hoàn toàn với Edge AI chạy camera. |

---

## 5. Danh mục Biến Môi trường Triển khai (Environment Configuration)

### 5.1. Cấu hình trên Cloud Server (File `.env`)
```properties
# --- Mosquitto Authentication (Tự sinh bằng: openssl rand -hex 24) ---
MQTT_AI_PASSWORD=<set-in-env>
MQTT_BACKEND_PASSWORD=<set-in-env>

# --- Backend Application Settings (MQTT) ---
MQTT_BROKER_URL=tcp://localhost:1883        # Đổi thành ssl://localhost:8883 khi bật TLS
MQTT_BACKEND_USERNAME=icss-backend
MQTT_BACKEND_PASSWORD=<set-in-env>
MQTT_CONNECT_RETRY_SECONDS=10
# Cấu hình Truststore nếu Broker dùng chứng chỉ CA tự ký (tuỳ chọn):
MQTT_TRUSTSTORE_PATH=                       # Ví dụ: ../mosquitto/certs/truststore.jks
MQTT_TRUSTSTORE_PASSWORD=                   # Mật khẩu truststore (ví dụ: changeit)
MQTT_TRUSTSTORE_TYPE=JKS

# --- Chế độ Cloud: Để trống URL HTTP Inbound tới Edge ---
AI_SERVICE_URL=
MEDIAMTX_API_URL=

# --- MinIO Storage ---
MINIO_ENDPOINT=http://localhost:9000
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=<set-in-env>
MINIO_KMS_SECRET_KEY=<set-in-env>

# --- Audit đăng nhập (H1): khoá HMAC băm chuỗi đăng nhập sai không khớp tài khoản (>= 32 ký tự, bắt buộc) ---
# Tự sinh bằng: openssl rand -hex 32
APP_SECURITY_AUDITHASHKEY=<set-in-env>
```

> **Khoá băm audit (`APP_SECURITY_AUDITHASHKEY`)** — bắt buộc ở mọi môi trường chạy backend (dev, demo, cloud); thiếu hoặc
> ngắn hơn 32 ký tự thì backend dừng ngay khi khởi động với thông báo nêu tên biến. Sinh một lần cho mỗi môi trường:
> `openssl rand -hex 32` (64 ký tự hex), đặt vào `.env` / biến môi trường của server, không commit. Giữ nguyên khoá giữa
> các lần triển khai: đổi khoá thì mã băm trong nhật ký cũ không còn so khớp được với mã băm mới (vẫn đọc được, chỉ không
> gom chung lần thử của cùng một chuỗi trước và sau khi đổi).

### 5.2. Cấu hình trên Raspberry Pi / Edge Device (File `ai-service/.env`)
```properties
# --- Kết nối Outbound lên Cloud Broker ---
MQTT_BROKER_HOST=cloud.yourdomain.com      # hoặc IP Public của VPS Cloud
MQTT_BROKER_PORT=1883                     # hoặc 8883 (TLS) / 8884 (WSS) nếu dùng mã hóa
MQTT_AI_CLIENT_ID=edge-cam-01                # BẮT BUỘC ĐỔI: Mỗi Edge Device phải có Client ID duy nhất để không đá nhau khỏi Broker
MQTT_AI_USERNAME=icss-ai
MQTT_AI_PASSWORD=<set-in-env>
MQTT_TLS_ENABLED=false                    # Đặt true khi kết nối bảo mật qua cổng 8883/8884
MQTT_TLS_CA_PATH=                         # Đường dẫn file Root CA (ví dụ: ../mosquitto/certs/ca.crt)
MQTT_TLS_INSECURE=false                   # Chỉ đặt true trong môi trường Dev nếu hostname không khớp SAN
MQTT_TRANSPORT=tcp                        # "tcp" cho cổng 8883 hoặc "websockets" cho cổng 8884 WSS

# --- Cấu hình AlertOutbox (Lưu đĩa chống mất mạng) ---
MQTT_OUTBOX_PATH=data/mqtt_outbox.sqlite3
MQTT_OUTBOX_FLUSH_SECONDS=5.0
MQTT_OUTBOX_INFLIGHT_TIMEOUT_SECONDS=30.0
MQTT_OUTBOX_MAX_ROWS=10000
MQTT_OUTBOX_MAX_AGE_HOURS=24.0

# --- Kết nối MinIO Cloud để Upload Bằng chứng ---
MINIO_ENDPOINT=cloud.yourdomain.com:9000
MINIO_ROOT_USER=minioadmin
MINIO_ROOT_PASSWORD=<set-in-env>
MINIO_SECURE=false

# --- API Backend Cloud (Dùng cho Outbound Check-Entry & Sync khuôn mặt) ---
BACKEND_API_URL=https://cloud.yourdomain.com
INTERNAL_SERVICE_KEY=<set-in-env>

# --- PostgreSQL Database (Bắt buộc cấu hình trong config.py để khởi động AI Service; dùng khi nạp ROI trực tiếp) ---
POSTGRES_USER=sep
POSTGRES_PASSWORD=<set-in-env>
POSTGRES_DB=campus_security
DB_HOST=localhost
DB_PORT=5432

# --- MediaMTX Streaming trên Edge & Cloud Relay ---
MEDIAMTX_API_URL=http://localhost:9997
MEDIAMTX_RTSP_URL=rtsp://localhost:8554
CLOUD_MEDIAMTX_RTSP_URL=                  # Đặt RTSP URL Cloud khi cần bật On-Demand Stream Relay
```

---

## 6. Kịch bản Kiểm thử Thực tế (Verification & Chaos Testing)

### Kịch bản 1: Mất mạng Internet tại Campus khi đang có xâm nhập
1. **Hành động**: Ngắt dây mạng Internet của Raspberry Pi (hoặc tắt Wi-Fi).
2. **Kích hoạt sự cố**: Cho đối tượng không phận sự đi vào khu vực cấm trước Camera.
3. **Hiện tượng quan sát**:
   - `VideoPipeline` trên Pi vẫn phát hiện xâm nhập qua OpenCV & YOLO11n.
   - Video MP4 480p có nhãn OSD giờ Việt Nam vẫn được ghi và lưu tạm tại thẻ nhớ Pi.
   - File `data/mqtt_outbox.sqlite3` tăng thêm 1 bản ghi cảnh báo với trạng thái `last_attempt_at = NULL`.
4. **Khôi phục mạng**: Cắm lại dây mạng Internet cho Pi.
5. **Kết quả**:
   - `_on_connect` của Paho kích hoạt ngay lập tức.
   - Luồng `_flush_loop` tống bản tin tồn đọng lên Cloud Mosquitto Broker.
   - Cloud Backend nhận được sự cố, lưu vào PostgreSQL và phát WebSocket STOMP tới điện thoại bảo vệ.
   - Bản ghi trong SQLite outbox của Pi tự động được xóa sau khi nhận PUBACK.

### Kịch bản 2: Restart Cloud Backend trong khi Edge vẫn hoạt động
1. **Hành động**: Khởi động lại container Spring Boot Backend trên Cloud.
2. **Hiện tượng**: Mosquitto Broker giữ lại session của Backend (`cleanSession = false`).
3. **Kích hoạt sự cố**: Camera phát hiện người lạ, Pi gửi MQTT message tới Mosquitto.
4. **Kết quả**: Khi Backend khởi động xong, kết nối lại Mosquitto, toàn bộ cảnh báo bị trễ trong thời gian restart được Mosquitto tự động chuyển tiếp tới Backend mà không mất bất kỳ sự cố nào.

---

## 7. Kết luận & Tóm tắt Điểm nổi bật cho Hội đồng Bảo vệ

1. **Chuẩn kiến trúc Hiện đại (Edge-Cloud Continuum)**: Không dồn toàn bộ tải xử lý lên Cloud (tránh tốn kém GPU) và không dồn toàn bộ lưu trữ tại Edge (tránh mất mát dữ liệu khi thiết bị biên hỏng hóc).
2. **An toàn Mạng tối đa (Zero Inbound Ports)**: Trường học không cần cấu hình NAT, không cần mở Port modem, không cần IP tĩnh công cộng; toàn bộ kết nối đều từ Edge đi ra Cloud.
3. **Độ tin cậy Tuyệt đối (At-least-once Delivery with Outbox Pattern)**: Đảm bảo không mất mát bất kỳ sự cố an ninh nào dù mạng Internet chập chờn hay mất điện cục bộ.

---

## 8. Khảo sát & Khả thi Triển khai Nền tảng Đám mây (Railway, Render, Expo)

### 8.1. Triển khai Backend & MQTT lên Railway / Render

| Thành phần | Khả năng chạy trên Railway | Khả năng chạy trên Render | Khuyến nghị giải pháp tối ưu |
|---|---|---|---|
| **Spring Boot Backend** | **RẤT TỐT (Khuyên dùng)**<br>• Hỗ trợ Java 21 / Dockerfile mượt mà.<br>• Tự động cấp domain HTTPS và chứng chỉ SSL.<br>• Không bị "ngủ đông" (sleep) gây mất kết nối WebSocket STOMP. | **KHẢ THI NHƯNG HẠN CHẾ**<br>• Bản Free bị "Sleep" sau 15 phút không có web request; khi tỉnh dậy mất 30–50 giây, ngắt kết nối WebSocket tới Mobile/Web.<br>• Bắt buộc trả phí ($7/tháng) nếu muốn chạy 24/7. | **Railway** vượt trội hơn Render cho Spring Boot Backend nhờ khả năng duy trì WebSocket ổn định và cấu hình biến môi trường trực quan. |
| **Mosquitto MQTT Broker** | **KHẢ THI (Dùng TCP Proxy)**<br>• Railway hỗ trợ tính năng **TCP Proxy** cho phép mở một cổng TCP công cộng (dạng `roundhouse.proxy.rlwy.net:PORT`) ánh xạ vào container Mosquitto cổng 1883. | **KHÔNG KHẢ THI TRỰC TIẾP**<br>• Render Web Service **chỉ hỗ trợ cổng HTTP/HTTPS (80/443)**.<br>• Render **không mở cổng TCP thô (raw TCP port 1883)** cho thiết bị bên ngoài (như Raspberry Pi) kết nối vào, trừ khi phải đóng gói MQTT qua WebSocket WSS. | • Phương án 1: Triển khai Mosquitto trên **Railway** bật tính năng TCP Proxy.<br>• Phương án 2 (Tốt nhất & Ổn định nhất): Thuê **1 Cloud VPS giá rẻ ($4 - $5/tháng như Hetzner / DigitalOcean / Vultr)** chạy Docker Compose (Mosquitto 1883 + MinIO 9000 + Backend 8080). Không lo bị giới hạn cổng TCP. |

### 8.2. Triển khai Ứng dụng Di động (Mobile App): Có dùng Expo được không?

> ❌ **KHÔNG THỂ DÙNG EXPO CHO MOBILE APP CỦA DỰ ÁN NÀY!**

- **Lý do kỹ thuật:** Thư mục [`mobile/`](file:///c:/Projects/AI-Based-Intelligent-Campus-Security-Surveillance-System/mobile/) của dự án được viết hoàn toàn bằng **Flutter / Dart** (Google Flutter Framework với `pubspec.yaml`, `lib/`, `android/`, `ios/`).
- **Expo là gì:** Expo là nền tảng đóng gói dành riêng cho **React Native (JavaScript/TypeScript)**, hoàn toàn không tương thích với mã nguồn Flutter.

#### Giải pháp Đóng gói & Phân phối Ứng dụng Di động Flutter thực tế:
1. **Build file APK trực tiếp (Nhanh nhất & Đơn giản nhất cho bảo vệ đồ án/demo)**:
   - Chạy lệnh build file cài đặt Android:
     ```bash
     cd mobile
     flutter build apk --release
     ```
   - File kết quả: `mobile/build/app/outputs/flutter-apk/app-release.apk`.
   - Copy file APK này cài trực tiếp lên điện thoại Android của bạn, nhân viên bảo vệ hoặc thành viên Hội đồng qua Zalo / Google Drive / cáp USB.
2. **Phân phối qua link cài đặt (Nếu muốn giống trải nghiệm link tải của Expo)**:
   - Dùng **Firebase App Distribution** (Hoàn toàn miễn phí của Google): Tải file APK lên bảng điều khiển Firebase, gửi link mời cài đặt tới email của các giám khảo/bảo vệ.
   - Dùng **Diawi** (`diawi.com`): Kéo thả file APK lên trang web để lấy ngay mã QR và link cài đặt trực tiếp trên điện thoại trong 1 nốt nhạc.

