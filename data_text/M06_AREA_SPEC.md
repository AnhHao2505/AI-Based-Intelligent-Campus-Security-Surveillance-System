# M06 — Quản lý khu vực · Implementation Spec

**Dự án:** ICSS (FA26SE040) · **Module:** M06 — Area Management
**Version:** 2.0 — viết lại trên nền `origin/main`. Thay thế hoàn toàn v1.x.

## Nền tảng (đã khảo sát, không phải giả định)

| Hạng mục | Thực tế trên `origin/main` |
|---|---|
| Bảng đang có | `users`, `password_reset_tokens` — **chỉ 2 bảng** |
| `users.id` | `SERIAL` (Integer tự tăng) |
| `users.role` | `VARCHAR(50)`, 4 giá trị: ADMIN, FACILITY_MANAGER, INTERNAL_GUARD, OUTSOURCED_GUARD |
| Migration cao nhất | `V4` → **M06 dùng V5, V6** |
| Package gốc | `com.fa26se040.security` (nhóm dự kiến đổi sang `com.fa26se040.icss`) |
| Kiến trúc | Phân tầng: `config/ controller/ dto/ entity/ exception/ repository/ security/ service/` |
| `GlobalExceptionHandler` | Có. Định dạng `{timestamp, status, error, message}` |
| Principal trong SecurityContext | **`email` (String)**, không phải id |
| Authority prefix | Có `ROLE_` |
| Frontend | Chưa có `apiClient`. `localStorage` dùng `accessToken` / `user` |

> **Nguồn sự thật duy nhất cho M06.** Code mâu thuẫn tài liệu → tài liệu đúng.
> Thấy chỗ nào không khả thi → **DỪNG VÀ HỎI**, không tự suy diễn.

## Phạm vi

**Phase 1 (spec này) — danh sách phẳng:** CRUD khu vực · bảng tra cứu `area_levels` chỉ đọc · kiểm tra ràng buộc trước khi ngừng · ghi nhật ký thay đổi.

**Phase 2 (làm sau, KHÔNG làm bây giờ):** phân cấp cha–con. Thêm bằng một migration `ALTER TABLE areas ADD COLUMN parent_area_id`, không phá gì đã có.

**Ngoài phạm vi:** camera (M07) · quyền truy cập (M08) · bản đồ campus trực quan · CRUD cho `area_levels` · khôi phục khu vực đã ngừng.

---

## 1. Quyết định thiết kế

| # | Quyết định | Ràng buộc |
|---|---|---|
| D1 | Không xoá cứng. Ngừng = `is_active = false` + `deleted_at = now()` | Cấm `deleteById`, `DELETE FROM` |
| D2 | `code` không sửa được sau khi tạo | Request PUT có trường `code` nullable, chỉ để kiểm tra |
| D3 | Unique `code` là **partial index** `WHERE deleted_at IS NULL` | Cấm `@Column(unique = true)` |
| D4 | Cấp độ khu vực là **bảng tra cứu**, khoá chính `SMALLINT` 1/2/3 | Không hardcode enum trong Java |
| D5 | Hạ cấp độ bắt buộc nhập `reason`, ghi vào nhật ký | |
| D6 | Chỉ **ADMIN** tạo/sửa/ngừng. FACILITY_MANAGER chỉ đọc | |
| D7 | `404` chỉ cho tài nguyên trên đường dẫn. Lỗi body → `400`. Xung đột → `409` | |
| D8 | Không dùng phân cấp cha–con ở Phase 1 | Dùng `building` + `floor` để nhóm |

---

## 2. Cơ sở dữ liệu

### 2.0 Kiểm tra trước khi viết migration

```bash
ls -la backend/src/main/resources/db/migration/
grep -rn "EnableMethodSecurity" backend/src/main/java/
grep -rn "findByEmail" backend/src/main/java/
```

Trả lời 3 câu, **báo cáo rồi mới viết file**:

| Câu hỏi | Nếu KHÔNG có |
|---|---|
| Số version cao nhất hiện tại? | Dùng số tiếp theo, báo lại |
| `SecurityConfig` có `@EnableMethodSecurity` không? | ⚠️ **Phải thêm.** Thiếu nó thì mọi `@PreAuthorize` bị bỏ qua âm thầm — không lỗi, không cảnh báo, ai cũng gọi được mọi endpoint |
| `UserRepository` có `findByEmail` không? | Thêm method, cần để tra `userId` từ principal |

### 2.1 `V5__area_levels.sql`

```sql
CREATE TABLE area_levels (
    level                     SMALLINT PRIMARY KEY,
    code                      VARCHAR(30)  NOT NULL UNIQUE,
    name                      VARCHAR(100) NOT NULL,
    requires_face_recognition BOOLEAN      NOT NULL DEFAULT TRUE,
    description               TEXT,
    is_active                 BOOLEAN      NOT NULL DEFAULT TRUE
);

INSERT INTO area_levels (level, code, name, requires_face_recognition, description) VALUES
 (1, 'PUBLIC',       'Công cộng',         FALSE,
    'Thư viện, lớp học, sảnh — chỉ phát hiện người, KHÔNG nhận diện khuôn mặt'),
 (2, 'SEMI_PRIVATE', 'Bán hạn chế',       TRUE,
    'Phòng lab, kho thiết bị'),
 (3, 'PRIVATE',      'Hạn chế tuyệt đối', TRUE,
    'Phòng server, phòng ban giám hiệu');
```

**`requires_face_recognition` là lý do chính bảng này tồn tại.** Nó cho phép tắt nhận diện khuôn mặt ở khu Public — vừa xử lý vấn đề quyền riêng tư (không profile sinh viên trong thư viện), vừa tiết kiệm GPU. M09 sẽ đọc cờ này.

Khoá chính là `SMALLINT` 1/2/3 để sau này `severity_rules` tra cứu bằng số, so sánh được `>=`.

### 2.2 `V6__areas.sql`

```sql
CREATE TABLE areas (
    id          SERIAL PRIMARY KEY,
    code        VARCHAR(50)  NOT NULL,
    name        VARCHAR(150) NOT NULL,
    area_level  SMALLINT     NOT NULL REFERENCES area_levels(level),
    building    VARCHAR(50),
    floor       VARCHAR(20),
    description TEXT,
    map_x       NUMERIC(7,2),
    map_y       NUMERIC(7,2),
    is_active   BOOLEAN      NOT NULL DEFAULT TRUE,
    created_by  INTEGER      REFERENCES users(id),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  INTEGER      REFERENCES users(id),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT ck_areas_map CHECK ((map_x IS NULL) = (map_y IS NULL))
);

CREATE UNIQUE INDEX ux_areas_code  ON areas(code) WHERE deleted_at IS NULL;
CREATE INDEX        ix_areas_level ON areas(area_level);

-- Nhật ký thay đổi khu vực. Chưa có bảng audit_logs chung nên dùng bảng riêng,
-- sau này gộp vào audit chung khi M14 làm.
CREATE TABLE area_change_logs (
    id          SERIAL PRIMARY KEY,
    area_id     INTEGER     NOT NULL REFERENCES areas(id),
    actor_id    INTEGER     REFERENCES users(id),
    action      VARCHAR(20) NOT NULL
                CHECK (action IN ('CREATE','UPDATE','DEACTIVATE')),
    old_value   JSONB,
    new_value   JSONB,
    reason      VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX ix_acl_area ON area_change_logs(area_id, created_at DESC);
```

**`ux_areas_code` bắt buộc có `WHERE deleted_at IS NULL`.** Thiếu mệnh đề này thì sau khi ngừng một khu vực sẽ không bao giờ tạo lại được khu vực cùng mã — đây là ca test bắt buộc I3.

**Không có trigger `updated_at`** trên nền này. Java tự set bằng `@PreUpdate`.

---

## 3. Cấu trúc code

Repo tổ chức theo tầng. **Bám theo, không tạo package `area/` riêng** — nhất quán quan trọng hơn, và bạn A đang làm cùng codebase.

```
com.fa26se040.security/          (hoặc .icss sau khi nhóm đổi tên)
├─ entity/       Area.java · AreaLevel.java · AreaChangeLog.java
├─ repository/   AreaRepository.java · AreaLevelRepository.java · AreaChangeLogRepository.java
├─ dto/area/     AreaCreateRequest · AreaUpdateRequest · AreaResponse
│                AreaListItemResponse · AreaLevelResponse · AreaDependencyResponse
├─ service/      AreaService.java · AreaValidator.java
├─ controller/   AreaController.java · AreaLevelController.java
└─ exception/    AreaErrorCode.java   (bổ sung vào GlobalExceptionHandler đã có)
```

`AreaValidator` tách riêng khỏi `AreaService` để unit test chạy được không cần database.

---

## 4. API

Base `/api/areas`. Mọi endpoint yêu cầu JWT hợp lệ.

| # | Method | Path | Quyền |
|---|---|---|---|
| A1 | GET | `/api/areas` | ADMIN, FACILITY_MANAGER |
| A2 | GET | `/api/areas/{id}` | ADMIN, FACILITY_MANAGER |
| A3 | GET | `/api/areas/{id}/dependencies` | **ADMIN** |
| A4 | POST | `/api/areas` | **ADMIN** |
| A5 | PUT | `/api/areas/{id}` | **ADMIN** |
| A6 | DELETE | `/api/areas/{id}` | **ADMIN** |
| A7 | GET | `/api/area-levels` | isAuthenticated |

### A1 — GET /api/areas

Query: `keyword` (khớp `code` hoặc `name`, bỏ qua hoa thường) · `areaLevel` · `building` · `isActive` (mặc định `true`) · `page`=0 · `size`=20 (max 100) · `sort`=`code,asc`

```json
{
  "content": [{
    "id": 3,
    "code": "SERVER-B01",
    "name": "Phòng server toà B tầng 1",
    "level": { "level": 3, "code": "PRIVATE", "name": "Hạn chế tuyệt đối",
               "requiresFaceRecognition": true },
    "building": "B",
    "floor": "1",
    "isActive": true
  }],
  "page": 0, "size": 20, "totalElements": 12, "totalPages": 1
}
```

### A2 — GET /api/areas/{id}

Như A1 kèm thêm `description`, `mapX`, `mapY`, `createdBy`, `createdAt`, `updatedBy`, `updatedAt`.

### A3 — GET /api/areas/{id}/dependencies

```json
{
  "areaId": 3,
  "areaCode": "SERVER-B01",
  "canDeactivate": true,
  "blockers": [],
  "warnings": [],
  "note": "Chưa có module nào tham chiếu tới khu vực. Kiểm tra sẽ được bổ sung ở M07, M08."
}
```

> **Phase 1 chưa có ràng buộc nào để kiểm** — `cameras`, `access_permissions`, `guard_shifts`, `security_incidents` chưa tồn tại. Viết `AreaDependencyChecker` với **mỗi loại là một method private riêng, tạm trả `0`, kèm comment `// TODO M07` / `// TODO M08`**. Khi module đó xong chỉ cần điền một method, không đụng chỗ khác.

### A4 — POST /api/areas

```json
{ "code": "SERVER-B01", "name": "Phòng server toà B tầng 1", "areaLevel": 3,
  "building": "B", "floor": "1", "description": "Chứa tủ rack",
  "mapX": 320.5, "mapY": 210.0 }
```
→ `201 Created`, body = A2, header `Location`.

### A5 — PUT /api/areas/{id}

Như A4, thêm `reason`. Trường `code` nullable, chỉ để kiểm tra:

| Client gửi `code` | Xử lý |
|---|---|
| Không gửi / `null` | Bỏ qua |
| Trùng hiện tại (sau `trim`, bỏ qua hoa thường) | Bỏ qua |
| Khác hiện tại | `400 ERR_AREA_007` |

`reason` bắt buộc khi `newAreaLevel < currentAreaLevel` (hạ cấp), tối thiểu 10 ký tự. → `200 OK`.

### A6 — DELETE /api/areas/{id}

Không body. → `204 No Content`.

### Định dạng lỗi

**Theo đúng `GlobalExceptionHandler` đã có của bạn A**, chỉ thêm trường `code`:

```json
{
  "timestamp": "2026-08-25T21:59:00.123456",
  "status": 409,
  "error": "Conflict",
  "code": "ERR_AREA_001",
  "message": "Mã khu vực đã tồn tại"
}
```

Bổ sung một `@ExceptionHandler` cho `AreaException` vào handler sẵn có. **KHÔNG tạo handler mới song song, KHÔNG đổi định dạng hiện tại.**

---

## 5. Luật kiểm tra

| Trường | Luật | Mã lỗi |
|---|---|---|
| `code` | Bắt buộc · `trim().toUpperCase()` trước khi kiểm · `^[A-Z0-9][A-Z0-9-]{1,48}[A-Z0-9]$` · duy nhất trong `deleted_at IS NULL` | `ERR_AREA_004`, `ERR_AREA_001` |
| `code` *(trong PUT)* | Nullable, chỉ kiểm tra, không bao giờ ghi vào DB | `ERR_AREA_007` |
| `name` | Bắt buộc · trim · 1–150 | `ERR_AREA_005` |
| `areaLevel` | Bắt buộc · tồn tại trong `area_levels` · `is_active = true` | `ERR_AREA_003` |
| `mapX`/`mapY` | Có cả hai hoặc không cái nào · `0 ≤ x ≤ 99999.99` | `ERR_AREA_006` |
| `reason` | Bắt buộc khi hạ cấp · 10–255 ký tự | `ERR_AREA_008` |

### Bảng mã lỗi

| Mã | HTTP | Thông báo |
|---|---|---|
| `ERR_AREA_001` | 409 | Mã khu vực đã tồn tại |
| `ERR_AREA_002` | 404 | Không tìm thấy khu vực |
| `ERR_AREA_003` | 400 | Cấp độ khu vực không hợp lệ hoặc đã ngừng sử dụng |
| `ERR_AREA_004` | 400 | Mã khu vực chỉ gồm chữ in hoa, số và dấu gạch ngang, dài 3–50 ký tự |
| `ERR_AREA_005` | 400 | Tên khu vực bắt buộc, tối đa 150 ký tự |
| `ERR_AREA_006` | 400 | Toạ độ bản đồ phải có đủ cả X và Y |
| `ERR_AREA_007` | 400 | Không được thay đổi mã khu vực sau khi tạo |
| `ERR_AREA_008` | 400 | Hạ cấp độ khu vực bắt buộc nhập lý do |
| `ERR_AREA_009` | 409 | Không thể ngừng: còn {n} camera đang gán *(dành sẵn cho M07)* |
| `ERR_AREA_010` | 409 | Không thể ngừng: còn {n} quyền truy cập *(dành sẵn cho M08)* |

Khai báo `enum AreaErrorCode { code, httpStatus, messageTemplate }`. Cấm ném exception với chuỗi tự chế.

---

## 6. Logic nghiệp vụ

### Lấy `actorId` từ principal

Principal hiện là **email (String)**, không phải id. `AreaService` tra một lần:

```java
Integer actorId = userRepository.findByEmail(currentEmail)
        .map(User::getId)
        .orElseThrow(() -> new UnauthorizedException("Phiên đăng nhập không hợp lệ"));
```

Thừa một truy vấn mỗi lần ghi, nhưng **không đụng vào code của bạn A** — đánh đổi đúng ở giai đoạn này.

### `create(req, actorEmail)`

```
1.  code = req.code.trim().toUpperCase()
2.  validate định dạng code                          → ERR_AREA_004
3.  validate name                                    → ERR_AREA_005
4.  if repo.existsByCodeAndDeletedAtIsNull(code)     → ERR_AREA_001
5.  level = levelRepo.findActiveByLevel(req.areaLevel) → ERR_AREA_003
6.  validate mapX/mapY                               → ERR_AREA_006
7.  actorId = resolveActorId(actorEmail)
8.  area = new Area(...); area.createdBy = actorId
9.  repo.save(area)
10. changeLog.log(area.id, actorId, CREATE, null, snapshot(area), null)
11. return AreaResponse
```

### `update(id, req, actorEmail)`

```
1.  area = repo.findByIdAndDeletedAtIsNull(id)       → ERR_AREA_002
2.  oldSnapshot = snapshot(area)
3.  if req.code != null && !req.code.trim().equalsIgnoreCase(area.code)
                                                     → ERR_AREA_007
4.  validate name                                    → ERR_AREA_005
5.  newLevel = levelRepo.findActiveByLevel(...)      → ERR_AREA_003
6.  downgraded = req.areaLevel < area.areaLevel
    if downgraded && (isBlank(req.reason) || req.reason.trim().length() < 10)
                                                     → ERR_AREA_008
7.  validate mapX/mapY                               → ERR_AREA_006
8.  cập nhật trường; area.updatedBy = actorId; area.updatedAt = now()
9.  repo.save(area)
10. changeLog.log(id, actorId, UPDATE, oldSnapshot, snapshot(area), req.reason)
11. return AreaResponse
```

### `deactivate(id, actorEmail)`

```
1.  area = repo.findByIdAndDeletedAtIsNull(id)       → ERR_AREA_002
2.  dep = dependencyChecker.check(id)
3.  if !dep.blockers.isEmpty()  → throw theo blockers[0].errorCode
4.  area.isActive = false; area.deletedAt = now(); area.updatedBy = actorId
5.  repo.save(area)
6.  changeLog.log(id, actorId, DEACTIVATE, oldSnapshot, snapshot(area), null)
```

Bản chụp (`snapshot`) là `Map<String,Object>` gồm: `code`, `name`, `areaLevel`, `building`, `floor`, `mapX`, `mapY`, `isActive`.

---

## 7. Test bắt buộc

### Unit — `AreaValidatorTest`, không cần database

| # | Trường hợp | Kỳ vọng |
|---|---|---|
| U1 | `code = "server-b01"` | Chuẩn hoá `SERVER-B01`, hợp lệ |
| U2 | `code = "AB"` | `ERR_AREA_004` |
| U3 | `code = "SERVER_B01"` | `ERR_AREA_004` |
| U4 | `code = "-SERVER"` | `ERR_AREA_004` |
| U5 | `name` rỗng | `ERR_AREA_005` |
| U6 | Hạ 3→1, `reason` rỗng | `ERR_AREA_008` |
| U7 | Hạ cấp, `reason` 5 ký tự | `ERR_AREA_008` |
| U8 | Nâng 1→3, không có `reason` | Hợp lệ |
| U9 | Chỉ có `mapX`, thiếu `mapY` | `ERR_AREA_006` |

### Integration — `AreaControllerIT`, Testcontainers PostgreSQL

| # | Trường hợp | Kỳ vọng |
|---|---|---|
| I1 | ADMIN tạo khu vực | `201`, body có `id` |
| I2 | Tạo trùng `code` đang hoạt động | `409 ERR_AREA_001` |
| **I3** | **Ngừng khu vực rồi tạo lại đúng `code` đó** | **`201` — bắt lỗi thiếu partial index** |
| **I4** | **FACILITY_MANAGER gọi `POST /api/areas`** | **`403`** |
| I5 | FACILITY_MANAGER gọi `GET /api/areas` | `200` |
| I6 | INTERNAL_GUARD gọi `GET /api/areas` | `403` |
| I7 | Không có token | `401` |
| I8 | `PUT` gửi `code` khác | `400 ERR_AREA_007` |
| I9 | `PUT` gửi `code` trùng khác hoa thường | `200`, `code` không đổi |
| I10 | Ngừng khu vực | `204`; `GET` mặc định không thấy, `?isActive=false` thấy |
| I11 | Ngừng khu vực đã ngừng | `404 ERR_AREA_002` |
| I12 | Hạ cấp có `reason` hợp lệ | `200`, `area_change_logs` có dòng UPDATE chứa `reason` |
| I13 | `?keyword=server` | Khớp cả `code` và `name`, bỏ qua hoa thường |
| I14 | `POST` với `areaLevel = 9` | `400 ERR_AREA_003` |
| I15 | Mọi thao tác ghi | `area_change_logs` có đúng 1 dòng mới |

**I3 và I4 là hai ca quan trọng nhất.** I4 kiểm chứng bảng phân quyền — nếu `@EnableMethodSecurity` thiếu, ca này sẽ fail và đó chính là điều cần biết.

---

## 8. Frontend

### Ràng buộc theo repo thật

| Hạng mục | Thực tế | Hệ quả |
|---|---|---|
| Ngôn ngữ | JavaScript thuần (`.jsx`) | **Cấm** tạo `.ts` / `.tsx` |
| Thư mục | `frontend/src/` với `api/ components/ context/ pages/ services/` | Bám theo, không tạo `features/` |
| Lớp gọi API | **Chưa có `apiClient`** | Xem ghi chú dưới |

> ⚠️ **Phụ thuộc:** M06 frontend cần một lớp `apiClient` tự gắn `Authorization: Bearer`. Việc này thuộc phần **hoàn thiện frontend login** đang làm dở (70%). Làm xong phần đó rồi mới bắt đầu frontend M06 — nếu không, mọi lời gọi API sẽ trả 401.

### File cần tạo

```
frontend/src/
├─ api/areaApi.js                     // dùng apiClient, KHÔNG gọi fetch trực tiếp
├─ pages/AreaListPage.jsx             // Màn 1
└─ components/area/
    ├─ AreaFormModal.jsx              // Màn 2
    ├─ AreaDeactivateDialog.jsx       // Màn 3
    └─ AreaLevelBadge.jsx
```

### Màn 1 — Danh sách · `/admin/areas`

Cột: Mã · Tên · Cấp độ (badge) · Toà · Tầng · Trạng thái · Thao tác.

Badge chọn màu theo số `areaLevel`: `1` xanh lá, `2` vàng hổ phách, `≥3` đỏ. Không hardcode theo `code`.

Lọc: ô tìm kiếm debounce 400ms · dropdown cấp độ · dropdown toà nhà · dropdown trạng thái (mặc định "Đang sử dụng"). Phân trang phía server.

Nút "Tạo khu vực" và cột Thao tác **chỉ hiện với ADMIN**.

Trạng thái rỗng: "Chưa có khu vực nào. Tạo khu vực đầu tiên để bắt đầu cấu hình hệ thống."

### Màn 2 — Form tạo / sửa (modal)

| Trường | Ghi chú |
|---|---|
| Mã khu vực | Tự viết hoa khi gõ. **Disabled ở chế độ sửa** kèm chú thích "Không thể thay đổi sau khi tạo" |
| Tên khu vực | Bắt buộc |
| Cấp độ | Select, hiện `name` + hệ quả. VD "Công cộng — không chạy nhận diện khuôn mặt" |
| Toà nhà / Tầng | Tuỳ chọn |
| Mô tả | Textarea |
| Toạ độ X / Y | Cả hai hoặc không cái nào |

**Cảnh báo hạ cấp — hiện ngay khi chọn cấp độ thấp hơn, trước khi bấm Lưu:**

> ⚠️ **Đang hạ SERVER-B01 từ Hạn chế tuyệt đối xuống Công cộng**
> · Khu vực này sẽ **không còn chạy nhận diện khuôn mặt**
> · Thay đổi chỉ áp dụng cho sự kiện mới
>
> **Lý do hạ cấp** *(bắt buộc, tối thiểu 10 ký tự)*

Nút Lưu vô hiệu cho tới khi nhập đủ lý do.

### Màn 3 — Hộp thoại ngừng sử dụng

Mở → gọi `/dependencies` → skeleton khi đang tải → render.

Phase 1 chưa có ràng buộc nào, nên hộp thoại chủ yếu là xác nhận. Nhưng **viết sẵn phần render `blockers` và `warnings`** để M07, M08 chỉ cần trả dữ liệu là hiển thị được.

Nút ghi **"Ngừng sử dụng"**, không ghi "Xoá".

---

## 9. Điều kiện hoàn thành

- [ ] `SecurityConfig` có `@EnableMethodSecurity` (kiểm tra hoặc bổ sung)
- [ ] `docker compose down -v && up` chạy sạch, Flyway apply đủ V1..V6, `success = t`
- [ ] `indexdef` của `ux_areas_code` chứa `WHERE (deleted_at IS NULL)`
- [ ] 9 unit test + 15 integration test xanh
- [ ] Ca **I3** và **I4** xanh
- [ ] Đăng nhập FM: không thấy nút Tạo/Sửa/Ngừng; gọi thẳng API vẫn `403`
- [ ] Hạ cấp không nhập lý do → không lưu được
- [ ] Mọi thao tác ghi có dòng trong `area_change_logs`
- [ ] Không có file `.ts`/`.tsx` trong `frontend/`
- [ ] Đã tạo 5–8 khu vực mẫu cho M07 và M08 dùng

---

## Phase 2 — phân cấp cha–con (làm sau, nếu còn thời gian)

Thêm bằng một migration, không phá gì:

```sql
ALTER TABLE areas ADD COLUMN parent_area_id INTEGER REFERENCES areas(id);
ALTER TABLE areas ADD CONSTRAINT ck_areas_not_self CHECK (id <> parent_area_id);
CREATE INDEX ix_areas_parent ON areas(parent_area_id) WHERE deleted_at IS NULL;
```

Kèm theo: luật `con.area_level >= cha.area_level`, chống vòng lặp, giới hạn 3 cấp, chặn nâng cấp độ cha khi con lỏng hơn, endpoint `GET /api/areas/tree`, component `AreaParentSelect`.

Ước lượng thêm: **1 ngày**.
