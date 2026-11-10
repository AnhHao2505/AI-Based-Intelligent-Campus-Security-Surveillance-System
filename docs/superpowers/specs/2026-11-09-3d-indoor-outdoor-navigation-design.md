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
| **1. `FloorDigitizerStudio`**<br/>*(Dev Tool số hóa hình học)* | **CÓ**<br/>*(Làm giấy can)* | • Giao diện CAD chuyên biệt (`Tool Rail 52px · Canvas · Inspector 320px`) để đồ nét hình học từ ảnh PNG ở góc nhìn phẳng `2D (0°)` và xem thử khối `3D (52°)`.<br/>• Chỉ vẽ hình học: Khung tòa nhà, Đa giác các Phòng, Vách ngăn, Đồ thị đường đi $\rightarrow$ Xuất/Nhập file GeoJSON tĩnh. |
| **2. `ADMIN` trên `CampusMapView`**<br/>*(Quản trị Khu vực `Area` & Camera)* | **KHÔNG** | • Render **Vector 3D sạch** kèm ánh sáng định hướng MapLibre (`MAP_LIGHT`) và layer nhãn tên `Area` trên bề mặt phòng khi zoom cận ($\ge 18.2$): Sàn hành lang + Tường bao + Khối phòng 3D.<br/>• **Admin thực hiện toàn bộ việc tạo/sửa `Area`, chọn cấp độ an ninh, đặt ghim tọa độ ngay trên `CampusMapView`**. Phòng 3D nào chứa ghim `Area` sẽ tự động hiển thị màu cấp độ an ninh (`PUBLIC` Xanh, `CONFIDENTIAL` Vàng, `HIGHLY_CONFIDENTIAL` Đỏ) của `Area` đó; phòng chưa gán `Area` hiển thị màu xám trung tính.<br/>• **Lộ trình A\* 3D:** Tuyến dẫn đường hiển thị nổi trên mặt sàn từng tầng kèm cọc chuyển tầng (thay vì đường 2D dưới đất), đánh dấu điểm xuất phát bằng **biểu tượng người đi bộ** và điểm đến bằng **pin giọt nước**. |
| **3. `FACILITY_MANAGER` trên `CampusMapView`**<br/>*(Giám sát Vận hành & Sự cố)* | **KHÔNG** | • Các khối phòng bình thường hiển thị tông màu **trung tính dịu mắt (Slate/Blue-grey)** kèm ánh sáng 3D `MAP_LIGHT`, nhãn tên `Area` (khi zoom $\ge 18.2$) và dẫn đường A\* 3D (biểu tượng người đi bộ $\rightarrow$ pin giọt nước).<br/>• Chỉ làm nổi bật khối phòng của `Area` đang được chọn hoặc có **Sự cố an ninh (`SecurityIncident`)**. |
| **4. `GUARD` trên Mobile `GuardMapScreen`**<br/>*(Tác chiến & Dẫn đường Real-time)* | **KHÔNG** | • **Giao diện Tác chiến Tương phản cao (High-Contrast Tactical 3D)**: Sàn hành lang sáng rõ + Khối phòng tối giản + **Khối phòng của `Area` có báo động phát sáng Đỏ**.<br/>• Vẽ **vạch dẫn đường màu Cam đậm** từ vị trí (IPS/GPS) của Bảo vệ $\rightarrow$ sân trường $\rightarrow$ cầu thang $\rightarrow$ cửa phòng báo động. |

---

## 2. Cơ Chế Tự Động Render Hành Lang, Cao Độ Tầng & Tường Bao Tòa Nhà

Từ dữ liệu hình học vẽ trong `FloorDigitizerStudio`, mỗi tầng được dựng theo **cao độ thực (`3.5m` / tầng: Tầng Trệt `0–3.5m`, Tầng 1 `3.5–7.0m`, Tầng 2 `7.0–10.5m`)** từ **3 lớp Vector 3D**:
1. **Đường bao Giới hạn Tòa nhà (`Building Outer Boundary`)**:
   - **Tự động sinh Sàn Hành Lang (`Floor Slab` - dày `0.16m`)**: Đổ nền cho toàn bộ phạm vi tòa nhà. Khi các **Khối Phòng 3D** mọc lên trên mặt sàn này, **phần diện tích sàn trống nằm giữa các khối phòng tự động tạo thành Hành Lang (Corridor)** rõ nét.
   - **Tự động sinh Tường Bao Tòa Nhà (`Boundary Walls` - nửa bề rộng `0.16m`, cao `2.85m`)**: Viền của đường bao tòa nhà tự động dựng thành bức tường giới hạn bao quanh tầng với **nối góc vát liền mạch (miter + outer bevel cap)** tránh hở khe hoặc chồng mép ở góc.
2. **Khối Phòng 3D (`Room Polygons` - cao `0.52m`, khi chọn nhô lên `1.0m`)**: Thấp hơn tường bao (`2.85m`) để không tạo cảm giác khối hộp đặc che khuất lòng phòng, vách ngăn và đường dẫn A\* bên trong; chỉ lưu tọa độ đa giác `ring` của từng phòng.
3. **Vách Ngăn Phụ (`Custom Wall Lines` - nửa bề rộng `0.11m`, cao `2.85m`)**: Các đoạn thẳng tường lẻ được nối vát góc liền mạch và dựng cao bằng tường bao (`2.85m`).
4. **Ghost Lower Floors (Xếp tầng mờ khi cắt tầng trên)**: Khi chọn xem các tầng trên (ví dụ Tầng 1, Tầng 2), các tầng bên dưới vẫn được giữ lại dưới dạng **Ghost mờ (`opacity ~0.16–0.20`)** và render trước lớp active để bảo toàn khối kiến trúc tổng thể mà không bị depth-buffer che khuất.

---

## 3. Đặc Tả Chi Tiết Công Cụ Số Hóa Hình Học (`FloorDigitizerStudio` — Chuẩn CAD)

Trong `FloorDigitizerStudio`, giao diện bố trí theo chuẩn CAD **`Tool Rail dọc 52px` (phím 1 ký tự `V, B, P, S, O, W, N, T, C`) · `Canvas MapLibre` · `Contextual Inspector 320px`** (3 tab: *Thuộc tính*, *Lớp & Cây*, *Kiểm tra lỗi Validator*):

### 3.1. Ba Chế Độ Vẽ & Cắt Hình Học Phòng (`Drawing Modes`)
1. **Chế độ 1 — `2-Click Box` (`Phím B`)**:
   - Click đỉnh Góc Trên-Trái $\rightarrow$ Click đỉnh Góc Dưới-Phải để tạo ngay đa giác phòng vuông vức (`Ohm Lab`, `Innovation Lab`, `Phòng 01-21`, `01-22`, `Thư viện`...).
2. **Chế độ 2 — `Multi-Point Polygon` (`Phím P`)**:
   - Click từng góc đỉnh của phòng theo chiều kim đồng hồ trên ảnh PNG $\rightarrow$ Bấm `Enter` (hoặc click lại điểm đầu) để khép kín đa giác (dùng cho các phòng nằm trên cạnh xiên hoặc phòng hình chữ L).
3. **Chế độ 3 — `Polygon Slice` (`Phím S`, chuẩn Mappedin)**:
   - Kẻ một đường cắt 2 điểm xuyên qua đa giác phòng hiện có để tự động cắt đôi thành **2 khối phòng đa giác độc lập** tại đúng giao điểm cạnh (kèm làm sạch topology `cleanRing`).

### 3.2. Tương Tác CAD, Căn Chỉnh Ảnh & Quản Lý Chất Lượng Dữ Liệu
- **Chỉnh sửa Tường & Đường bao trên `2D (0°)`**: Tường bao (`O`) và vách ngăn (`W`) render dưới dạng **polyline có `id` & `wallKind`** (thay vì khối 3D) để click chọn, kéo từng đỉnh góc, và bấm nút **`+` tại trung điểm cạnh** để chèn thêm góc uốn mới.
- **Bắt dính Đỉnh & Cạnh (`Vertex & Edge Snapping`)**: Khi đưa chuột gần đỉnh (`~2.0m`) hoặc cạnh (`~1.5m`) có sẵn, con trỏ tự động hút dính để các phòng sát vách không hở khe; **giữ phím `Alt`** để tạm tắt bắt dính khi cần vẽ tự do.
- **Chọn nhiều & Kéo sửa đỉnh (`Multi-Select` & `Vertex Drag`)**: Dùng công cụ Chọn (`V`) để click kéo chỉnh từng đỉnh phòng; giữ **`Shift` / `Ctrl` + Click** hoặc kéo khung **Marquee (`Shift + Drag`)** để chọn, xóa hoặc sao chép hàng loạt phòng sang tầng kế tiếp (`Clone to Floor`).
- **Căn chỉnh & Xoay ảnh PNG (`CALIBRATE_PNG` - `Phím C`, chuẩn Mappedin)**: Kéo độc lập **4 góc (`NW, NE, SE, SW`)** + **tay cầm di chuyển tâm** + **trục xoay (`Rotation Stalk`)** với live-preview 60fps; dữ liệu `floorCalibrations` lưu `corners` 4 điểm để nội suy song tuyến khớp tọa độ xoay thực tế.
- **Tiện ích Editor & Kiểm định (`Validator`)**: Tích hợp **Undo/Redo 50 bước (`Ctrl+Z` / `Ctrl+Shift+Z`)**, cảnh báo chưa lưu + `beforeunload` guard, bật/tắt & khóa từng lớp (`Layer Visibility & Lock`), và bộ **Validator tự động** phát hiện nút mồ côi, thang thiếu tầng đối ứng, cửa chưa nối hành lang hoặc lỗi đỉnh phòng (click cảnh báo để zoom tới đối tượng lỗi).

---

### 3.3. Cách Khối Phòng 3D Tự Động Nhận Nghiệp Vụ `Area` Trên `CampusMapView` (Zero Config trong Dev Tool)
- Trong `FloorDigitizerStudio`, mỗi phòng chỉ là một đa giác hình học `{ id, building, floorOrder, ring }`.
- Khi hiển thị trên **`CampusMapView`**:
  - Admin vẫn dùng tính năng **"Thêm vùng mới"** / **"Đặt vị trí trên bản đồ"** sẵn có của `CampusMapView` để đặt ghim `Area` vào trong khối phòng 3D.
  - Hệ thống dùng phép kiểm tra hình học **`Point-in-Polygon`** (tọa độ `[centerLongitude, centerLatitude]` của `Area` nằm trong đa giác phòng 3D nào trên cùng tầng) để **tự động gắn màu `areaLevel` (`PUBLIC` / `CONFIDENTIAL` / `HIGHLY_CONFIDENTIAL`), tên `Area` và sự kiện click chọn `Area` cho khối phòng 3D đó**!

---

## 4. Đặc Tả Chi Tiết Công Cụ Vẽ Đồ Thị Đường Đi (`Nav Graph Tool` — `Phím N`)

1. **6 Loại Điểm Nút (`Node Types`) trên UI**:
   - 🟢 `OUTDOOR` (Đường nội bộ sân trường - `floorOrder = 0`).
   - 🟡 `ENTRANCE` (Cửa chính ra vào tòa nhà ở Tầng Trệt - nối `OUTDOOR` với `CORRIDOR`).
   - 🔵 `CORRIDOR` (Điểm tim hành lang & ngã rẽ trên từng tầng).
   - 🟠 `DOOR` (Điểm cửa phòng - tự động kẻ đoạn nối vuông góc ngắn nhất ra dây `CORRIDOR` gần nhất).
   - 🟣 `STAIRS` (Cầu thang bộ — dùng chung `portalGroupId` nối xuyên tầng).
   - 🩷 `ELEVATOR` (Thang máy — tách riêng trên bảng chọn, cùng dùng `portalGroupId` nối xuyên tầng).
2. **Thao tác vẽ, Kéo thả & Kiểm thử A\***:
   - **Vẽ chuỗi liên hoàn (`Chain Mode`)**: Click liên tiếp dọc hành lang để tự nối dây `Edge`; bấm `Esc` để ngắt chuỗi.
   - **Kéo thả trực tiếp nút tròn (`Draggable Nav Nodes`)**: Giữ chuột kéo trực tiếp bất kỳ nút tròn nào trên bản đồ để tinh chỉnh vị trí cùng toàn bộ các cạnh nối liên quan (tự khóa `dragPan` bản đồ trong khi kéo).
   - **Bắt dính nút cũ (`Snap-to-Node`)**: Đưa chuột gần nút đã có (`< 12px`) để tạo ngã ba, ngã tư.
   - **Liên kết Xuyên Tầng tự động (`portalGroupId`)**: Các nút `STAIRS` / `ELEVATOR` ở các tầng khác nhau có cùng mã (ví dụ `HCM_STAIR_W`, `EPS_THANG_TH1`) sẽ tự động được nối cạnh dọc xuyên tầng.
   - **Test tuyến A\* trực tiếp (`Phím T`)**: Click 2 điểm bất kỳ trên bản đồ để kiểm tra đường đi ngắn nhất; hiển thị **marker người đi bộ** tại điểm xuất phát, **pin giọt nước** tại điểm đến, tổng cự ly (`m`) và số tầng chuyển trục dọc.
