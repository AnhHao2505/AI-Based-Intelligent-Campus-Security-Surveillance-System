# Mô hình phân quyền ra vào khu vực — ICSSS

> Cập nhật 22/09/2026. Nhóm FA26SE040.

## 1. Đã chốt

| Thành phần | Nội dung |
|---|---|
| `users.access_level` | Cấp của người dùng (1–3) |
| `areas.area_access_level` | Ngưỡng vào tự do. Mặc định lấy theo `area_level`, sửa được cho từng khu vực |
| `areas.explicit_authorization_required` | Bật: level **không có tác dụng**, chỉ người được gán hoặc có đơn được duyệt mới vào được |
| Assigned Personnel (V37) | Người được gán cố định vào khu vực (ví dụ hiệu trưởng ↔ phòng hiệu trưởng). Có `valid_to` để quyền tự hết hạn |
| Access Request (đã có) | Quyền **tạm thời theo khung giờ**, không đếm lượt. Dùng cho: người cấp dưới lên phòng cấp trên, tiếp khách, hội thảo, nghiên cứu. Giữ cả đơn nhóm |

## 2. Thứ tự xét quyền (`checkEntry`)

| Bước | Điều kiện | Kết quả |
|---|---|---|
| 1 | User hoặc area không hợp lệ | Không cho vào |
| 2 | Có bản ghi gán còn hiệu lực | Cho vào (ASSIGNED_PERSONNEL) |
| 3 | Cờ chỉ định đích danh **tắt** và `user_level >= area_access_level` | Cho vào (ACCESS_LEVEL) |
| 4 | Có đơn APPROVED đúng khung giờ | Cho vào (ACCESS_REQUEST) |
| 5 | Còn lại | Không cho vào |

## 3. Kiểm chứng

| Tình huống | Kết quả |
|---|---|
| Hiệu trưởng vào phòng hiệu trưởng | Bước 2 → cho vào |
| Hiệu trưởng vào phòng server (cờ bật), chưa có đơn | Bước 5 → **không cho vào** |
| Trợ lý lên phòng hiệu trưởng, có đơn được duyệt | Bước 4 → cho vào |
| FM level 2 vào khu vực SEMI_PRIVATE | Bước 3 → cho vào |
| Người lạ vào lab | Bước 5 → không cho vào |

## 4. Giá trị mặc định — **đã chốt 22/09**

Lưu trong DB, không viết cứng trong Java.

**Level mặc định theo role**

| Role | Level |
|---|---|
| NORMAL_USER | 1 |
| GUARD | 2 |
| FACILITY_MANAGER | 2 |
| ADMIN | 1 |

Giảng viên và sinh viên cùng là NORMAL_USER → cùng mặc định 1. FM nâng cấp cho giảng viên sau.

**Điền sẵn khi tạo khu vực**

| `area_level` | `area_access_level` | `explicit_authorization_required` |
|---|---|---|
| PUBLIC | 1 | false |
| SEMI_PRIVATE | 2 | false |
| PRIVATE | 3 | true |

**Người sửa level của user và quy tắc khu vực:** FM (theo phiếu: *Configure access permissions*). ADMIN chỉ xem.

## 5. Hiện trạng code

| Hạng mục | Trạng thái | Vị trí |
|---|---|---|
| V37 `area_assigned_personnel` + API gán/sửa/thu hồi | **Xong** | `V37__area_assigned_personnel.sql`, `AreaAssignedPersonnelController`, `AreaAssignedPersonnelService` |
| `checkEntry` đủ 5 bước (gán → level → đơn) | **Xong** | `AccessDecisionService#checkEntry` |
| V38: `users.access_level`, `areas.area_access_level`, `areas.explicit_authorization_required`, bảng `area_level_presets`, 4 config `ACCESS_LEVEL_DEFAULT_*` | **Xong** | `V38__access_level_model.sql` |
| Khu vực mới lấy quy tắc từ preset, thiếu preset thì fail-closed 3/true | **Xong** | `AreaService#create` |
| User mới lấy level mặc định theo role từ config | **Xong** | `UserAccessLevelHelper#resolveDefaultAccessLevel` (gọi từ `UserService`, `UserBulkImportHelper`) |
| `GET /api/users/search` (FM, ADMIN, chỉ 5 trường) | **Xong** | `UserController#searchUsers`, `UserService#searchUsers` |
| `PATCH /api/users/{id}/access-level` (FM, không tự sửa mình) | **Xong** | `UserController#updateAccessLevel`, `UserService#updateAccessLevel` |
| `PATCH /api/areas/{id}/access-rules` (FM) | **Xong** | `AreaController#updateAccessRules`, `AreaService#updateAccessRules` |
| FE: tab Assigned Personnel, sửa quy tắc khu vực, sửa level user | **Chưa làm** | `frontend/src` |
| Màn hình sửa preset | **Chưa làm** | Chưa có |
| Audit log thay đổi quyền (BR-AP-07) | **Chưa làm** | Chưa có |
| FM không tự gán mình vào khu vực (BR-AP-06) | **Chưa làm** | Chờ GVHD trả lời số lượng FM |

*Số test hiện tại: 196/196 test pass (`./mvnw test`).*

## 6. Còn mở

| # | Việc |
|---|---|
| 1 | ~~FM tìm user khi gán~~ → **Đã chốt:** endpoint tìm kiếm cho FM/ADMIN, chỉ trả `id, userCode, fullName, role, accessLevel`; từ khoá ≥ 2 ký tự, tối đa 20 kết quả, chỉ user đang hoạt động — **Đã làm** |
| 2 | Số lượng FM (hỏi GVHD) → quyết rule FM không tự gán mình. Việc FM tự sửa level của chính mình **đã bị chặn**, còn FM tự gán mình vào khu vực thì vẫn chờ |
| 3 | Khách ngoài hệ thống: `is_guest` + `face_data.is_temporary`, tự xoá mặt khi hết sự kiện. Làm sau mainflow, cần module AI chuyển việc xét quyền sang `checkEntry` trước |
