# Thiết Kế, Lộ Trình & Cách Kiểm Nghiệm: Bản Đồ 3D Đa Tòa/Đa Tầng (`Tòa HCM` & `Tòa Epsilon`)

> [!IMPORTANT]
> **Nguyên tắc phân tách trách nhiệm (Separation of Concerns) đã chốt**:
> 1. **`FloorDigitizerStudio` (Dev Tool số hóa hình học — chỉ mở từ Admin)**:
>    - **Chỉ làm đúng 1 việc**: Dùng ảnh `.png` làm giấy can để vẽ **Hình học thuần túy (Pure Geometry)** gồm: Khung tòa nhà, Đa giác các Phòng, và Đồ thị đường đi (Nodes + Edges) $\rightarrow$ Lưu/Xuất ra file GeoJSON tĩnh.
>    - **KHÔNG chứa bất kỳ cấu hình nghiệp vụ `Area` nào** (không chọn `PUBLIC`/`CONFIDENTIAL`/`HIGHLY_CONFIDENTIAL`, không gán `Area` DB, không có `ATRIUM` hay phân loại rườm rà).
> 2. **`CampusMapView` (Màn hình Quản lý Khu vực hiện tại của Admin & FM)**:
>    - Render khối 3D từ file GeoJSON tĩnh đã số hóa (**không hiện ảnh PNG thô**).
>    - **Mọi nghiệp vụ cấu hình `Area` (Tạo vùng, Đặt tên khu vực, Chọn cấp độ an ninh Công khai/Bảo mật/Bảo mật cao, Đặt tọa độ ghim trên bản đồ)** vẫn do **Admin thực hiện hoàn toàn trên `CampusMapView`** như luồng hiện tại! Khi một ghim `Area` của Admin có tọa độ `(centerLatitude, centerLongitude)` nằm trong đa giác của khối phòng 3D nào, khối phòng 3D đó sẽ tự động lấy màu cấp độ an ninh và tên của `Area` đó.

---

## 1. Bốn Giao Diện & Chế Độ Render Theo Mục Đích Sử Dụng

| Vai trò & Màn hình | Có hiện ảnh PNG? | Mục đích & Cách Render trên Bản đồ 3D |
| :--- | :---: | :--- |
| **1. `FloorDigitizerStudio`**<br/>*(Dev Tool số hóa hình học)* | **CÓ**<br/>*(Làm giấy can)* | • Giao diện riêng biệt để đồ nét hình học từ ảnh PNG ở góc nhìn phẳng `2D (0°)` và xem thử khối `3D (52°)`.<br/>• Chỉ vẽ hình học: Khung tòa nhà, Đa giác các Phòng, Đồ thị đường đi $\rightarrow$ Xuất file GeoJSON tĩnh. |
| **2. `ADMIN` trên `CampusMapView`**<br/>*(Quản trị Khu vực `Area` & Camera)* | **KHÔNG** | • Render **Vector 3D sạch**: Sàn hành lang + Tường bao + Khối phòng 3D.<br/>• **Admin thực hiện toàn bộ việc tạo/sửa `Area`, chọn cấp độ an ninh, đặt ghim tọa độ ngay trên `CampusMapView`**. Phòng 3D nào chứa ghim `Area` sẽ tự động hiển thị màu cấp độ an ninh (`PUBLIC` Xanh, `CONFIDENTIAL` Vàng, `HIGHLY_CONFIDENTIAL` Đỏ) của `Area` đó; phòng chưa gán `Area` hiển thị màu xám trung tính. |
| **3. `FACILITY_MANAGER` trên `CampusMapView`**<br/>*(Giám sát Vận hành & Sự cố)* | **KHÔNG** | • Các khối phòng bình thường hiển thị tông màu **trung tính dịu mắt (Slate/Blue-grey)**.<br/>• Chỉ làm nổi bật khối phòng của `Area` đang được chọn hoặc có **Sự cố an ninh (`SecurityIncident`)**. |
| **4. `GUARD` trên Mobile `GuardMapScreen`**<br/>*(Tác chiến & Dẫn đường Real-time)* | **KHÔNG** | • **Giao diện Tác chiến Tương phản cao (High-Contrast Tactical 3D)**: Sàn hành lang sáng rõ + Khối phòng tối giản + **Khối phòng của `Area` có báo động phát sáng Đỏ**.<br/>• Vẽ **vạch dẫn đường màu Cam đậm** từ vị trí (IPS/GPS) của Bảo vệ $\rightarrow$ sân trường $\rightarrow$ cầu thang $\rightarrow$ cửa phòng báo động. |

---

## 2. Cơ Chế Tự Động Render Hành Lang & Tường Bao Tòa Nhà

Từ dữ liệu hình học vẽ trong `FloorDigitizerStudio`, mỗi tầng được tổng hợp tự động từ **3 lớp Vector 3D**:
1. **Đường bao Giới hạn Tòa nhà (`Building Outer Boundary`)**:
   - **Tự động sinh Sàn Hành Lang (`Floor Slab` - cao `0.15m`)**: Đổ nền cho toàn bộ phạm vi tòa nhà. Khi các **Khối Phòng 3D (`cao 3.2m`)** mọc lên trên mặt sàn này, **phần diện tích sàn trống nằm giữa các khối phòng tự động tạo thành Hành Lang (Corridor)** rõ nét.
   - **Tự động sinh Tường Bao Tòa Nhà (`Boundary Walls` - dày `~25cm`, cao `3.2m`)**: Viền của đường bao tòa nhà tự động dựng thành bức tường giới hạn bao quanh tầng.
2. **Khối Phòng 3D (`Room Polygons` - cao `3.2m`)**: Chỉ lưu tọa độ đa giác `ring` của từng phòng.
3. **Vách Ngăn Phụ (`Custom Wall Lines`)**: Các đoạn thẳng tường lẻ tự động được bơm độ dày `20cm` và dựng cao `3.2m`.

---

## 3. Đặc Tả Chi Tiết Công Cụ Vẽ Khối Phòng 3D (`📦 Room Digitizer Tool` — Thuần Hình Học)

Trong `FloorDigitizerStudio`, công cụ **`📦 Vẽ Khối Phòng`** tập trung 100% vào tốc độ và độ chính xác hình học để bạn số hóa 5 tấm ảnh PNG nhanh nhất:

### 3.1. Ba Chế Độ Vẽ Hình Học Phòng (`Drawing Modes`)
1. **Chế độ 1 — `2-Click Box` (Hình chữ nhật nhanh)**:
   - Click đỉnh Góc Trên-Trái $\rightarrow$ Click đỉnh Góc Dưới-Phải để tạo ngay đa giác phòng vuông vức (`Ohm Lab`, `Innovation Lab`, `Phòng 01-21`, `01-22`, `Thư viện`...).
2. **Chế độ 2 — `Multi-Point Polygon` (Đa giác tự do / Phòng nghiêng chéo)**:
   - Click từng góc đỉnh của phòng theo chiều kim đồng hồ trên ảnh PNG $\rightarrow$ Double-click (hoặc bấm `Enter`) để khép kín đa giác (dùng cho các phòng nằm trên cạnh xiên của `Tòa HCM` hoặc phòng hình chữ L).
3. **Chế độ 3 — `Row Splitter` (Chia nhanh 1 khung dài thành N phòng liền kề)**:
   - Vẽ **1 khung bao quanh cả dãy phòng** (ví dụ dãy `004..007` hoặc `033..037`) $\rightarrow$ nhập số lượng phòng muốn chia (`N`) $\rightarrow$ Công cụ **tự động cắt đều khung đó thành `N` đa giác phòng liền khít vách với nhau** chỉ trong 3 giây.

### 3.2. Bắt Dính Đỉnh/Cạnh (`Snapping`), Kéo Sửa Đỉnh & Copy Tầng
- **Bắt dính đỉnh & cạnh chung (`Vertex & Edge Snapping`)**: Khi đưa chuột gần đỉnh hoặc cạnh của phòng đã vẽ (`< 10px`), con trỏ tự động hút chặt vào đỉnh/cạnh đó để hai phòng sát vách nhau không bị hở khe hay chồng lấn khối 3D.
- **Kéo sửa đỉnh (`Vertex Drag`)**: Click chọn phòng đã vẽ để kéo chỉnh lại từng góc đỉnh cho khít nét vẽ trên ảnh PNG.
- **Copy bố cục phòng sang tầng khác (`Clone to Floor`)**: Cho phép sao chép toàn bộ đa giác các phòng từ `Tầng G` lên `Tầng 1`, `Tầng 2` rồi chỉ việc chỉnh sửa nhẹ những chỗ khác biệt.

### 3.3. Cách Khối Phòng 3D Tự Động Nhận Nghiệp Vụ `Area` Trên `CampusMapView` (Zero Config trong Dev Tool)
- Trong `FloorDigitizerStudio`, mỗi phòng chỉ là một đa giác hình học `{ id, building, floorOrder, ring }`.
- Khi hiển thị trên **`CampusMapView`**:
  - Admin vẫn dùng tính năng **"Thêm vùng mới"** / **"Đặt vị trí trên bản đồ"** sẵn có của `CampusMapView` để đặt ghim `Area` vào trong khối phòng 3D.
  - Hệ thống dùng phép kiểm tra hình học **`Point-in-Polygon`** (tọa độ `[centerLongitude, centerLatitude]` của `Area` nằm trong đa giác phòng 3D nào trên cùng tầng) để **tự động gắn màu `areaLevel` (`PUBLIC` / `CONFIDENTIAL` / `HIGHLY_CONFIDENTIAL`), tên `Area` và sự kiện click chọn `Area` cho khối phòng 3D đó**!

---

## 4. Đặc Tả Chi Tiết Công Cụ Vẽ Đồ Thị Đường Đi (`🧭 Nav Graph Tool`)

1. **5 Loại Điểm Nút (`Node Types`)**:
   - 🟢 `OUTDOOR` (Đường nội bộ sân trường - `floorOrder = 0`).
   - 🟡 `ENTRANCE` (Cửa chính ra vào tòa nhà ở Tầng Trệt - nối `OUTDOOR` với `CORRIDOR`).
   - 🔵 `CORRIDOR` (Điểm tim hành lang & ngã rẽ trên từng tầng).
   - 🟠 `DOOR` (Điểm cửa phòng - tự động kẻ đoạn nối vuông góc ngắn nhất ra dây `CORRIDOR` gần nhất).
   - 🟣 `STAIRS` / `ELEVATOR` (Điểm Cầu thang bộ / Thang máy).
2. **Thao tác vẽ nhanh**:
   - **Vẽ chuỗi liên hoàn (`Chain Mode`)**: Click liên tiếp dọc hành lang để tự nối dây `Edge`; bấm `Esc` để ngắt chuỗi.
   - **Bắt dính nút cũ (`Snap-to-Node`)**: Đưa chuột gần nút đã có (`< 12px`) để tạo ngã ba, ngã tư.
   - **Liên kết Xuyên Tầng tự động (`portalGroupId`)**: Các nút `STAIRS` / `ELEVATOR` ở các tầng khác nhau có cùng mã (ví dụ `HCM_STAIR_W`, `EPS_THANG_TH1`) sẽ tự động được nối cạnh dọc xuyên tầng.
