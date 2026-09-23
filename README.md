# 🛡️ FPTU Intelligent Campus Security Surveillance System (ICSS)

Hệ thống giám sát an ninh thông minh dựa trên AI cho các khu vực hạn chế truy cập tại khuôn viên FPT University Campus.

---

## 🚀 Hướng Dẫn Dựng Lại Cơ Sở Dữ Liệu Dev & Chạy Dữ Liệu Demo

### 1. Dựng Lại Database Dev (`campus_security`)

Khi cần làm sạch dữ liệu và khởi tạo lại database từ đầu:

1. **Dừng Backend** nếu đang chạy:
   ```bash
   # Kiểm tra và dừng tiến trình backend Spring Boot
   lsof -i :8080
   kill <PID>
   ```

2. **Sao lưu DB hiện tại (nếu cần)**:
   ```bash
   docker exec sep_postgres pg_dump -U sep -d campus_security > backups/campus_security-$(date +%Y%m%d_%H%M%S).sql
   ```

3. **DROP và CREATE lại database `campus_security`**:
   ```bash
   docker exec -i sep_postgres psql -U sep -d postgres -c "DROP DATABASE campus_security WITH (FORCE);"
   docker exec -i sep_postgres psql -U sep -d postgres -c "CREATE DATABASE campus_security OWNER sep;"
   ```

4. **Khởi động Backend để Flyway tự động chạy migrations (V1 → V43)**:
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```
   *Backend sẽ khởi chạy và Flyway tự động thực thi toàn bộ migration từ V1 đến V43.*

---

### 2. Chạy Script Dữ Liệu Demo (`scripts/demo-seed.sql`)

Script demo seed cung cấp dữ liệu mẫu phong phú và thực tế cho tất cả các luồng nghiệp vụ (người dùng các vai trò, khu vực với bản đồ toạ độ, ca trực bảo vệ, đơn đăng ký ra vào, sự cố an ninh và nhật ký audit log).

```bash
docker exec -i sep_postgres psql -U sep -d campus_security < scripts/demo-seed.sql
```

> [!NOTE]
> - **An toàn**: Script có chốt chặn an toàn `current_database() = 'campus_security'`, chỉ cho phép thực thi trên DB dev, ngăn chặn chạy nhầm lên production hoặc DB test.
> - **Idempotent**: Script có thể chạy nhiều lần mà không gây lỗi hoặc trùng lặp bản ghi.

---

## 👥 Danh Sách Tài Khoản Demo

> [!IMPORTANT]
> **Mật khẩu chung cho tài khoản demo: 123456 (chỉ DB dev)**

| STT | Họ và Tên | Email | Vai trò (Role) | Cấp độ (Level) | Mục đích Demo |
|:---:|:---|:---|:---:|:---:|:---|
| 1 | Quản trị viên | `admin@fpt.edu.vn` | **ADMIN** | Level 3 | Quản trị toàn hệ thống, quản lý tài khoản, cấu hình |
| 2 | Quản Lý CSVC Bình | `manager.binh@fpt.edu.vn` | **FACILITY_MANAGER** | Level 2 | Quản lý khu vực, duyệt đơn đăng ký, chỉ định nhân viên |
| 3 | Lê Thị Chi | `manager.chi@fpt.edu.vn` | **FACILITY_MANAGER** | Level 2 | Quản lý cơ sở thứ 2, kiểm tra nhật ký audit log |
| 4 | Bảo Vệ An | `guard.an@fpt.edu.vn` | **GUARD** | Level 2 | Trực ca sáng Cổng chính, nhận và xử lý sự cố an ninh |
| 5 | Bảo Vệ Demo | `guard.demo@fpt.edu.vn` | **GUARD** | Level 2 | Trực ca chiều Thư viện, giải tán đám đông, xử lý sự cố |
| 6 | Hoàng Văn Dũng | `guard.dung@fpt.edu.vn` | **GUARD** | Level 2 | Trực ca đêm Phòng Server, hộ tống khách ngoài ra cổng |
| 7 | Sinh Viên Tuấn | `student.tuan@fpt.edu.vn` | **NORMAL_USER** | Level 1 | Sinh viên, gửi đơn đăng ký truy cập phòng Lab AI (PENDING) |
| 8 | Nguyễn Thị Hoa | `student.hoa@fpt.edu.vn` | **NORMAL_USER** | Level 1 | Sinh viên, từng được chỉ định vào Lab IoT (đã hết hạn) |
| 9 | Trần Văn Nam | `student.nam@fpt.edu.vn` | **NORMAL_USER** | Level 1 | Sinh viên, trưởng nhóm đăng ký truy cập nhóm vào Lab IoT |
| 10 | Vũ Thùy Linh | `student.linh@fpt.edu.vn` | **NORMAL_USER** | Level 1 | Sinh viên, gửi đơn vào Phòng Server (bị từ chối - REJECTED) |
| 11 | TS. Lê Hùng | `lecturer.hung@fpt.edu.vn` | **NORMAL_USER** | Level 2 | Giảng viên, được chỉ định vào Lab AI (sắp hiệu lực) |
| 12 | ThS. Đỗ Tuyết Mai | `lecturer.mai@fpt.edu.vn` | **NORMAL_USER** | Level 2 | Giảng viên, có đơn duyệt đang trong khung giờ Phòng họp 1 |
| 13 | TS. Bùi Đăng Khoa | `lecturer.khoa@fpt.edu.vn` | **NORMAL_USER** | Level 2 | Giảng viên, đơn đăng ký đã kết thúc (FINISHED) |
| 14 | Đặng Quốc Cường | `it.cuong@fpt.edu.vn` | **NORMAL_USER** | Level 3 | Kỹ sư IT, chỉ định Phòng Server (đang hiệu lực không thời hạn) |
| 15 | Nguyễn Hoàng Phúc | `it.phuc@fpt.edu.vn` | **NORMAL_USER** | Level 3 | Kỹ sư Mạng, chỉ định Phòng điện (sắp hết hạn trong vài giờ) |
| 16 | Võ Anh Tuấn | `it.tuan@fpt.edu.vn` | **NORMAL_USER** | Level 3 | Admin IT hệ thống, người dùng cấp độ an ninh Level 3 |
| 17 | Tài Khoản Vô Hiệu Hóa | `user.disabled@fpt.edu.vn` | **NORMAL_USER** | Level 1 | Tài khoản vô hiệu hoá (`is_active = false`) dùng để test từ chối login |

---

## 🏢 Bản Đồ Khu Vực Demo (Toà FPT_AROUND - Tầng G, 1, 2)

Hệ thống sử dụng hệ toạ độ chuẩn hoá `[0.0, 1.0]` tương ứng với bản đồ mặt bằng các tầng:

- **Tầng G**:
  - `FPTA-G-GATE`: Cổng chính (PUBLIC, Level 1)
  - `FPTA-G-LOTUS`: Hồ Sen tầng G (PUBLIC, Level 1)
  - `FPTA-G-CAN`: Căng tin tầng G (PUBLIC, Level 1)
  - `FPTA-G-LIB`: Thư viện tầng G (INTERNAL_CONFIDENTIAL, Level 2)
  - `FPTA-G-LB01`: Phòng LB01 (HIGHLY_CONFIDENTIAL, Level 3, Explicit)
  - `FPTA-G-LB02`: Phòng LB02 (HIGHLY_CONFIDENTIAL, Level 3, Explicit)
  - `FPTA-G-MED`: Phòng Y tế (HIGHLY_CONFIDENTIAL, Level 3, Explicit)
- **Tầng 1**:
  - `FPTA-1-LEC`: Phòng giảng viên (INTERNAL_CONFIDENTIAL, Level 2)
  - `FPTA-1-MR1`: Phòng họp tầng 1 (INTERNAL_CONFIDENTIAL, Level 2)
  - `FPTA-1-AI`: Lab AI (CONFIDENTIAL_CONTACT_REQUIRED, Level 3)
  - `FPTA-1-IOT`: Lab IoT (CONFIDENTIAL_CONTACT_REQUIRED, Level 3, Explicit - **Khác mặc định**)
- **Tầng 2**:
  - `FPTA-2-SRV`: Phòng Server (HIGHLY_CONFIDENTIAL, Level 3, Explicit)
  - `FPTA-2-ELE`: Phòng điện (HIGHLY_CONFIDENTIAL, Level 3, Explicit)
  - `FPTA-2-EXM`: Phòng khảo thí (HIGHLY_CONFIDENTIAL, Level 3, Explicit)
