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
scripts/      (hook kiểm màu hiện KHÔNG có)
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
- **API có wrapper `ApiResponse<T>` generic.** Controller bọc response qua
  `ApiResponse<T>` (FE `apiClient` tự bóc data).
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
  **KHÔNG hardcode mã hex.** (Lưu ý: hook kiểm tra màu hiện KHÔNG có, không có lệnh chạy hook, cần tự giác tuân thủ token màu).
- Theme.css có đầy đủ biến cho cả light và dark mode. Sửa CSS nhớ kiểm tra
  cả hai.
- Prefix class theo trang: `arp-` (AccessRequestPage), `arr-` (ReviewPage),
  `notif-` (NotificationsPage)...

## 6. Database / migration

- **Flyway**, file ở `backend/src/main/resources/db/migration/`,
  đặt tên `V{n}__{mô_tả}.sql`. Hiện tại đã tới **V33**.
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
| Cấu hình hệ thống (System Configuration) | Xong BE + FE — V31, xem mục 9b |
| **MF3 — Yêu cầu ra vào khu vực** | Xong BE + FE, tham số configurable, đã vá lỗ hổng phân quyền (xem mục 9, 9a, 9b) |
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

## 9a. MF3 — phân quyền và tra cứu thành viên (V33)

- **`GET /api/users/{code}` chỉ dành cho ADMIN.** Trước đây endpoint này không có
  `@PreAuthorize` nên mọi tài khoản đăng nhập đều quét được toàn bộ danh bạ kèm
  `email` và `role`. Đừng mở lại cho role khác.
- MF3 tra cứu thành viên qua **`POST /api/access-requests/resolve-members`**
  (NORMAL_USER, FACILITY_MANAGER, ADMIN). Quy tắc bắt buộc giữ:
  - chỉ trả `userCode` + `fullName`, **không** trả `email`, `role`, `id`
  - mã không tồn tại / bị vô hiệu hoá / đã soft-delete đều trả **cùng một `reason`**
    — khác thông báo là suy ra được tài khoản nào tồn tại
  - một query `findAllByUserCodeIn`, không lặp gọi repository từng mã
  - dedupe nhưng giữ thứ tự người dùng nhập
  - giới hạn theo `ACCESS_REQUEST_MAX_GROUP_MEMBERS`
  - rate limit theo `SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE`, vượt ngưỡng trả **429**
- **`MemberInfo` không chứa `email`.** Nếu thêm lại, sinh viên tạo yêu cầu nhóm 30 mã
  rồi đọc email từ response là quét được danh bạ theo đường vòng.
  `requesterEmail` và `reviewerEmail` ở `AccessRequestResponse` thì giữ — đó là người
  tạo đơn và người duyệt, không phải danh bạ.

**Ranh giới ADMIN / FACILITY_MANAGER (đã chốt, không mở lại):**

| | ADMIN | FACILITY_MANAGER |
|---|---|---|
| Cấu hình hệ thống, đọc audit | Có | Không |
| Duyệt / từ chối yêu cầu | **Không** | Có |
| Đọc đơn của người khác | **Không** | Có |
| Tạo đơn cho chính mình | Có — nên `/available-areas` vẫn mở cho ADMIN | Có |

Biến `isStaff` trong `AccessRequestController` chỉ tính `ROLE_FACILITY_MANAGER`.
Đừng thêm lại `ROLE_ADMIN`.

**Nợ kỹ thuật đã biết:**
- `resolveMembers` chuẩn hoá mã bằng `StringNormalizer`, nhưng
  `resolveAndValidateGroupMembers` vẫn tra bằng mã thô → gõ `fm-001` thì tra cứu thấy
  mà gửi đơn báo 404. Sửa chung khi làm `AccessAuthorizationService`.
- Nhánh `afterCommit` của `SystemConfigService` chưa có test phủ (unit test Mockito
  không có transaction nên luôn đi nhánh `else`).
- Rate limit đếm trong bộ nhớ một instance, không dùng được khi chạy nhiều instance.

## 9b. Cấu hình hệ thống — tham số nghiệp vụ (V32, V33)

**Tuyệt đối không hardcode tham số nghiệp vụ trong Java.** Mọi giới hạn, ngưỡng,
khung thời gian phải nằm trong bảng `system_configurations`.

- Bảng `system_configurations` là **key-value có metadata**: `config_key` (PK,
  String), `config_value`, `data_type` (INTEGER/DECIMAL/BOOLEAN/STRING),
  `min_value`, `max_value`, `unit`, `config_group`, `display_order`,
  `description`, `editable`, `updated_at`, `updated_by`.
  Cố tình KHÔNG dùng bảng singleton cột cố định như `ai_configurations`.
- **Thêm tham số mới = 1 dòng `INSERT` trong migration mới + 1 hằng trong
  `enums/ConfigKey.java`.** Không sửa entity, không sửa DTO, không sửa frontend —
  `SystemConfigPage.jsx` render động theo metadata.
- Đọc giá trị qua `SystemConfigService.getInt(ConfigKey.X)` / `getBoolean(...)`.
  Thiếu key hoặc sai kiểu → log WARN + trả `defaultValue` khai báo trong enum.
  Không để hệ thống sập vì một dòng config.
- Cache là `volatile Map` nạp ở `@PostConstruct`. `update()` ghi cache **sau khi
  transaction commit** (`TransactionSynchronizationManager.afterCommit`), không ghi
  trong transaction — commit lỗi thì cache không được phép giữ giá trị chưa lưu.
  → **sửa xong có hiệu lực không cần restart**. Đây là tiêu chí nghiệm thu, đừng
  thay bằng cách đọc yml rồi restart.
- `getBoolean` gặp giá trị không phải true/false → log WARN + trả `defaultValue`.
  Không được để `Boolean.parseBoolean` nuốt lỗi trả `false`.
- Mọi lần sửa ghi vào `system_configuration_change_logs` (old → new, ai, lúc nào).
- **Thông báo lỗi phải nội suy giá trị config**, không viết cứng con số vào chuỗi.
- 8 tham số hiện có: `ACCESS_REQUEST_MAX_GROUP_MEMBERS` (30) ·
  `ACCESS_REQUEST_MAX_DURATION_HOURS` (12) ·
  `ACCESS_REQUEST_MAX_ADVANCE_DAYS` (30) ·
  `ACCESS_REQUEST_PAST_START_BUFFER_MINUTES` (5) ·
  `ACCESS_REQUEST_GROUP_ALLOWED_IN_PRIVATE` (false) ·
  `NOTIFICATION_PENDING_OVERDUE_HOURS` (24) ·
  `NOTIFICATION_EXPIRING_SOON_LEAD_MINUTES` (30) ·
  `SECURITY_MEMBER_LOOKUP_RATE_PER_MINUTE` (20, nhóm SECURITY).
- **Cron của `@Scheduled` KHÔNG nằm trong DB** — là tham số vận hành, đặt ở
  `application.yml` dưới `icss.scheduler.*`, override được bằng env var.
- API: `GET /api/system-configurations` (ADMIN, FACILITY_MANAGER) ·
  `PATCH /api/system-configurations/{key}` (ADMIN) ·
  `GET /api/system-configurations/{key}/history` (ADMIN) ·
  `POST /api/system-configurations/reload` (ADMIN, nạp lại cache khi ai đó sửa
  thẳng trong DB).
  Màn hình `/admin/system-configurations`, chỉ ADMIN.
- Config chỉ áp dụng **lúc tạo request mới**, KHÔNG hồi tố đơn đã tồn tại.

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
- `npm run lint` hiện còn **17 warning đã được phân loại có chủ đích**
  (biến chờ MF4, `exhaustive-deps` ở code WebRTC/WebSocket, và
  `only-export-components` ở Context). **Đừng "dọn" chúng** — sửa
  `exhaustive-deps` ở `WebRtcPlayer.jsx` / `GuardDashboardPage.jsx` /
  `CameraDetailPage.jsx` sẽ gây reconnect liên tục hoặc gọi API vô hạn.
- Đừng thêm `eslint-disable` / `oxlint-disable` để bịt cảnh báo.
- Đừng thêm thư viện mới mà chưa hỏi.
- Đừng tạo file ghi chú `.md` (implementation_plan, walkthrough...) trong
  repo — báo cáo thẳng cho người dùng. **Ngoại lệ duy nhất:**
  `docs/<MÃ MODULE>-*-status.md` là tài liệu trạng thái module do người dùng
  yêu cầu — KHÔNG được xoá, KHÔNG coi là file ghi chú tạm.
- Test dùng JUnit 5 + Mockito. Test hiện có **không được làm vỡ**.

---

## 12. Lệnh nguy hiểm — agent KHÔNG được tự chạy

Những lệnh dưới đây xoá dữ liệu không hồi phục được, hoặc phá trạng thái của cả
nhóm. Agent **phải liệt kê ra trước, hỏi người dùng, rồi mới chạy** — kể cả khi
chắc chắn là đúng.

### 12.1. Xoá file hàng loạt

**CẤM** mọi lệnh xoá dùng pattern rộng:

```bash
find <dir> -name "<pattern>" -delete          # CẤM
find <dir> -name "<pattern>" -exec rm {} \;   # CẤM
rm -rf <thư mục>/*                            # CẤM
git clean -fdx                                # CẤM — xoá cả file untracked của người dùng
```

Lý do: một pattern như `-name "* *"` khớp **mọi** file có dấu cách trong tên, không
chỉ file rác bạn định xoá. Repo này có `image/Forgot Password.png`,
`image/Reset Password.png`, và người dùng còn giữ ảnh chụp màn hình untracked.

**Cách đúng — liệt kê trước, xoá sau, từng file một:**

```bash
find backend/src -type f -name "* 2.java"     # bước 1: chỉ liệt kê
# bước 2: đọc danh sách, xác nhận từng file là rác
rm "backend/src/.../MemberLookupRateLimiter 2.java"   # bước 3: xoá đích danh
```

Nếu danh sách dài hơn 10 file, dừng lại và hỏi người dùng thay vì tự xoá.

### 12.2. Cơ sở dữ liệu

```bash
docker compose down -v              # CẤM — xoá luôn volume MinIO chứa ảnh khuôn mặt
DROP SCHEMA public CASCADE          # chỉ chạy khi người dùng yêu cầu rõ ràng
UPDATE flyway_schema_history ...    # CẤM sửa tay bảng lịch sử Flyway
flyway repair                       # CẤM tự chạy
DELETE FROM <bảng> ...              # phải hỏi trước
```

Gặp lỗi Flyway checksum mismatch hoặc "migration not resolved locally": **dừng lại,
báo nguyên văn lỗi**, không tự xử lý.

### 12.3. Git

```bash
git push --force <nhánh chung>      # CẤM. Chỉ --force-with-lease, và chỉ trên nhánh của mình
git push origin main                # CẤM push thẳng main
git reset --hard                    # phải hỏi trước, mất thay đổi chưa commit
git checkout . / git restore .      # phải hỏi trước
git branch -D                       # phải hỏi trước
```

Không bao giờ hoàn tác thay đổi trong `db/migration/` khi resolve conflict hoặc
"align với origin/main" — xem mục 6.

### 12.4. File của người dùng

Không add, không sửa, không xoá:

- `CLAUDE.md` (file cấu hình riêng, cố tình không đưa lên GitHub)
- `docs/<MÃ MODULE>-*-status.md`
- Ảnh untracked trong `image/`
- Bất kỳ file untracked nào agent không tự tạo ra

### 12.5. Nguyên tắc chung

Trước khi chạy một lệnh phá huỷ, tự hỏi: **"Nếu pattern này khớp nhiều hơn dự
kiến thì mất gì?"** Nếu câu trả lời không phải "không mất gì", hãy liệt kê ra
trước và hỏi.
