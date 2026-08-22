# Hướng dẫn Khởi chạy Dự án trên Windows

Tài liệu này hướng dẫn chi tiết cách tải, cấu hình và chạy dự án **AI-Based Intelligent Campus Security Surveillance System** trên máy tính sử dụng hệ điều hành Windows.

---

## 1. Yêu cầu Phần mềm (Prerequisites)

Trước khi bắt đầu, đảm bảo máy Windows của bạn đã cài đặt:

1. **Docker Desktop for Windows**:
   - Tải từ: [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop)
   - *Lưu ý*: Bật tùy chọn **WSL 2 Backend** khi cài đặt.

2. **Git for Windows**:
   - Tải từ: [git-scm.com/download/win](https://git-scm.com/download/win)

3. *(Tùy chọn)* **Java 21 JDK**: Chỉ cần thiết nếu bạn muốn chạy code Backend trực tiếp trên máy thay vì qua Docker.

---

## 2. Cách 1: Chạy toàn bộ 5 Service bằng Docker (Khuyên dùng)

Đây là cách đơn giản nhất, chỉ cần 1 câu lệnh để chạy toàn bộ hệ thống (PostgreSQL + Flyway Migration, Kafka, MinIO, Backend Spring Boot, Frontend React).

### Bước 1: Clone / Pull code về máy
Mở **Command Prompt (CMD)**, **PowerShell** hoặc **Git Bash**:

```cmd
git clone https://github.com/AnhHao2505/AI-Based-Intelligent-Campus-Security-Surveillance-System.git
cd AI-Based-Intelligent-Campus-Security-Surveillance-System
git checkout feature/phase2-flyway-migrations
```

### Bước 2: Kiểm tra file `.env`
File `.env` đã được tích hợp sẵn trong repo với các giá trị mặc định. Nếu chưa có, copy từ file mẫu:

```cmd
copy .env.example .env
```

### Bước 3: Khởi chạy 5 container bằng Docker Compose

```cmd
docker compose up -d --build
```

### Bước 4: Kiểm tra trạng thái các service

```cmd
docker compose ps
```

**Kỳ vọng:** Cả 5 container (`sep_postgres`, `sep_kafka`, `sep_minio`, `sep_backend`, `sep_frontend`) đều báo trạng thái `Up` / `healthy`.

---

## 3. Cách 2: Chạy Backend Local trên Windows (Dành cho Dev Backend)

Nếu bạn muốn sửa code Backend Java và chạy trực tiếp trên máy Windows:

### Bước 1: Khởi động các service hạ tầng trước (Postgres, Kafka, MinIO)

```cmd
docker compose up -d postgres kafka minio
```

### Bước 2: Chạy Backend bằng Maven Wrapper trên Windows (`mvnw.cmd`)

Mở **Command Prompt (CMD)** hoặc **PowerShell**:

```cmd
cd backend
mvnw.cmd spring-boot:run
```

*(Script `mvnw.cmd` sẽ tự động tải Apache Maven về máy trong lần chạy đầu tiên, bạn không cần tự cài Maven).*

---

## 4. Các Địa chỉ & Cổng truy cập (URLs & Ports)

| Dịch vụ | Địa chỉ truy cập | Tài khoản / Ghi chú |
|---|---|---|
| **Backend Actuator Health** | `http://localhost:8080/actuator/health` | Trả về `{"status":"UP"}` |
| **Frontend Web Dashboard** | `http://localhost:5173` | Trang React Dashboard |
| **MinIO Console (Storage)** | `http://localhost:9001` | User: `minioadmin` / Pass: `minioadmin123` |
| **PostgreSQL Database** | `localhost:5432` | DB: `campus_security` / User: `sep` / Pass: `123456` |
| **Kafka Broker** | `localhost:9092` | Bootstrap server cho AI Engine |

---

## 5. Xử lý Lỗi Thường gặp trên Windows (Troubleshooting)

### Lỗi 1: `docker compose` báo Docker chưa chạy
- **Nguyên nhân**: Docker Desktop chưa mở hoặc WSL 2 chưa sẵn sàng.
- **Cách khắc phục**: Mở ứng dụng **Docker Desktop** từ Start Menu, đợi góc dưới bên trái hiện màu xanh lá cây (`Engine running`) rồi chạy lại lệnh.

### Lỗi 2: Trùng cổng `5432` hoặc `8080`
- **Nguyên nhân**: Máy tính đã cài sẵn PostgreSQL local hoặc Tomcat/IIS đang chiếm cổng.
- **Cách khắc phục**: Dừng dịch vụ Postgres local trên Windows (`Services.msc` ➔ tìm `postgresql-x64` ➔ bấm `Stop`).

### Lỗi 3: Ký tự xuống dòng CRLF / LF khi dùng Git
- **Nguyên nhân**: Windows dùng CRLF (`\r\n`), Linux dùng LF (`\n`).
- **Cách khắc phục**: Chạy lệnh cấu hình Git:
  ```cmd
  git config --global core.autocrlf true
  ```
