# Quy tắc dịch kiểu dữ liệu cơ sở dữ liệu sang PostgreSQL

Khi chuyển đổi thiết kế database (như DBML / ERD.txt) sang các tệp migrations SQL cho PostgreSQL, cần chú ý dịch kiểu dữ liệu cho khớp với mapping mặc định của JPA/Hibernate để tránh lỗi Schema-validation:

## Quy tắc ánh xạ kiểu dữ liệu

1. **Chuỗi độ dài cố định (`CHAR(N)`)**:
   - **Vấn đề**: Trong DBML thường viết là `password char(60)`. Tuy nhiên, PostgreSQL sẽ lưu dưới dạng `bpchar` (Types#CHAR), trong khi Hibernate mapping mặc định cho `String` trong Java là `varchar(N)` (Types#VARCHAR).
   - **Giải pháp**: Thay vì dùng `CHAR(60)`, hãy khai báo cột là `VARCHAR(60)` trong PostgreSQL migration để khớp với Hibernate validation.

2. **Dữ liệu văn bản chung**:
   - Sử dụng `VARCHAR(N)` hoặc `TEXT` cho các cột chuỗi văn bản thay vì `CHAR(N)`.

3. **Khóa chính (Primary Key)**:
   - **Quy tắc**: Khóa chính phải thuộc kiểu tự động tăng (primary key should be of auto-increment type). Sử dụng các kiểu như `SERIAL` hoặc `BIGSERIAL` cho khóa chính thay vì `UUID`.

## Quy tắc thiết kế và tài liệu
- **File ERD.txt**: Nội dung trong file `ERD.txt` phải phản ánh chính xác các entity hiện có trong hệ thống. Đây là File nhằm chuyển đổi thành migration tương ứng.
- Trước khi thực hiện chỉnh sửa hoặc tạo bất kỳ migration nào, bạn phải cập nhật file `ERD.txt` để đảm bảo nó phản ánh đúng cấu trúc hiện tại của database.

