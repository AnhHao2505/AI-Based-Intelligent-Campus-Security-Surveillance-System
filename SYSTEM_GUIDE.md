# 🛡️ FPTU Campus Security — Hướng Dẫn Vận Hành Hệ Thống (System Guide)

> **Mã Đề Tài:** FA26SE040  
> **Dự Án:** _AI-Based Intelligent Campus Security Surveillance System for Restricted Area Access Control in Tân Uyên FPTU Campus_  
> **Phiên bản:** 1.0 (Local Development & Deployment)

---

## 📌 MỤC LỤC

1. [Danh Sách Đường Dẫn Web (Endpoints)](#1-danh-sách-đường-dẫn-web)
2. [Danh Sách Tài Khoản Kiểm Thử (Credentials)](#2-danh-sách-tài-khoản-kiểm-thử)
3. [Hướng Dẫn Quản Lý Dataset Khuôn Mặt (Face Dataset)](#3-hướng-dẫn-quản-lý-dataset-khuôn-mặt)
4. [Cẩm Nang Câu Lệnh Vận Hành (CLI Reference)](#4-cẩm-nang-câu-lệnh-vận-hành)

---

## 1. Danh Sách Đường Dẫn Web

### Giao diện Người dùng (Frontend):

- **Trang Đăng Nhập:** [http://localhost:5173/login](http://localhost:5173/login)
- **Trang Chủ / Dashboard Chung:** [http://localhost:5173/](http://localhost:5173/)
- **Bảng Điều Khiển Admin:** [http://localhost:5173/admin](http://localhost:5173/admin)
- **Quản Lý Dataset Khuôn Mặt:** [http://localhost:5173/admin/faces](http://localhost:5173/admin/faces)
- **Màn Hình Giám Sát Bảo Vệ (Guard Console):** [http://localhost:5173/guard](http://localhost:5173/guard)

### Giao diện Quản trị Dịch vụ (Service Consoles):

- **Kho ảnh MinIO (S3):** [http://localhost:9001](http://localhost:9001)
- **Tài liệu API AI Service (Swagger):** [http://localhost:8000/docs](http://localhost:8000/docs)
- **Web pgAdmin 4:** [http://localhost:5050](http://localhost:5050)
- **Kiểm tra sức khỏe Backend (Actuator):** [http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)

---

## 2. Danh Sách Tài Khoản Kiểm Thử

### 1. Tài khoản Người dùng Hệ thống (Đăng nhập tại `/login`):

| Vai trò (Role)                           | Email                     | Mật khẩu | Mã nhân sự (MSNV/MSSV) | Quyền hạn                                                   |
| :--------------------------------------- | :------------------------ | :------: | :--------------------: | :---------------------------------------------------------- |
| **Quản trị viên (ADMIN)**                | `admin@fpt.edu.vn`        | `123456` |        `AD-001`        | Toàn quyền hệ thống, quản lý tài khoản & dataset khuôn mặt  |
| **Bảo vệ (INTERNAL_GUARD)**              | `guard.an@fpt.edu.vn`     | `123456` |       `SEC-001`        | Xem camera trực tiếp, tiếp nhận cảnh báo đột nhập/lảng vảng |
| **Quản lý CSVC (FACILITY_MANAGER)**      | `manager.binh@fpt.edu.vn` | `123456` |        `FM-001`        | Quản lý thiết bị camera, xem báo cáo thống kê vi phạm       |
| **Sinh viên / Người dùng (NORMAL_USER)** | `student.tuan@fpt.edu.vn` | `123456` |        `SV-001`        | Người dùng sinh viên thông thường                           |

### 2. Danh sách Camera Mẫu Khởi Tạo (Seeded Cameras):

| Mã Camera | Tên Camera        | Trạng thái Hoạt động | Trạng thái Vận hành |
| :-------- | :---------------- | :------------------: | :-----------------: |
| `CAM-001` | Camera Cổng Chính |       `ACTIVE`       |      `ONLINE`       |
| `CAM-002` | Camera Sảnh A     |       `ACTIVE`       |      `ONLINE`       |
| `CAM-003` | Camera Thư Viện   |       `ACTIVE`       |      `OFFLINE`      |
| `CAM-004` | Camera Bãi Xe     |       `ACTIVE`       |      `ONLINE`       |

### 3. Tài khoản Dịch vụ Hạ tầng:

| Dịch vụ                 | Tên đăng nhập (User) | Mật khẩu (Password) | Ghi chú                                        |
| :---------------------- | :------------------- | :------------------ | :--------------------------------------------- |
| **PostgreSQL Database** | `sep`                | `123456`            | Tên CSDL: `campus_security`, Port `5432`       |
| **MinIO S3 Storage**    | `minioadmin`         | `minioadmin123`     | Tên Bucket chính: `security-evidence`          |
| **pgAdmin 4 Web**       | `admin@example.com`  | `admin123`          | Server host: `postgres`, DB: `campus_security` |

---

## 3. Hướng Dẫn Quản Lý Dataset Khuôn Mặt

Mô hình AI sử dụng kiến trúc **1 góc chụp** để nhận diện đối tượng ở mọi góc độ camera:

1. **Chính diện (0°):** Nhìn thẳng camera, khuôn mặt chiếm 50% - 70% khung hình.

> **💡 Tính năng tự động thông minh:** Hệ thống hỗ trợ mọi định dạng ảnh (`.JPG`, `.PNG`, `.WEBP`, `.BMP`) và **tự động chuyển đổi ngầm file `.HEIC` / `.HEIF` từ điện thoại iPhone/Samsung sang `.JPG`** ngay trong bộ nhớ.

### A. Nạp từng người ("➕ Thêm Hồ Sơ Mới"):

- Không cần quy tắc đặt tên. Chỉ cần điền Mã số (MSSV/MSNV), Họ tên và chọn 1 ảnh chân dung chính diện.

### B. Nạp hàng loạt ("📦 Nạp Hàng Loạt (.ZIP)"):

- Chuẩn bị file `.zip` chứa các file ảnh được đặt tên theo quy tắc:
  $$\text{[MÃ\_SỐ]}\_\text{[HỌ\_TÊN]}.\text{jpg} \quad \text{hoặc} \quad \text{[MÃ\_SỐ]}\_\text{[HỌ\_TÊN]}\_\text{front}.\text{jpg}$$
- **Ví dụ cụ thể:**
  - `SE194249_Nguyen-Hoang-Minh.jpg`
  - `SE194249_Nguyen-Hoang-Minh_front.jpg`
  - `SEC-001_Tran-Binh.jpg`

---

## 4. Cẩm Nang Câu Lệnh Vận Hành

Tất cả câu lệnh thực hiện từ thư mục gốc của dự án (`d:/DoAnSE/AI-Based-Intelligent-Campus-Security-Surveillance-System`):

### Khởi động & Dừng hệ thống:

```bash
# 1. Khởi động toàn bộ 7 container ngầm
docker compose up -d

# 2. Kiểm tra trạng thái các container (Đảm bảo các container đều healthy/Up)
docker ps

# 3. Dừng toàn bộ hệ thống
docker compose down
```

### Xem Nhật Ký (Logs):

```bash
# Xem log Backend theo thời gian thực
docker logs -f sep_backend

# Xem log AI Service theo thời gian thực
docker logs -f sep_ai_service

# Xem log Frontend
docker logs -f sep_frontend
```

### Cập nhật & Rebuild riêng từng dịch vụ khi sửa code:

```bash
# Rebuild Frontend khi sửa giao diện React
docker compose up -d --build frontend

# Rebuild Backend khi sửa Java / Spring Boot
docker compose up -d --build backend

# Rebuild AI Service khi sửa Python / Model
docker compose up -d --build ai-service
```

```
================================================================================
HƯỚNG DẪN CHẠY MINIO TRÊN LOCAL VÀ DOCKER
AI-Based Intelligent Campus Security Surveillance System (ICSS)
================================================================================

1. GIỚI THIỆU

---

MinIO là hệ thống Object Storage tương thích với Amazon S3, được sử dụng trong hệ
thống ICSS để lưu trữ ảnh hồ sơ khuôn mặt (`face-profiles`) và ảnh bằng chứng vi
phạm an ninh (`security-evidence`).

Theo kiến trúc chuẩn của dự án:

- MinIO CHỈ KẾT NỐI VỚI BACKEND (Spring Boot).
- AI-Service không kết nối trực tiếp với MinIO.

Cấu hình tài khoản mặc định của dự án:

- Endpoint API : http://localhost:9000
- Console Web UI : http://localhost:9001
- Root Username : minioadmin
- Root Password : 12345678abc

================================================================================ 2. CÁCH 1: CHẠY MINIO BẰNG DOCKER (KHUYÊN DÙNG)

---

2.1. Sử dụng Docker Compose (Cùng với toàn bộ Infrastructure của hệ thống)
Dự án đã tích hợp sẵn dịch vụ MinIO trong file `docker-compose.yml` ở thư mục gốc.

Bước 1: Mở terminal tại thư mục gốc dự án (`AI-Based-Intelligent-Campus-Security-Surveillance-System`).

Bước 2: Khởi chạy container MinIO:
docker-compose up -d minio

Bước 3: Kiểm tra trạng thái container:
docker-compose ps minio

Bước 4: Kiểm tra log của MinIO:
docker-compose logs -f minio

Bước 5: Dừng dịch vụ MinIO khi không sử dụng:
docker-compose stop minio

2.2. Sử dụng Docker Command độc lập (Chỉ chạy MinIO container lẻ)
Nếu bạn chỉ muốn chạy duy nhất một container MinIO độc lập mà không cần docker-compose:

docker run -d \
 --name minio-server \
 -p 9000:9000 \
 -p 9001:9001 \
 -e "MINIO_ROOT_USER=minioadmin" \
 -e "MINIO_ROOT_PASSWORD=12345678abc" \
 -v minio_data:/data \
 minio/minio server /data --console-address ":9001"

================================================================================ 4. KẾT NỐI VÀ KIỂM TRA HỆ THỐNG

---

4.1. Truy cập Giao diện MinIO Console (Web Dashboard)

- Mở trình duyệt web và truy cập địa chỉ: http://localhost:9001
- Tên đăng nhập : minioadmin
- Mật khẩu : 12345678abc
- Sau khi đăng nhập, Backend (Spring Boot) sẽ TỰ ĐỘNG tạo 2 Buckets khi khởi chạy:
  1. `face-profiles` : Chứa ảnh hồ sơ cán bộ / nhân viên / sinh viên.
  2. `security-evidence`: Chứa ảnh snapshot bằng chứng vi phạm an ninh.

  4.2. Cấu hình file `.env` của Backend
  Đảm bảo file `.env` ở thư mục gốc dự án hoặc trong thư mục `backend/` có các thông số:
  MINIO_ENDPOINT=http://localhost:9000
  MINIO_ROOT_USER=minioadmin
  MINIO_ROOT_PASSWORD=12345678abc

  4.3. Kiểm tra kết nối từ Backend
  Khi khởi chạy Backend (Spring Boot), kiểm tra dòng log sau để xác nhận kết nối MinIO thành công:
  [MINIO] Created MinIO bucket: face-profiles (hoặc verified thành công)
  [MINIO] Created MinIO bucket: security-evidence

================================================================================
```
