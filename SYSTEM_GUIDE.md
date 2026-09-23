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

> **Mật khẩu chung cho tài khoản demo: 123456 (chỉ DB dev)**

| Vai trò (Role) | Email | Mật khẩu | MSNV/MSSV | Quyền hạn / Mục đích demo |
|:---|:---|:---:|:---:|:---|
| **Quản trị viên (ADMIN)** | `admin@fpt.edu.vn` | `123456` | `AD-001` | Toàn quyền hệ thống, quản lý tài khoản & dataset khuôn mặt |
| **Quản lý CSVC (FACILITY_MANAGER)** | `manager.binh@fpt.edu.vn` | `123456` | `FM-001` | Quản lý thiết bị camera, xét duyệt đơn, chỉ định nhân viên |
| **Quản lý CSVC (FACILITY_MANAGER)** | `manager.chi@fpt.edu.vn` | `123456` | `FM-002` | Quản lý cơ sở thứ 2, kiểm tra nhật ký audit log |
| **Bảo vệ (GUARD)** | `guard.an@fpt.edu.vn` | `123456` | `SEC-001` | Trực ca sáng Cổng chính, nhận và xử lý sự cố an ninh |
| **Bảo vệ (GUARD)** | `guard.demo@fpt.edu.vn` | `123456` | `SEC-002` | Trực ca chiều Thư viện, tiếp nhận và xử lý sự cố an ninh |
| **Bảo vệ (GUARD)** | `guard.dung@fpt.edu.vn` | `123456` | `SEC-003` | Trực ca đêm Phòng Server, xử lý sự cố an ninh |
| **Sinh viên (NORMAL_USER - L1)** | `student.tuan@fpt.edu.vn` | `123456` | `SV-001` | Sinh viên, đơn đăng ký truy cập phòng Lab AI (PENDING) |
| **Sinh viên (NORMAL_USER - L1)** | `student.hoa@fpt.edu.vn` | `123456` | `SV-002` | Sinh viên, từng được chỉ định vào Lab IoT (đã hết hạn) |
| **Sinh viên (NORMAL_USER - L1)** | `student.nam@fpt.edu.vn` | `123456` | `SV-003` | Sinh viên, trưởng nhóm đăng ký truy cập nhóm vào Lab IoT |
| **Sinh viên (NORMAL_USER - L1)** | `student.linh@fpt.edu.vn` | `123456` | `SV-004` | Sinh viên, gửi đơn vào Phòng Server (bị từ chối) |
| **Giảng viên (NORMAL_USER - L2)** | `lecturer.hung@fpt.edu.vn` | `123456` | `GV-001` | Giảng viên, được chỉ định vào Lab AI (sắp hiệu lực) |
| **Giảng viên (NORMAL_USER - L2)** | `lecturer.mai@fpt.edu.vn` | `123456` | `GV-002` | Giảng viên, đơn đã duyệt trong khung giờ Lab AI (cấp quyền vào khu vực Level 3) |
| **Giảng viên (NORMAL_USER - L2)** | `lecturer.khoa@fpt.edu.vn` | `123456` | `GV-003` | Giảng viên, đơn đăng ký đã kết thúc (FINISHED - Phòng LB01) |
| **Kỹ thuật IT (NORMAL_USER - L3)** | `it.cuong@fpt.edu.vn` | `123456` | `IT-001` | Kỹ sư IT, chỉ định Phòng Server (đang hiệu lực không thời hạn) |
| **Kỹ thuật IT (NORMAL_USER - L3)** | `it.phuc@fpt.edu.vn` | `123456` | `IT-002` | Kỹ sư Mạng, chỉ định Phòng điện (sắp hết hạn trong vài giờ) |
| **Kỹ thuật IT (NORMAL_USER - L3)** | `it.tuan@fpt.edu.vn` | `123456` | `IT-003` | Admin IT hệ thống, người dùng cấp độ an ninh Level 3 |
| **Vô hiệu hóa (NORMAL_USER - Inactive)** | `user.disabled@fpt.edu.vn` | `123456` | `DIS-001` | Tài khoản vô hiệu hoá (`is_active = false`), kiểm tra từ chối login |

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
