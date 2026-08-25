# M06 Area Management — Known Issues & Limitations

## Issue 1: Ca I7 (Xác thực không token trả về 403 Forbidden thay vì 401 Unauthorized)
- **Mô tả**: Khi client gửi request không kèm JWT token tới các endpoint bảo mật (ví dụ: `GET /api/areas`), hệ thống trả về mã HTTP `403 Forbidden` thay vì `401 Unauthorized`.
- **Nguyên nhân**: File `SecurityConfig.java` thiếu cấu hình `.exceptionHandling().authenticationEntryPoint(...)` để định hướng xử lý các yêu cầu chưa được xác thực.
- **Thuộc trách nhiệm**: Module M01 (Auth & Security Configuration do đồng đội quản lý).
- **Ảnh hưởng**: `apiClient` ở phía Frontend không phân biệt được tự động giữa lỗi chưa đăng nhập / hết phiên (401) và lỗi thiếu quyền hạn (403).
- **Trạng thái**: Chờ M01 cập nhật `AuthenticationEntryPoint` trong `SecurityConfig.java`.

## Issue 2: Cấu hình Database dành cho Test tự động (thay thế Testcontainers)
- **Mô tả**: Testcontainers không thể chạy trên môi trường macOS Docker Desktop do xung đột API Version giữa `docker-java` client (1.32) và Docker Desktop Daemon (yêu cầu tối thiểu 1.44).
- **Giải pháp**: Sử dụng database riêng biệt `campus_security_test` trên container Postgres sẵn có của `docker-compose`. Cấu hình profile `test` tự động `clean` và `migrate` bằng Flyway trước khi thực thi test suite.
- **Điều kiện chạy test**:
  1. Đảm bảo Postgres container đang chạy: `docker compose up -d postgres`
  2. Tạo database test một lần duy nhất bằng lệnh:
     `docker exec sep_postgres psql -U sep -d postgres -c "CREATE DATABASE campus_security_test;"`
  3. Lệnh chạy test: `set -a && source .env && set +a && cd backend && ./mvnw test`
