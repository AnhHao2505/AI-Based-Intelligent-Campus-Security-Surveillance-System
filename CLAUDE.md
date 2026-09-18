# CLAUDE.md — Hướng dẫn cho AI agent làm việc trên repo này

File này được mọi AI agent đọc trước khi đụng vào code. Giữ nó ngắn, đúng
và cập nhật. Nếu bạn (agent) phát hiện thông tin ở đây đã sai so với code,
hãy báo cho người dùng thay vì im lặng làm theo.

---

## 1. Dự án là gì

**ICSS — AI-Based Intelligent Campus Security Surveillance System.**
Hệ thống giám sát an ninh khuôn viên trường: quản lý khu vực, camera,
tài khoản, yêu cầu ra vào khu vực, và nhận diện khuôn mặt.

Package gốc backend: `com.fa26se040.icss`

---

## 2. Cấu trúc repo

```
backend/      Spring Boot 3.2.5 + Java 21 (API chính)
frontend/     React + Vite (web app)
ai-service/   Python — nhận diện khuôn mặt, pipeline AI
ai-engine/    (trống / dự phòng)
docs/         báo cáo khảo sát UI, design system
scripts/      check-colors.sh — pre-commit hook kiểm tra CSS token
SYSTEM_GUIDE.md   tài khoản demo, hướng dẫn chạy hệ thống
```

---

## 3. Lệnh hay dùng

**Cẩn thận: `mvnw` nằm trong `backend/`, KHÔNG nằm ở gốc repo.**

```bash
# Backend
cd backend && ./mvnw test           # chạy toàn bộ test
cd backend && ./mvnw test-compile   # chỉ kiểm tra biên dịch (nhanh)
cd backend && ./mvnw spring-boot:run

# Frontend
cd frontend && npm run dev
cd frontend && npm run lint         # oxlint
cd frontend && npm run typecheck    # tsc --noEmit
cd frontend && npm run build
```

Backend chạy ở `localhost:8080`. DB mặc định: PostgreSQL
`campus_security`, user `sep`, port 5432. Timezone DB đặt sẵn
`Asia/Ho_Chi_Minh`.

---

## 4. Convention backend (bắt buộc tuân theo)

- **Phân tầng:** `controller` → `service` → `repository` → `entity`.
  DTO trong `dto/<module>/`, enum trong `enums/`, exception trong `exception/`.
- **DTO luôn là Java `record`**, bất biến. Validate bằng
  `jakarta.validation.constraints.*`.
- **Mapping viết tay** — dự án KHÔNG dùng MapStruct hay ModelMapper.
  Map bằng constructor của record hoặc private helper trong service.
- **KHÔNG có response wrapper generic.** Controller trả thẳng
  `ResponseEntity.ok(data)` hoặc `Page<T>`. Đừng bọc thêm `ApiResponse<T>`.
- **Lỗi** gom ở `GlobalExceptionHandler`. Format cố định:
  `{ timestamp, status, error, message }`. Đừng đổi.
  Exception mới thì đặt `@ResponseStatus(HttpStatus.XXX)` ngay trên class
  (đây là convention sẵn có, xem `ResourceNotFoundException`,
  `ConcurrentReviewException`).
- **ID là UUID**, sinh bằng `@GeneratedValue(strategy = GenerationType.UUID)`.
  Riêng bảng `areas` và vài bảng cũ dùng `INTEGER SERIAL`.
- **Thời gian dùng `OffsetDateTime`**, set trong `@PrePersist` / `@PreUpdate`.
- **`open-in-view: false`** — mọi dữ liệu quan hệ phải fetch trong
  `@Transactional`, hoặc dùng `JOIN FETCH`. Không thì `LazyInitializationException`.
- **Route:** RESTful, số nhiều, kebab-case. Ví dụ `/api/access-requests/{id}/review`.
- **Phân quyền** bằng `@PreAuthorize` trên method controller.

## 5. Convention frontend

- Component dùng chung ở `src/components/ui/` — **tái sử dụng, đừng viết mới**.
- Gọi API qua `src/api/apiClient.js` (`apiGet/apiPost/apiPatch`). Nó tự gắn
  Bearer token, tự xử lý 401/403, và ném `Error` có `.status` + `.message`
  lấy từ body lỗi của backend.
- Service theo module: `src/services/<module>Service.js`, JSDoc tiếng Việt.
- **CSS phải dùng token trong `src/styles/theme.css`**
  (`--theme-primary-*`, `--theme-warning-*`, `--theme-danger-*`, ...).
  **KHÔNG hardcode mã hex.** Có pre-commit hook `scripts/check-colors.sh`
  chặn việc này — commit sẽ fail.
- Theme.css có đầy đủ biến cho cả light và dark mode. Sửa CSS nhớ kiểm tra
  cả hai.
- Prefix class theo trang: `arp-` (AccessRequestPage), `arr-` (ReviewPage),
  `notif-` (NotificationsPage)...

## 6. Database / migration

- **Flyway**, file ở `backend/src/main/resources/db/migration/`,
  đặt tên `V{n}__{mô_tả}.sql`. Hiện tại đã tới **V29**.
- **TUYỆT ĐỐI không sửa migration cũ.** Cần đổi gì thì thêm file V mới.
- Seed data viết `INSERT` thẳng trong migration.
- `area_level` lưu dạng SMALLINT tham chiếu bảng `area_levels`:
  **1 = PUBLIC, 2 = SEMI_PRIVATE, 3 = PRIVATE**.

## 7. Role & tài khoản

4 role trong `enums/Role.java`: `ADMIN`, `FACILITY_MANAGER`, `GUARD`,
`NORMAL_USER`. (`GUARD` là kết quả merge `INTERNAL_GUARD` +
`OUTSOURCED_GUARD` ở V27.)

Auth: JWT stateless. Lấy user hiện tại qua tham số `Authentication` →
`authentication.getName()` trả về **email** → tra `userRepository.findByEmail`.

Tài khoản seed (mật khẩu đều là `123456`):

| Email | Role | Mã |
|---|---|---|
| `admin@fpt.edu.vn` | ADMIN | AD-001 |
| `manager.binh@fpt.edu.vn` | FACILITY_MANAGER | FM-001 |
| `guard.an@fpt.edu.vn` | GUARD | SEC-001 |
| `student.tuan@fpt.edu.vn` | NORMAL_USER | SV-001 |

Lưu ý: seed chỉ có **1 FM** và **1 normal user** — muốn test luồng nhiều
FM hoặc group request thì phải tạo thêm tài khoản.

---

## 8. Trạng thái các module

| Module | Tình trạng |
|---|---|
| Auth, quản lý tài khoản (import Excel theo batch) | Xong BE + FE |
| Quản lý khu vực (CRUD, geometry trên floor plan) | Xong BE + FE |
| Quản lý camera (CRUD, stream MediaMTX, health log) | Xong BE + FE |
| Cấu hình AI | Xong BE + FE |
| **MF3 — Yêu cầu ra vào khu vực** | Xong BE + FE (xem mục 9) |
| Notification in-app | Xong BE + FE |
| MF4 — Nhận diện khuôn mặt, lịch sử ra vào | Mới có `FaceData` + Kafka, **chưa có API**. FE có `AccessHistoryPage` đang để TODO chờ backend |

Hạ tầng đã dựng nhưng chưa dùng hết: MinIO (lưu file), MediaMTX (stream),
Kafka consumer (incident), STOMP WebSocket `/ws-security`.

---

## 9. MF3 — các quyết định đã chốt (đừng tự đổi)

Đây là phần dễ bị agent sau "sửa cho hợp lý" rồi phá vỡ nghiệp vụ.

**Quy tắc nghiệp vụ:**
- Request có 2 loại: `INDIVIDUAL` và `GROUP`.
- Chỉ khu vực `SEMI_PRIVATE` và `PRIVATE` mới cần xin request.
  `PUBLIC` thì tự do ra vào.
- **Group request bị cấm ở khu vực `PRIVATE`** (chỉ cho cá nhân).
- Giới hạn: tối đa **30 thành viên**, thời lượng tối đa **12 giờ**,
  đặt trước tối đa **30 ngày**, `startTime` không được ở quá khứ
  (buffer 5 phút).
- Người tạo tự động bị loại khỏi `memberUserCodes` nếu nhập nhầm
  (dedupe im lặng, không báo lỗi).
- **Chặn trùng lịch:** cùng user + cùng khu vực + khung giờ giao nhau +
  status `PENDING`/`APPROVED` → **409**. Không chặn nếu khác khu vực.
  Với group, message lỗi phải nêu mã số + họ tên người bị trùng.
- FM duyệt/từ chối **theo cả nhóm**, không duyệt lẻ từng thành viên.
  Bảng `access_request_members` cố tình KHÔNG có cột status.
- Từ chối bắt buộc có `rejectionReason` (validate cross-field ở DTO).
- 5 trạng thái: `PENDING`, `APPROVED`, `REJECTED`, `CANCELLED`, `EXPIRED`.
  User tự huỷ được khi còn `PENDING`. Scheduled task mỗi 15 phút chuyển
  `PENDING` quá giờ thành `EXPIRED`.

**Chống race condition — QUAN TRỌNG:**

`reviewRequest` và `cancelRequest` dùng **atomic conditional UPDATE**:

```sql
UPDATE ... SET status = :new WHERE id = :id AND status = 'PENDING'
```

Dựa vào số dòng bị ảnh hưởng:
- **1 dòng** → thành công, đây là người duy nhất giành được quyền xử lý
- **0 dòng** → người khác đã xử lý trước → ném `ConcurrentReviewException` (409)
  với message nêu rõ ai xử lý, thành trạng thái gì, lúc nào

**Không dùng `@Version`, không dùng `SELECT FOR UPDATE`.** Đã cân nhắc và
loại bỏ: conditional UPDATE không có khe hở giữa kiểm tra và ghi, không cần
migration, không giữ khoá.

**Đã cố tình XOÁ pre-check `if (status != PENDING) throw ...`.** Kết quả của
conditional UPDATE là nguồn chân lý duy nhất. Đừng thêm lại — thêm vào sẽ
khiến trường hợp phổ biến trả 400 thay vì 409, và frontend sẽ không hiển thị
đúng banner cảnh báo.

**Notification chỉ được bắn ở nhánh update trả về 1 dòng.** Có comment
`[RÀNG BUỘC NOTIFICATION / SIDE-EFFECTS]` đánh dấu sẵn trong
`AccessRequestService.java`. Bắn ở nhánh 409 sẽ khiến người dùng nhận
thông báo trùng hoặc mâu thuẫn (vừa "đã duyệt" vừa "bị từ chối").

---

## 10. Notification in-app

- Bảng `notifications` (V29). **8 loại** trong `enums/NotificationType.java`:
  `REQUEST_APPROVED`, `REQUEST_REJECTED`, `EXPIRING_SOON`, `ACCESS_DENIED`,
  `ADDED_TO_GROUP`, `NEW_REQUEST_PENDING`, `REQUEST_CANCELLED`,
  `PENDING_OVERDUE`.
  Thêm loại mới thì phải sửa **cả** enum, CHECK constraint (migration mới),
  và nhánh `renderIcon` trong `NotificationsPage.jsx`.
- **`InAppNotificationService` khác hoàn toàn `NotificationService`.**
  `NotificationService` là gửi **email** (reset password, cấp tài khoản).
  Đừng nhầm hai file này.
- `createForUser`/`createForUsers` chạy trong
  `@Transactional(propagation = REQUIRES_NEW)`. Lý do: nếu chạy chung
  transaction, lỗi ghi notification sẽ đánh dấu session `rollback-only` và
  làm **rollback luôn nghiệp vụ duyệt đơn**, kể cả khi caller đã `try/catch`.
  Nghiệp vụ chính phải luôn commit được dù module thông báo có hỏng.
- Chống gửi trùng bằng `existsByReferenceIdAndType` trước khi tạo.
- Chỉ in-app. **Không** email, **không** WebSocket real-time, **không** push.
  Frontend poll `getUnreadCount()` mỗi 60 giây cho badge chuông.

---

## 11. Bẫy hay gặp

- `./mvnw` ở `backend/`, không phải gốc repo.
- `npm run lint` hiện còn **11 warning đã được phân loại có chủ đích**
  (biến chờ MF4, `exhaustive-deps` ở code WebRTC/WebSocket, và
  `only-export-components` ở Context). **Đừng "dọn" chúng** — sửa
  `exhaustive-deps` ở `WebRtcPlayer.jsx` / `GuardDashboardPage.jsx` /
  `CameraDetailPage.jsx` sẽ gây reconnect liên tục hoặc gọi API vô hạn.
- Đừng thêm `eslint-disable` / `oxlint-disable` để bịt cảnh báo.
- Đừng thêm thư viện mới mà chưa hỏi.
- Đừng tạo file ghi chú `.md` (implementation_plan, walkthrough...) trong
  repo — báo cáo thẳng cho người dùng.
- Test dùng JUnit 5 + Mockito. Test hiện có **không được làm vỡ**.
