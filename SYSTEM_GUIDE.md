# 🛡️ FPTU Campus Security — Hướng Dẫn Vận Hành Hệ Thống (System Guide)
> **Mã Đề Tài:** FA26SE040  
> **Dự Án:** *AI-Based Intelligent Campus Security Surveillance System for Restricted Area Access Control in Tân Uyên FPTU Campus*  
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
| Vai trò (Role) | Email | Mật khẩu | Mã nhân sự (MSNV) | Quyền hạn |
| :--- | :--- | :---: | :---: | :--- |
| **Quản trị viên (ADMIN)** | `admin@fpt.edu.vn` | `123456` | `AD-001` | Toàn quyền hệ thống, quản lý tài khoản & dataset khuôn mặt |
| **Bảo vệ (INTERNAL_GUARD)** | `guard.an@fpt.edu.vn` | `123456` | `SEC-001` | Xem camera trực tiếp, tiếp nhận cảnh báo đột nhập/lảng vảng |
| **Quản lý CSVC (FACILITY_MANAGER)**| `manager.binh@fpt.edu.vn` | `123456` | `FM-001` | Quản lý thiết bị camera, xem báo cáo thống kê vi phạm |

### 2. Tài khoản Dịch vụ Hạ tầng:
| Dịch vụ | Tên đăng nhập (User) | Mật khẩu (Password) | Ghi chú |
| :--- | :--- | :--- | :--- |
| **PostgreSQL Database** | `sep` | `123456` | Tên CSDL: `campus_security`, Port `5432` |
| **MinIO S3 Storage** | `minioadmin` | `minioadmin123` | Tên Bucket chính: `security-evidence` |
| **pgAdmin 4 Web** | `admin@example.com` | `admin123` | Server host: `postgres`, DB: `campus_security` |

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
