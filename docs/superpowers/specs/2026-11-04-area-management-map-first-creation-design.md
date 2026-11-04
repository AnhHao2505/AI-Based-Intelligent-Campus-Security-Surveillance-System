# Thiết Kế Chi Tiết: Hợp Nhất Bản Đồ Vào Quản Lý Khu Vực & Luồng Tạo Khu Vực Ưu Tiên Bản Đồ (Map-First)

- **Tệp tài liệu**: `docs/superpowers/specs/2026-11-04-area-management-map-first-creation-design.md`
- **Ngày tạo**: 2026-11-04
- **Trạng thái**: Hoàn thiện - Chờ phê duyệt (Ready for Approval)
- **Tài liệu quy chuẩn liên quan**: `BR-AREA-MAP-01` (bắt buộc tọa độ tâm khi tạo khu vực), `BR-AREA-MAP-03` (hiển thị marker và cơ chế auto-shift)

---

## 1. Bối cảnh & Mục tiêu (Intent & Why)

### 1.1. Hiện trạng
- **Phân mảnh menu & điều hướng**: Hệ thống hiện có hai trang riêng biệt cho quản trị viên:
  - Trang "Quản lý khu vực" (`/admin/areas` - `AreaListPage.jsx`): Quản lý dạng danh sách/card, tạo mới, chỉnh sửa, vô hiệu hóa khu vực.
  - Trang "Quản lý bản đồ" (`/admin/map` - `CampusMapPage.jsx`): Hiển thị bản đồ toàn khuôn viên (`CampusMapView.jsx`).
  - Hai trang này tải cùng một tập dữ liệu (`areas`, `buildings`, `cameraCounts`) và có bộ lọc tương tự nhau, dẫn đến trùng lặp code và trải nghiệm bị phân mảnh.
- **Quy trình tạo khu vực thiếu trực quan**:
  - Khi bấm "+ Thêm vùng" ở trang Danh sách, modal hiện ra với 2 ô số nhập tay vĩ độ/kinh độ, cho phép để trống cả hai.
  - Quản trị viên muốn gán tọa độ phải qua nhiều bước phụ: tạo xong -> mở trang Bản đồ riêng -> tìm phòng -> bấm "Đặt vị trí trên bản đồ" -> click -> lưu.

### 1.2. Mục tiêu (Goals)
1. **Hợp nhất giao diện (Unified Experience)**:
   - Gộp "Quản lý bản đồ" vào trang "Quản lý khu vực" (`/admin/areas`).
   - Loại bỏ mục menu riêng "Quản lý bản đồ" trên thanh điều hướng bên trái (`Sidebar.jsx`). Chuyển hướng các đường dẫn cũ `/admin/map` về `/admin/areas?view=map`.
   - Bỏ nút link rời rạc "Xem trên bản đồ an ninh" ở góc trên header `PageHeader`.
   - Bổ sung nút chuyển đổi chế độ xem: Tại Toolbar, đặt nút **`[Bản đồ]`** (Icon `Compass`) nằm ngay cạnh cụm `[Đang hoạt động] [Đã vô hiệu hóa]`.
2. **Luồng tạo khu vực ưu tiên bản đồ (Map-First Creation Flow)**:
   - Khi quản trị viên bấm **"+ Thêm vùng"**:
     - Tự động chuyển sang chế độ xem Bản đồ (nếu đang ở chế độ Danh sách).
     - Kích hoạt chế độ chọn vị trí: Bản đồ hiển thị thanh thông báo nổi hướng dẫn: *"Nhấp vào bản đồ để chọn vị trí vùng mới"* kèm nút Hủy.
     - Khi người dùng nhấp vào một điểm trên bản đồ: Ghim nháp xuất hiện tại điểm đó (hỗ trợ kéo thả để tinh chỉnh).
     - **Thanh trượt bên phải (Right Rail)** tự động chuyển sang panel **"Thêm khu vực mới"**:
       - Vĩ độ và kinh độ được tự động điền sẵn từ tọa độ vừa nhấp.
       - Form cho phép nhập: Tên khu vực, Cấp độ bảo mật (Public, Confidential, Highly Confidential), Tòa nhà, Tầng.
       - Quản trị viên bấm **"Tạo khu vực"**: Gọi API tạo mới.
       - Sau khi tạo thành công: Cập nhật danh sách, hiển thị ghim chính thức trên bản đồ và đóng panel tạo.
3. **Bắt buộc tọa độ khi tạo (Mandatory Coordinates on Create)**:
   - Backend `AreaCreateRequest`: Đặt chú thích `@NotNull` cho `centerLatitude` và `centerLongitude`, trả về lỗi HTTP 400 `VALIDATION_ERROR` nếu thiếu.
   - Frontend: Form không cho phép submit nếu chưa chọn điểm tọa độ.
4. **Phạm vi cập nhật (Update Scope)**:
   - Giữ nguyên luồng chỉnh sửa khu vực hiện tại theo yêu cầu người dùng ("còn khi cập nhật thì tính sau").

---

## 2. Các quyết định kỹ thuật đã chốt (Architectural Decisions)

1. **Phân quyền vai trò (RBAC)**:
   - Chỉ người dùng có vai trò `ADMIN` mới nhìn thấy nút chuyển sang chế độ `[Bản đồ]` và nút `[+ Thêm vùng]`.
   - Người dùng `FACILITY_MANAGER` chỉ thấy chế độ Danh sách khu vực đang hoạt động và không có nút chuyển bản đồ (giữ nguyên phân quyền bảo mật hiện hành).
2. **Bố cục thanh trượt bên phải (Right Rail) khi tạo khu vực**:
   - Khi ở chế độ tạo (`isCreatingArea = true`), form "Thêm khu vực mới" sẽ thay thế toàn bộ nội dung thanh bên phải thành một panel nhập liệu độc lập, tập trung (Focused Creation Panel) với tiêu đề, các trường nhập liệu, nút "Hủy" và nút "Tạo khu vực".
   - Người dùng có thể kéo thả ghim trên bản đồ để cập nhật lại tọa độ hiển thị trong form trước khi bấm Tạo.
3. **Bộ lọc Tòa nhà trên thanh công cụ dùng chung**:
   - Khi ở chế độ Danh sách: Giữ bộ lọc từng tòa nhà cụ thể để đảm bảo danh sách tầng hiển thị chính xác.
   - Khi ở chế độ Bản đồ: Dropdown Tòa nhà bổ sung thêm tùy chọn **"Tất cả tòa nhà" (`ALL`)** để quản trị viên có thể quan sát toàn cảnh khuôn viên trường hoặc lọc tập trung vào 1 tòa nhà cụ thể.
4. **Bộ lọc trạng thái `[Đang hoạt động] [Đã vô hiệu hóa]`**:
   - Chế độ Bản đồ chỉ hiển thị các khu vực đang hoạt động. Khi quản trị viên bấm chuyển sang `[Bản đồ]`, hệ thống tự động ẩn hoặc vô hiệu hóa tab "Đã vô hiệu hóa" để tránh xung đột ngữ cảnh.

---

## 3. Thiết kế kỹ thuật chi tiết (Detailed Design)

### 3.1. Phía Backend (Spring Boot)

#### A. Cập nhật DTO: `AreaCreateRequest.java`
- Thêm chú thích `@NotNull` cho hai trường tọa độ:
```java
@NotNull(message = "Vĩ độ không được để trống")
@DecimalMin(value = "-90.0", message = "Vĩ độ phải từ -90 đến 90")
@DecimalMax(value = "90.0", message = "Vĩ độ phải từ -90 đến 90")
private Double centerLatitude;

@NotNull(message = "Kinh độ không được để trống")
@DecimalMin(value = "-180.0", message = "Kinh độ phải từ -180 đến 180")
@DecimalMax(value = "180.0", message = "Kinh độ phải từ -180 đến 180")
private Double centerLongitude;
```

#### B. Nghiệp vụ `AreaService.java`
- Trong hàm `create()`:
  - Tận dụng hàm phòng thủ có sẵn `requireCoordinatePair(req.getCenterLatitude(), req.getCenterLongitude())` trong `AreaService` mà không tạo thêm logic kiểm tra lặp thừa.
  - Việc bắt buộc không được để trống khi tạo mới được kiểm soát chặt chẽ ở tầng DTO qua `@NotNull`, đảm bảo request không hợp lệ sẽ bị chặn ngay từ cổng vào API.

#### C. Cập nhật Tests
- Tệp `backend/src/test/java/com/fa26se040/icss/service/AreaCenterCoordinatesTest.java`:
  - Sửa test case `createWithBothNull_savesNull` -> đổi thành `createWithBothNull_rejected`: Gửi tạo khu vực với vĩ độ và kinh độ null phải trả về HTTP 400 `VALIDATION_ERROR`.
  - Giữ nguyên các test case `updateClearingBoth_setsNull` (do luồng update chưa thay đổi).

---

### 3.2. Phía Frontend (React)

#### A. Điều hướng & Sidebar (`Sidebar.jsx` & `App.jsx`)
- `Sidebar.jsx`:
  - Xóa bỏ mục NavLink "Quản lý bản đồ" (`to="/admin/map"`).
  - Giữ lại mục "Quản lý khu vực" (`to="/admin/areas"`).
- `App.jsx`:
  - Cập nhật route `/admin/map` thành `<Navigate to="/admin/areas?view=map" replace />`.

#### B. Tích hợp View Chế độ xem trong `AreaListPage.jsx`
- State mới:
  - `const [activeView, setActiveView] = useState("list");` // `"list"` hoặc `"map"` (hỗ trợ đọc từ search param `?view=map`).
  - `const [isCreatingOnMap, setIsCreatingOnMap] = useState(false);`
  - `const [newAreaDraftCoords, setNewAreaDraftCoords] = useState(null);` // `[lng, lat]`
- Header & Toolbar `zone-toolbar`:
  - Bỏ nút Link "Xem trên bản đồ an ninh" trong `PageHeader actions`.
  - Cụm Toggle chế độ xem tại toolbar:
    - Nút `[Danh sách]` (Icon `Layers`)
    - Nút `[Bản đồ]` (Icon `Compass`) đặt ngay cạnh cụm `[Đang hoạt động] [Đã vô hiệu hóa]`.
  - Nút `[+ Thêm vùng]`:
    - Khi click: Nếu đang ở view `"list"`, chuyển sang `activeView = "map"`. Kích hoạt `isCreatingOnMap = true`, `newAreaDraftCoords = null`.
- Vùng hiển thị chính:
  - Nếu `activeView === "list"`: Render bảng/thẻ danh sách khu vực như hiện tại.
  - Nếu `activeView === "map"`: Render component `<CampusMapView />` với các props mở rộng.

#### C. Mở rộng Component `CampusMapView.jsx`
- Bổ sung Props:
  - `isCreatingArea`: Boolean - cờ kích hoạt chế độ tạo khu vực trên map.
  - `onStartCreateArea()`: Bắt đầu chế độ tạo.
  - `onCancelCreateArea()`: Hủy chế độ tạo.
  - `onCreateAreaSubmit(areaData)`: Callback gửi request tạo khu vực.
  - `buildingsList`: Danh sách tòa nhà để đổ vào dropdown tạo.
- Tương tác trên bản đồ khi `isCreatingArea = true`:
  - Hiển thị banner nổi phía trên: *"Nhấp vào bản đồ để chọn vị trí vùng mới"* kèm nút "Hủy".
  - Bắt sự kiện `handleMapClick`: Lấy `[e.lngLat.lng, e.lngLat.lat]`, gán vào `draftCoords`.
  - Render ghim nháp nổi bật (màu cam/xanh, hỗ trợ drag).
  - Tự động mở rộng thanh bên phải (`railCollapsed = false`).
- Thanh trượt bên phải (`campus-rail`):
  - Khi `isCreatingArea = true`:
    - Thay thế nội dung bằng Card form **"Thêm khu vực mới"**:
      - Vĩ độ & Kinh độ (hiển thị tọa độ đã chọn trên bản đồ).
      - Tên khu vực (Input text, kiểm tra độ dài và trùng lặp).
      - Cấp độ bảo mật (Public / Confidential / Highly Confidential).
      - Tòa nhà & Tầng (Dropdown Select liên kết theo `buildingsList`).
      - Nút "Hủy" và nút "Tạo khu vực".
    - Khi submit thành công: Reset form, tắt chế độ tạo, tự động chọn khu vực vừa tạo trên bản đồ.

---

## 4. Tài liệu nghiệp vụ & Quy chuẩn (Business Rules & Exceptions)

1. **Cập nhật Business Rule**:
   - Cập nhật `BR-AREA-MAP-01` trong `.agents/bussiness_rule/area_map_coordinate_rules.md`: Khẳng định tọa độ tâm là điều kiện tiên quyết bắt buộc khi tạo khu vực (`POST /api/areas`).
2. **Ghi nhận thay đổi**:
   - Ghi nhận chi tiết vào `.agents/change/2026-11-04.text`.
