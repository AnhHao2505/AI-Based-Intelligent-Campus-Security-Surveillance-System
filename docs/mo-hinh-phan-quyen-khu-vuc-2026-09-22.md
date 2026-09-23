# Mô hình phân quyền ra vào khu vực — ICSSS

> Cập nhật 23/09/2026. Nhóm FA26SE040.

## 1. Đã chốt

| Thành phần | Nội dung |
|---|---|
| `users.access_level` | Cấp của người dùng (1–3) |
| `areas.area_access_level` | Ngưỡng vào tự do (1–3). Mặc định lấy theo preset khi tạo khu vực, sửa được cho từng khu vực bởi FM |
| `areas.explicit_authorization_required` | Bật: level **không bao giờ có tác dụng** (kể cả Level 3), chỉ người được gán hoặc có đơn được duyệt mới vào được |
| Assigned Personnel (V37) | Người được gán cố định vào khu vực. Áp dụng cho mọi khu vực trừ PUBLIC. Có `valid_to` để quyền tự hết hạn |
| Access Request (đã có, V40 có thêm FINISHED) | Quyền **tạm thời theo khung giờ**, không đếm lượt. Áp dụng cho mọi khu vực trừ PUBLIC. Đơn FINISHED/PENDING/REJECTED không cho vào |

## 2. Thứ tự xét quyền (`checkEntry`)

Điểm quyết định duy nhất trong `AccessDecisionService.checkEntry(userId, areaId, at)` theo đúng 5 bước:

| Bước | Điều kiện | Kết quả |
|---|---|---|
| 1 | User hoặc Area không tồn tại / inactive / đã bị xoá mềm | Không cho vào (DENY) |
| 2 | Có bản ghi Assigned Personnel còn hiệu lực tại `at` (chưa thu hồi, valid_from <= at < valid_to, valid_to NULL = vô hạn) | Cho vào (ASSIGNED_PERSONNEL) |
| 3 | Cờ `explicit_authorization_required` là **false** VÀ `user.access_level >= area.area_access_level` | Cho vào (ACCESS_LEVEL) |
| 4 | Có đơn Access Request với status == **APPROVED** trong khung giờ (`startTime <= at < endTime`), user là người tạo hoặc thành viên | Cho vào (ACCESS_REQUEST) |
| 5 | Còn lại | Không cho vào (DENY) |

*Nguyên tắc bất biến: Khi `explicit_authorization_required == true`, cấp độ truy cập (access level) KHÔNG bao giờ cho vào, kể cả level cao nhất (Level 3).*

## 3. Kiểm chứng

| Tình huống | Kết quả |
|---|---|
| Hiệu trưởng được gán vào phòng hiệu trưởng | Bước 2 → cho vào (ASSIGNED_PERSONNEL) |
| Hiệu trưởng vào phòng server HIGHLY_CONFIDENTIAL (cờ bật), chưa có đơn | Bước 5 → **không cho vào** (DENY) |
| Trợ lý lên phòng hiệu trưởng, có đơn APPROVED trong giờ | Bước 4 → cho vào (ACCESS_REQUEST) |
| FM level 2 vào khu vực INTERNAL_CONFIDENTIAL (level 2, cờ tắt) | Bước 3 → cho vào (ACCESS_LEVEL) |
| User level 3 vào khu vực CONFIDENTIAL_CONTACT_REQUIRED (level 3, cờ tắt) | Bước 3 → cho vào (ACCESS_LEVEL) |
| User level 2 vào khu vực CONFIDENTIAL_CONTACT_REQUIRED (level 3, cờ tắt), không đơn | Bước 5 → **không cho vào** (DENY) |
| User level 3 vào phòng HIGHLY_CONFIDENTIAL (cờ bật), không gán / không đơn | Bước 5 → **không cho vào** (DENY) |
| Người lạ vào lab | Bước 5 → không cho vào (DENY) |

## 4. Giá trị mặc định (Preset) — **đã chốt 23/09 (V41)**

Lưu trong DB (`area_level_presets`), đọc qua repository khi tạo khu vực, không viết switch/if-else cứng trong Java. Khi thiếu preset trong DB, áp dụng cơ chế **fail-closed**: `areaAccessLevel = 3`, `explicitAuthorizationRequired = true`, ghi log WARN.

**Level mặc định theo role**

| Role | Level |
|---|---|
| NORMAL_USER | 1 |
| GUARD | 2 |
| FACILITY_MANAGER | 2 |
| ADMIN | 1 |

Giảng viên và sinh viên cùng là NORMAL_USER → cùng mặc định 1. FM nâng cấp cho giảng viên sau.

**Điền sẵn khi tạo khu vực (V41)**

| `area_level` | `area_access_level` | `explicit_authorization_required` | Giải thích |
|---|---|---|---|
| PUBLIC | 1 | false | Công khai: mọi level tự do ra vào |
| INTERNAL_CONFIDENTIAL | 2 | false | Nội bộ: Level 2, 3 tự do ra vào; Level 1 cần đơn/gán |
| CONFIDENTIAL_CONTACT_REQUIRED | 3 | false | Liên hệ trước: Level 3 tự do ra vào; Level 1, 2 cần đơn/gán |
| HIGHLY_CONFIDENTIAL | 3 | true | Bảo mật cao: Cấm vào tự do; bắt buộc có đơn hoặc nhân sự chỉ định |

*Quy tắc cập nhật khu vực (I3): ADMIN sửa thông tin khu vực (`PUT /api/areas/{id}`) KHÔNG được thay đổi `areaAccessLevel` và `explicitAuthorizationRequired`. Hai trường này chỉ được sửa bởi FM qua `PATCH /api/areas/{id}/access-rules`.*

## 5. Hiện trạng code

| Hạng mục | Trạng thái | Vị trí |
|---|---|---|
| V37 `area_assigned_personnel` + API gán/sửa/thu hồi | **Xong** | `V37__area_assigned_personnel.sql`, `AreaAssignedPersonnelController`, `AreaAssignedPersonnelService` |
| `checkEntry` đủ 5 bước chuẩn hóa I1 | **Xong** | `AccessDecisionService#checkEntry` |
| V38: `users.access_level`, `areas.area_access_level`, `areas.explicit_authorization_required`, bảng `area_level_presets`, 4 config `ACCESS_LEVEL_DEFAULT_*` | **Xong** | `V38__access_level_model.sql` |
| V40: 4 loại khu vực mới (`PUBLIC`, `INTERNAL_CONFIDENTIAL`, `CONFIDENTIAL_CONTACT_REQUIRED`, `HIGHLY_CONFIDENTIAL`) & status FINISHED | **Xong** | `V40__area_security_levels.sql` |
| V41: Khôi phục preset bất biến (CONFIDENTIAL_CONTACT_REQUIRED: 3, false) không đụng dữ liệu areas | **Xong** | `V41__fix_area_level_presets.sql` |
| Khu vực mới lấy quy tắc từ preset, thiếu preset thì fail-closed 3/true, không switch | **Xong** | `AreaService#create` |
| Admin update khu vực không ghi đè access rules (I3) | **Xong** | `AreaService#update` |
| Cho phép gán AP và tạo Access Request với mọi khu vực trừ PUBLIC | **Xong** | `AreaAssignedPersonnelService`, `AccessRequestService`, `AreaService#getAvailableAreasForRequest` |
| User mới lấy level mặc định theo role từ config | **Xong** | `UserAccessLevelHelper#resolveDefaultAccessLevel` (gọi từ `UserService`, `UserBulkImportHelper`) |
| `GET /api/users/search` (FM, ADMIN, chỉ 5 trường) | **Xong** | `UserController#searchUsers`, `UserService#searchUsers` |
| `PATCH /api/users/{id}/access-level` (FM, không tự sửa mình) | **Xong** | `UserController#updateAccessLevel`, `UserService#updateAccessLevel` |
| `PATCH /api/areas/{id}/access-rules` (FM) | **Xong** | `AreaController#updateAccessRules`, `AreaService#updateAccessRules` |
| FE: Hỗ trợ 4 loại khu vực, AP modal & nút quick action cho mọi khu vực trừ PUBLIC | **Xong** | `AreaListView.jsx`, `AreaMapView.jsx`, `areaHelpers.js` |

*Số test hiện tại: 263/263 test pass (`./mvnw test`).*

## 6. Còn mở

| # | Việc |
|---|---|
| 1 | ~~FM tìm user khi gán~~ → **Đã chốt:** endpoint tìm kiếm cho FM/ADMIN, chỉ trả `id, userCode, fullName, role, accessLevel`; từ khoá ≥ 2 ký tự, tối đa 20 kết quả, chỉ user đang hoạt động — **Đã làm** |
| 2 | Số lượng FM (hỏi GVHD) → quyết rule FM không tự gán mình. Việc FM tự sửa level của chính mình **đã bị chặn**, còn FM tự gán mình vào khu vực thì vẫn chờ |
| 3 | Khách ngoài hệ thống: `is_guest` + `face_data.is_temporary`, tự xoá mặt khi hết sự kiện. Làm sau mainflow, cần module AI chuyển việc xét quyền sang `checkEntry` trước |
