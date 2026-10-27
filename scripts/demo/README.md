# Dữ liệu demo ICSS

`seed-demo.sql` nạp bộ dữ liệu **bịa** để demo đủ luồng của hệ thống: khu vực 3 loại, đơn truy cập ở mọi trạng thái,
nhân sự chỉ định, lịch sự kiện, lượt khách, log ra vào 30 ngày, sự cố an ninh, thông báo, ca trực.

Đây **không** phải migration Flyway (không nằm trong `db/migration`). Chỉ chạy trên DB demo / DB thử, không chạy trên DB thật.

## Cách chạy

1. Khởi động backend **một lần** trỏ vào DB demo để Flyway tạo bảng (đợi log `Started SecurityApplication`), ví dụ:

   ```bash
   cd backend
   DB_HOST=localhost DB_PORT=55434 POSTGRES_DB=campus_security_demo \
   POSTGRES_USER=sep POSTGRES_PASSWORD=<mật khẩu DB demo> \
   JWT_SECRET=<chuỗi ngẫu nhiên ≥ 32 ký tự> INTERNAL_SERVICE_KEY=<chuỗi ngẫu nhiên ≥ 32 ký tự> \
   MINIO_ROOT_PASSWORD=<giá trị tạm> SERVER_PORT=8090 \
   ./mvnw spring-boot:run
   ```

2. Chạy seed (backend có thể đang chạy):

   ```bash
   psql -h localhost -p 55434 -U sep -d campus_security_demo -v ON_ERROR_STOP=1 -f scripts/demo/seed-demo.sql
   ```

   Script tự `BEGIN … COMMIT`: lỗi giữa chừng thì không ghi gì.

3. Chạy lại bao nhiêu lần cũng được: dữ liệu demo cũ bị xoá theo id cố định rồi chèn lại, số dòng không đổi.
   Mốc thời gian tính theo `now()` nên chạy lại trước buổi demo là dữ liệu "đang diễn ra" đúng như mô tả dưới đây.

## Tài khoản

Mật khẩu chung của tài khoản demo: **`Demo@123456`** (lưu BCrypt trong DB).

| E-mail | Vai | Cấp | Ghi chú |
|---|---|---|---|
| `admin@fpt.edu.vn` | ADMIN | 3 | Tài khoản **có sẵn từ migration V7**, mật khẩu của migration (`123456`), seed không đổi |
| `demo.fm1@icss.local` | FACILITY_MANAGER | 3 | Duyệt phần lớn đơn / lượt khách |
| `demo.fm2@icss.local` | FACILITY_MANAGER | 3 | |
| `demo.guard1@icss.local` | GUARD | 2 | Đội Demo; AP không thời hạn vào Phòng Server |
| `demo.guard2@icss.local` | GUARD | 2 | Đội Demo; có AP bị thu hồi |
| `demo.guard3@icss.local` | GUARD | 2 | Đội Demo |
| `demo.user1@icss.local` | NORMAL_USER | 1 | Có đơn PENDING / APPROVED / FINISHED, thành viên đơn nhóm |
| `demo.user2@icss.local` | NORMAL_USER | 1 | Có đơn đang diễn ra, đơn quá giờ chưa duyệt |
| `demo.user3@icss.local` | NORMAL_USER | 1 | Có đơn bị từ chối, đơn tự huỷ |
| `demo.user4@icss.local` | NORMAL_USER | 2 | Tạo đơn nhóm, host lượt khách, xin vào khu Tuyệt mật |
| `demo.user5@icss.local` | NORMAL_USER | 2 | Host lượt khách đang diễn ra; AP vào Phòng Server |
| `demo.user6@icss.local` | NORMAL_USER | 3 | Tạo đơn nhóm đang diễn ra, host lượt khách |
| `demo.user7@icss.local` | NORMAL_USER | 1 | Đơn bị FM huỷ, đơn hết hạn, AP bị thu hồi |

## Nội dung

| Nhóm | Dữ liệu |
|---|---|
| Toà / tầng | "Tòa Demo", Tầng 1–3 (toà riêng, không đụng Alpha / Beta) |
| Khu vực | 2 Công khai, 3 Bảo mật (Lab AI, Phòng họp Hội đồng, Kho thiết bị), 1 Tuyệt mật (Phòng Server), 1 Bảo mật đã vô hiệu hoá |
| Camera | `DEMO-CAM-01…06`, mỗi khu đang hoạt động một camera, `ACTIVE` / `OFFLINE` (không có luồng hình) |
| Đơn truy cập | PENDING tương lai · PENDING đã tới giờ chưa duyệt (job 15 phút sẽ chuyển EXPIRED) · APPROVED chưa bắt đầu · APPROVED đang diễn ra · REJECTED · CANCELLED do người tạo · CANCELLED do FM · EXPIRED · FINISHED do FM · FINISHED do hệ thống · đơn nhóm PENDING và APPROVED (có thành viên được bảo lãnh) · PENDING khu Tuyệt mật |
| Nhân sự chỉ định | UPCOMING · ACTIVE có hạn · ACTIVE không thời hạn · EXPIRED · REVOKED (2) |
| Lịch sự kiện | SCHEDULED · STARTED (Phòng họp Hội đồng đang mở chế độ sự kiện) · COMPLETED · ENDED_EARLY · CANCELLED · FAILED |
| Lượt khách | PENDING · APPROVED tương lai · APPROVED đang diễn ra · REJECTED · CANCELLED · REVOKED · EXPIRED · COMPLETED. Khách sắp tới / đang diễn ra **chưa có ảnh**; khách lượt đã xong đã xoá sinh trắc |
| Log ra vào | 360 lượt rải 29 ngày trước + 12 lượt hôm nay, đủ 5 kết quả |
| Sự cố | 14 sự cố: 3 NEW, 2 CLAIMED, 6 RESOLVED_VERIFIED, 3 RESOLVED_DISMISSED; Phòng Server nhiều nhất |
| Thông báo | 18 thông báo (đa số chưa đọc) cho FM, ADMIN, GUARD, NORMAL_USER |
| Ca trực | Đội Demo, 3 ca hôm nay + 3 ca ngày mai |

## Giới hạn

- Không có ảnh khuôn mặt, ảnh sự cố, video: môi trường demo không có MinIO / camera. Ảnh khách phải chụp lại qua giao diện khi có MinIO.
- Không chèn nhật ký thao tác (`audit_logs` append-only). Màn "Nhật ký thao tác" có dữ liệu khi thao tác trên giao diện.
- Job của backend chạy như thật trên dữ liệu demo: đơn PENDING quá giờ → EXPIRED, sự cố NEW lâu không ai nhận → leo thang tới FM.
- Thao tác trên giao diện trong lúc demo (tạo đơn, gán AP…) tạo dòng mới **không** bị seed xoá khi chạy lại.
