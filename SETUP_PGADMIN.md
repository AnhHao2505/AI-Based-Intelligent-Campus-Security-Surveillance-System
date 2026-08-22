Tài liệu này hướng dẫn set_up pgadmin, 1 tool UI tương tác với database PostgresSQL
Có dùng DB Migration để tự động chạy các câu lệnh SQL nằm trong mục "C:\Projects\AI-Based-Intelligent-Campus-Security-Surveillance-System\backend\src\main\resources\db\migration"

1. Kích hoạt Docker container "sep_pgadmin" + Docker container "sep_postgres" | trên Desktop Docker
2. truy cập URL port của pgadmin
3. đăng nhập tài khoản (admin@example.com) + mk (admin123)
4. Add New Server (mới vào, mà có server, thì đến bước 8)
5. Cấu hình server như sau:
6. Tab General, Name = tùy ý
7. Tab Connection, Host name = sep_postgres, Username = sep, Password = 123456 --> Ấn Save Password
8. Vào mục schema của database "campus_security" (được tạo sẵn theo config), nơi chứa các table
   8.1. Nếu không có table user hay flyway_migration, thì hãy chạy backend vì chạy backend sẽ tự động kích hoạt các câu lệnh SQL trong file db migration
