# M06 Area Management — Known Issues & Limitations

## Issue 1: Ca I7 (Xác thực không token trả về 403 Forbidden thay vì 401 Unauthorized)
- **Mô tả**: Khi client gửi request không kèm JWT token tới các endpoint bảo mật (ví dụ: `GET /api/areas`), hệ thống trả về mã HTTP `403 Forbidden` thay vì `401 Unauthorized`.
- **Nguyên nhân**: File `SecurityConfig.java` thiếu cấu hình `.exceptionHandling().authenticationEntryPoint(...)` để định hướng xử lý các yêu cầu chưa được xác thực.
- **Thuộc trách nhiệm**: Module M01 (Auth & Security Configuration do đồng đội quản lý).
- **Ảnh hưởng**: `apiClient` ở phía Frontend không phân biệt được tự động giữa lỗi chưa đăng nhập / hết phiên (401) và lỗi thiếu quyền hạn (403).
- **Trạng thái**: Chờ M01 cập nhật `AuthenticationEntryPoint` trong `SecurityConfig.java`.
