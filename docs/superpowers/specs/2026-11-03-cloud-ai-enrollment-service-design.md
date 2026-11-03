# Thiết kế Tính năng & Đánh giá An ninh: Cloud AI Enrollment Microservice
> **Dự án:** AI-Based Intelligent Campus Security Surveillance System  
> **Tài liệu:** Thiết kế Kỹ thuật (Design Spec) & Rà soát An ninh (Security Review)  
> **Ngày tạo:** 2026-11-03  
> **Trạng thái:** Chờ phê duyệt (Pending Approval)  
> **Phương án lựa chọn:** Cách 2 — Tách thành Microservice độc lập (`ai-enrollment-service/`) trên Cloud

---

## 1. Bối cảnh & Mục tiêu (Context & Motivation)

### 1.1. Vấn đề kiến trúc hiện tại
- Trong môi trường phát triển cục bộ (Monolith All-in-One), Backend Spring Boot gọi HTTP trực tiếp tới `http://localhost:8000/api/v1/faces/process-registration` để đếm khuôn mặt và trích xuất vector embedding 512D khi đăng ký nhân sự (`FaceDataService.java`) hoặc khách viếng thăm (`GuestFaceEmbeddingClient.java`).
- Khi triển khai theo mô hình **Cloud–Edge (Edge-Cloud Continuum)**:
  - Backend, Web, CSDL và MinIO nằm trên **Cloud**.
  - `ai-service` xử lý video giám sát camera nằm tại **Edge (Raspberry Pi)** trong mạng nội bộ trường học sau NAT/Firewall.
  - Backend trên Cloud **không thể gọi HTTP Inbound** vào Raspberry Pi tại trường học. Hơn nữa, việc đăng ký tài khoản từ xa không được phép phụ thuộc vào trạng thái bật/tắt hay đường truyền của thiết bị biên tại trường học.
- Việc nhúng thư viện `onnxruntime` native C++/JNI trực tiếp vào Spring Boot tiềm ẩn nguy cơ **Vector Drift** (lệch vector cosine similarity so với Python ArcFace tại Edge) và phức tạp hóa môi trường build Java.

### 1.2. Mục tiêu giải pháp
- Tách một microservice Python mini chuyên biệt mang tên **`ai-enrollment-service`** chạy trên Cloud (cùng hạ tầng với Spring Boot Backend).
- Dịch vụ này chỉ thực hiện 1 nhiệm vụ duy nhất: **Xử lý ảnh đăng ký tĩnh (Face Detection YuNet + Face Embedding ArcFace)**.
- Đảm bảo độ đồng nhất 100% về mặt toán học của vector 512D với Edge AI (cùng thuật toán tiền xử lý OpenCV và model ArcFace ONNX).
- Đóng gói container Docker siêu nhẹ ($\approx 250 - 300\text{ MB}$), ngốn ít RAM ($\approx 120\text{ MB}$ khi idle), khởi động dưới 3 giây.

---

## 2. Kiến trúc & Ranh giới Dịch vụ (Architecture & Boundaries)

```
[ Người dùng / Admin (Browser / Mobile) ]
                  │
                  ▼ (HTTPS Multipart Upload)
┌────────────────────────────────────────────────────────┐
│            Cloud Backend (Spring Boot 3)               │
│                                                        │
│ • Xác thực JWT & Quyền hạn (Admin / Staff)             │
│ • Kiểm tra kích thước file ảnh (<= 5MB)                │
│ • Gửi ảnh sang AI Enrollment Service (Mạng nội bộ)     │
│   (Header: X-Service-Key = INTERNAL_SERVICE_KEY)       │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼ (Internal HTTP POST /api/v1/faces/process-registration)
┌────────────────────────────────────────────────────────┐
│      Cloud AI Enrollment Service (FastAPI Mini)        │
│                                                        │
│ • Xác thực Service-to-Service: X-Service-Key           │
│ • Giải mã an toàn: Magic bytes check & cv2.imdecode    │
│ • FaceDetector (YuNet): đếm mặt, landmarks 5 điểm      │
│ • FaceEmbedder (ArcFace 512D): trích xuất vector       │
│ • Trả về JSON: face_count, embedding_front             │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼ (JSON Vector 512D)
┌────────────────────────────────────────────────────────┐
│         Lưu trữ & Đồng bộ Dữ liệu trên Cloud           │
│                                                        │
│ • PostgreSQL: lưu vector 512D vào bảng face_data       │
│ • MinIO S3: lưu ảnh gốc bảo mật vào bucket face-profiles│
│ • MQTT Broker: bắn tín hiệu campus/edge/events/faces   │
└──────────────────────────┬─────────────────────────────┘
                           │
                           ▼ (Outbound Sync qua Internet)
┌────────────────────────────────────────────────────────┐
│           Campus Edge Device (Raspberry Pi)            │
│                                                        │
│ • Nhận MQTT: gọi FaceMatcher.refresh_cache()           │
│ • Kéo vector mới từ PostgreSQL về lưu vào RAM Cache    │
│ • Nhận diện thời gian thực qua camera LAN (<0.05s)     │
└────────────────────────────────────────────────────────┘
```

---

## 3. Cấu trúc Thư mục Đề xuất (`ai-enrollment-service/`)

```
ai-enrollment-service/
├── Dockerfile                  # Base image python:3.11-slim, chỉ cài OpenCV headless & ONNX Runtime CPU
├── requirements.txt            # fastapi, uvicorn, numpy, opencv-python-headless, onnxruntime, pydantic-settings
├── README.md                   # Hướng dẫn chạy độc lập & test
├── models/                     # Chứa 2 model ONNX trích xuất tĩnh
│   ├── yunet_2023mar.onnx      # Face Detection & 5-point landmarks
│   └── w600k_r50.onnx          # ArcFace 512D Feature Extractor
├── src/
│   ├── __init__.py
│   ├── config.py               # Settings (INTERNAL_SERVICE_KEY, MAX_IMAGE_BYTES, MODEL_PATHS)
│   ├── core/
│   │   ├── __init__.py
│   │   ├── face_detector.py    # YuNet wrapper độc lập
│   │   └── face_embedder.py    # ArcFace wrapper độc lập (512D normalized vector)
│   ├── utils/
│   │   ├── __init__.py
│   │   └── image_security.py   # Magic bytes validation & safe image decoding
│   └── main.py                 # FastAPI app, middleware xác thực X-Service-Key, route /process-registration
└── tests/
    ├── __init__.py
    ├── conftest.py
    └── test_enrollment_api.py  # Unit tests cho trường hợp 0 mặt, 1 mặt, nhiều mặt, sai key
```

---

## 4. Đặc tả API Contract (`/api/v1/faces/process-registration`)

- **Method**: `POST`
- **Headers**:
  - `Content-Type: multipart/form-data`
  - `X-Service-Key: <INTERNAL_SERVICE_KEY>` (Bắt buộc)
- **Form Data**:
  - `code` (string, required): Mã người dùng / nhân viên (VD: `NV001`).
  - `full_name` (string, optional): Họ tên.
  - `front_image` (file, required): File ảnh định dạng JPG/PNG.
- **Response Success (`200 OK`)**:
  ```json
  {
    "success": true,
    "code": "NV001",
    "full_name": "Nguyen Van A",
    "face_count": 1,
    "embedding_front": [0.0124, -0.0512, ..., 0.0891]
  }
  ```
- **Response Validation Errors**:
  - `401 Unauthorized`: Thiếu hoặc sai header `X-Service-Key`.
  - `400 Bad Request`: Ảnh hỏng, không chứa khuôn mặt (`face_count == 0`), hoặc chứa nhiều hơn 1 khuôn mặt (`face_count > 1`).
  - `413 Payload Too Large`: Dung lượng ảnh vượt quá ngưỡng quy định ($> 5\text{MB}$).

---

## 5. RÀ SOÁT AN NINH TOÀN DIỆN (SECURITY REVIEW & THREAT MODELING)

Thực hiện rà soát an ninh chuyên sâu bám sát tiêu chuẩn OWASP API Security Top 10 và hướng dẫn `security-review` của dự án:

### Finding 1: Rủi ro Tấn công Khai thác Tệp ảnh Tải lên (File Upload Attack & Decompression Bomb)
- **Mức độ (Severity):** **HIGH**
- **Vị trí (Location):** `ai-enrollment-service/src/main.py#L40` & `image_security.py`
- **Mã lỗi (Vulnerability Type):** `CWE-400 (Uncontrolled Resource Consumption)`, `CWE-434 (Unrestricted Upload of File with Dangerous Type)`
- **Kịch bản tấn công (Attack Scenario):**
  Kẻ tấn công gửi một tệp độc hại giả mạo đuôi `.jpg` (như file zip bomb giải nén ra hàng chục GB bộ nhớ, hoặc file nhị phân shellcode). Nếu ứng dụng đọc trực tiếp toàn bộ dữ liệu vào RAM hoặc gọi `cv2.imdecode` thiếu kiểm soát, container có thể bị Out-Of-Memory (OOM Crash) hoặc tràn bộ đệm thư viện native.
- **Biện pháp xử lý (Remediation):**
  1. **Magic Bytes Check:** Kiểm tra tiền tố byte nhị phân của tệp (`\xFF\xD8\xFF` cho JPEG, `\x89PNG\r\n\x1a\n` cho PNG) trước khi giải mã, từ chối ngay lập tức nếu không khớp.
  2. **Giới hạn kích thước nghiêm ngặt (Max Body Size):** Giới hạn dung lượng tối đa $5\text{ MB}$ ngay ở tầng middleware trước khi đọc nội dung vào bộ nhớ.
  3. **Giới hạn kích thước ảnh sau giải mã:** Kiểm tra `height * width <= 4096 * 4096` để chống ảnh độ phân giải dị thường làm cạn kiệt CPU.

### Finding 2: Truy cập Trái phép vào Dịch vụ Nội bộ (Missing Service-to-Service Authentication)
- **Mức độ (Severity):** **HIGH**
- **Vị trí (Location):** `ai-enrollment-service/src/main.py#L25`
- **Mã lỗi (Vulnerability Type):** `CWE-306 (Missing Authentication for Critical Function)`
- **Kịch bản tấn công (Attack Scenario):**
  Nếu `ai-enrollment-service` được triển khai trên Cloud (ví dụ một container trên Railway hoặc VPS) mà cổng HTTP vô tình bị lộ ra ngoài Internet mà không có xác thực, kẻ xấu có thể gửi hàng nghìn ảnh để ép server tính toán ArcFace liên tục gây cạn kiệt CPU (Denial of Service), hoặc lợi dụng dịch vụ để trích xuất vector khuôn mặt trái phép.
- **Biện pháp xử lý (Remediation):**
  Bắt buộc cài đặt Middleware xác thực `X-Service-Key` khớp với biến môi trường `INTERNAL_SERVICE_KEY` (tối thiểu 32 ký tự ngẫu nhiên). Mọi request thiếu header này hoặc sai key đều bị từ chối ngay lập tức với mã `401 Unauthorized` bằng so sánh hằng số thời gian `secrets.compare_digest` (chống Timing Attack).

### Finding 3: Rò rỉ Dữ liệu Sinh trắc học qua Nhật ký (Biometric Data Leakage in Logs)
- **Mức độ (Severity):** **MEDIUM**
- **Vị trí (Location):** `ai-enrollment-service/src/main.py#L65`
- **Mã lỗi (Vulnerability Type):** `CWE-532 (Insertion of Sensitive Information into Log File)`
- **Kịch bản tấn công (Attack Scenario):**
  Ghi log chi tiết payload phản hồi chứa mảng 512 số thực `embedding_front`. Khi log được thu thập vào hệ thống giám sát tập trung (như Datadog, Grafana Loki, hoặc Cloud Console), dữ liệu sinh trắc học khuôn mặt của người dùng bị phơi bày cho các kỹ sư vận hành hoặc bị trích xuất trái phép.
- **Biện pháp xử lý (Remediation):**
  Chỉ log metadata gồm: `code`, `face_count`, thời gian xử lý (`elapsed_ms`). Nghiêm cấm in mảng vector hoặc mã nhị phân ảnh ra `logger.info` hay `logger.debug`.

### Finding 4: Giả mạo hoặc Chèn ép Tệp Mô hình AI (Model Injection & Tampering)
- **Mức độ (Severity):** **LOW**
- **Vị trí (Location):** `ai-enrollment-service/src/config.py#L15`
- **Mã lỗi (Vulnerability Type):** `CWE-73 (External Control of File Name or Path)`
- **Kịch bản tấn công (Attack Scenario):**
  Nếu đường dẫn tải mô hình ONNX cho phép nhận tham số từ bên ngoài, kẻ tấn công có thể chỉ định load một tệp ONNX độc hại từ đường dẫn bất kỳ.
- **Biện pháp xử lý (Remediation):**
  Cố định cứng (Hardcode) thư mục nạp model trong container (`/app/models/`), chỉ đọc các tệp `.onnx` có sẵn được đóng gói từ lúc build Docker image, không cho phép truyền đường dẫn model qua request API.

---

## 6. So sánh Đánh đổi Kiến trúc (Approaches Trade-off)

1. **Cách 1: Profile-switch trong `ai-service`**:
   - *Ưu điểm:* Không thêm thư mục mới.
   - *Nhược điểm:* Docker image vẫn cồng kềnh ($\approx 1.5\text{ GB}$ vì chứa dependencies của YOLO, RTSP, MediaMTX) dù chạy trên Cloud chỉ cần OpenCV và ArcFace.
2. **Cách 2: Tách `ai-enrollment-service` riêng biệt (Được chọn)**:
   - *Ưu điểm:* Image siêu nhẹ ($\approx 250\text{ MB}$), cô lập hoàn toàn bề mặt tấn công, bảo trì độc lập, deploy lên Railway/Cloud VPS chỉ mất $30\text{ giây}$.
   - *Đánh đổi:* Thêm 1 thư mục dự án nhỏ cần quản lý file model ArcFace/YuNet đồng bộ với `ai-service`.
3. **Cách 3: Nhúng ONNX Runtime C++/Java vào Spring Boot**:
   - *Đánh đổi:* Nguy cơ lệch vector (Vector Drift), khó build JNI đa nền tảng, làm nặng process Spring Boot. Bị loại trừ.

---

## 7. Kế hoạch Triển khai (Implementation Steps)

1. **Bước 1**: Khởi tạo thư mục `ai-enrollment-service/` với đầy đủ cấu trúc (`requirements.txt`, `config.py`, `face_detector.py`, `face_embedder.py`, `image_security.py`, `main.py`).
2. **Bước 2**: Sao chép 2 tệp mô hình ONNX đã chuẩn hóa (`yunet_2023mar.onnx` và `w600k_r50.onnx`) vào `ai-enrollment-service/models/`.
3. **Bước 3**: Cấu hình Dockerfile tối ưu kích thước và viết bộ unit test kiểm thử bảo mật (`test_enrollment_api.py`).
4. **Bước 4**: Cập nhật `docker-compose.yml` trên Cloud để bổ sung service `sep_ai_enrollment` kết nối nội bộ với Backend qua `INTERNAL_SERVICE_KEY`.
5. **Bước 5**: Kiểm thử tích hợp toàn trình luồng đăng ký người dùng từ Frontend $\rightarrow$ Backend $\rightarrow$ `ai-enrollment-service` $\rightarrow$ PostgreSQL `face_data`.
