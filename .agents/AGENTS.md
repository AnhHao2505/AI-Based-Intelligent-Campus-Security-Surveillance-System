# Quy tắc dịch kiểu dữ liệu cơ sở dữ liệu sang PostgreSQL

Khi chuyển đổi thiết kế database (như DBML / ERD.txt) sang các tệp migrations SQL cho PostgreSQL, cần chú ý dịch kiểu dữ liệu cho khớp với mapping mặc định của JPA/Hibernate để tránh lỗi Schema-validation:

## Quy tắc ánh xạ kiểu dữ liệu

1. **Chuỗi độ dài cố định (`CHAR(N)`)**:
   - **Vấn đề**: Trong DBML thường viết là `password char(60)`. Tuy nhiên, PostgreSQL sẽ lưu dưới dạng `bpchar` (Types#CHAR), trong khi Hibernate mapping mặc định cho `String` trong Java là `varchar(N)` (Types#VARCHAR).
   - **Giải pháp**: Thay vì dùng `CHAR(60)`, hãy khai báo cột là `VARCHAR(60)` trong PostgreSQL migration để khớp với Hibernate validation.

2. **Dữ liệu văn bản chung**:
   - Sử dụng `VARCHAR(N)` hoặc `TEXT` cho các cột chuỗi văn bản thay vì `CHAR(N)`.
