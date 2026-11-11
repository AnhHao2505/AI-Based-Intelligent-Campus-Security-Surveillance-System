# Thiết Kế, Lộ Trình & Cách Kiểm Nghiệm: Bản Đồ 3D Đa Tòa/Đa Tầng (`Tòa HCM` & `Tòa Epsilon`)

> [!IMPORTANT]
> **Nguyên tắc phân tách trách nhiệm (Separation of Concerns) đã chốt**:
> 1. **`FloorDigitizerStudio` (Dev Tool số hóa hình học — chỉ mở từ Admin)**:
>    - **Chỉ làm đúng 1 việc**: Dùng ảnh `.png` làm giấy can để vẽ **Hình học thuần túy (Pure Geometry)** theo mô hình **Đồ thị Tường Hợp Nhất (`Unified Wall Graph`)**: mạng lưới tường (`walls`), tự động trích xuất mặt phẳng khép kín (`Planar Faces`: `ROOM` / `CORRIDOR` / `VOID`), cửa gắn tường (`doors`) và đồ thị đường đi (`navNodes` + `navEdges`) $\rightarrow$ Lưu/Xuất ra file GeoJSON tĩnh (`icss_campus_3d_geometry_v3`).
>    - **KHÔNG chứa bất kỳ cấu hình nghiệp vụ `Area` nào** (không chọn `PUBLIC`/`CONFIDENTIAL`/`HIGHLY_CONFIDENTIAL`, không gán `Area` DB, không đặt tên nghiệp vụ rườm rà trong Studio).
> 2. **`CampusMapView` (Màn hình Quản lý Khu vực hiện tại của Admin & FM)**:
>    - Render khối 3D từ file GeoJSON tĩnh đã số hóa (**không hiện ảnh PNG thô**).
>    - **Mọi nghiệp vụ cấu hình `Area` (Tạo vùng, Đặt tên khu vực, Chọn cấp độ an ninh Công khai/Bảo mật/Bảo mật cao, Đặt tọa độ ghim trên bản đồ)** vẫn do **Admin thực hiện hoàn toàn trên `CampusMapView`** như luồng hiện tại! Khi một ghim `Area` của Admin có tọa độ `(centerLatitude, centerLongitude)` nằm trong đa giác của khối phòng 3D nào (kiểm tra bằng `isPointInPolygonWithHoles` có loại trừ lỗ `holes`), khối phòng 3D đó sẽ tự động lấy màu cấp độ an ninh và tên của `Area` đó.

---

## 1. Bốn Giao Diện & Chế Độ Render Theo Mục Đích Sử Dụng

| Vai trò & Màn hình | Có hiện ảnh PNG? | Mục đích & Cách Render trên Bản đồ 3D |
| :--- | :---: | :--- |
| **1. `FloorDigitizerStudio`**<br/>*(Dev Tool số hóa hình học)* | **CÓ**<br/>*(Làm giấy can)* | • Giao diện CAD chuyên biệt (`Tool Rail 52px · Canvas · Inspector 320px`) để đồ nét hình học từ ảnh PNG ở góc nhìn phẳng `2D (0°)` và xem thử khối `3D (52°)`.<br/>• Vẽ mạng lưới Tường hợp nhất (`[W]`), tự động tách ô mặt phẳng khép kín (`ROOM` / `CORRIDOR` / `VOID`), gắn Cửa đục tường (`[D]`), và vẽ Đồ thị đường đi (`[N]`) $\rightarrow$ Xuất/Nhập file GeoJSON tĩnh. |
| **2. `ADMIN` trên `CampusMapView`**<br/>*(Quản trị Khu vực `Area` & Camera)* | **KHÔNG** | • Render **Vector 3D sạch** kèm ánh sáng định hướng MapLibre (`MAP_LIGHT`) và layer nhãn tên `Area` trên bề mặt phòng khi zoom cận ($\ge 18.2$): Sàn hành lang + Tường bao `2.85m` + Vách ngăn `1.35m` + Khối phòng 3D.<br/>• **Admin thực hiện toàn bộ việc tạo/sửa `Area`, chọn cấp độ an ninh, đặt ghim tọa độ ngay trên `CampusMapView`**. Phòng 3D nào chứa ghim `Area` sẽ tự động hiển thị màu cấp độ an ninh (`PUBLIC` Xanh, `CONFIDENTIAL` Vàng, `HIGHLY_CONFIDENTIAL` Đỏ) của `Area` đó; phòng chưa gán `Area` hiển thị màu xám trung tính.<br/>• **Lộ trình A\* 3D:** Tuyến dẫn đường hiển thị nổi trên mặt sàn từng tầng kèm cọc chuyển tầng (thay vì đường 2D dưới đất), đánh dấu điểm xuất phát bằng **biểu tượng người đi bộ** và điểm đến bằng **pin giọt nước**. |
| **3. `FACILITY_MANAGER` trên `CampusMapView`**<br/>*(Giám sát Vận hành & Sự cố)* | **KHÔNG** | • Các khối phòng bình thường hiển thị tông màu **trung tính dịu mắt (Slate/Blue-grey)** kèm ánh sáng 3D `MAP_LIGHT`, nhãn tên `Area` (khi zoom $\ge 18.2$) và dẫn đường A\* 3D (biểu tượng người đi bộ $\rightarrow$ pin giọt nước).<br/>• Chỉ làm nổi bật khối phòng của `Area` đang được chọn hoặc có **Sự cố an ninh (`SecurityIncident`)**. |
| **4. `GUARD` trên Mobile `GuardMapScreen`**<br/>*(Tác chiến & Dẫn đường Real-time)* | **KHÔNG** | • **Giao diện Tác chiến Tương phản cao (High-Contrast Tactical 3D)**: Sàn hành lang sáng rõ + Khối phòng tối giản + **Khối phòng của `Area` có báo động phát sáng Đỏ**.<br/>• Vẽ **vạch dẫn đường màu Cam đậm** từ vị trí (IPS/GPS) của Bảo vệ $\rightarrow$ sân trường $\rightarrow$ cầu thang $\rightarrow$ cửa phòng báo động. |

---

## 2. Cơ Chế Tường Hợp Nhất (`Unified Wall Graph`), Tách Mặt Phẳng (`PSLG/DCEL`) & Render Khối 3D

Từ mạng lưới tường vẽ trong `FloorDigitizerStudio`, thuật toán **Planar Straight-Line Graph / Half-Edge DCEL (`extractPlanarFacesFromWalls`)** tự động tìm giao điểm đoạn thẳng, cắt đoạn tại ngã ba/ngã tư, khử nhánh cụt (dangling spurs) và trích xuất toàn bộ các **ô mặt phẳng khép kín (`Planar Faces`)** kèm cấu trúc lỗ lồng nhau (`holes` cho giếng trời / sân trong / phòng nằm trong hành lang). Mỗi tầng được dựng theo **cao độ thực (`3.5m` / tầng: Tầng Trệt `0–3.5m`, Tầng 1 `3.5–7.0m`, Tầng 2 `7.0–10.5m`)**:

1. **Ba Vai Trò Mặt Phẳng Khép Kín (`ROOM` · `CORRIDOR` · `VOID`)**:
   - Click chọn bất kỳ ô khép kín nào để gán vai trò: **`ROOM`** (Mặt sàn phòng 3D), **`CORRIDOR`** (Sàn hành lang liên thông), hoặc **`VOID`** (Giếng trời / Sân trong trống không đổ sàn).
   - **Sàn Tầng & Hành Lang (`Floor Slab` - dày `0.16m`)**: Tự động hợp nhất các mặt phẳng không phải `VOID` (có đục lỗ `holes` tại các ô `VOID` như sân trong Hồ Sen) để tạo mặt sàn kiến trúc chuẩn xác.
   - **Auto-Clip Starter Geometry**: Toàn bộ đa giác phòng mẫu (`buildingRooms`) tự động được cắt gọn (`clipPolygonToBoundary`) theo đúng đường bao ngoài của tòa nhà, đảm bảo không có đỉnh phòng nào trồi ra ngoài phạm vi tường bao.
2. **Tường Bao Ngoài & Vách Ngăn Nội Bộ (`Perimeter vs Interior Partition Walls` — Đặc 100% `opacity: 1.0`)**:
   - **Tường Bao Ngoài (`Perimeter Wall` — cao `2.85m`, nửa bề rộng `0.16m`)**: Các đoạn tường tiếp giáp với không gian bên ngoài tòa nhà hoặc tiếp giáp với lỗ giếng trời (`VOID`) tự động dựng cao `2.85m` với nối góc vát liền mạch (miter + outer bevel cap).
   - **Vách Ngăn Nội Bộ (`Interior Partition Wall` — cao `1.35m`, nửa bề rộng `0.11m`)**: Các đoạn tường nằm giữa `ROOM–ROOM` hoặc `ROOM–CORRIDOR` tự động hạ thấp xuống `1.35m` (kiểu نیم-wall / cutaway chuẩn Mappedin) và render **đặc hoàn toàn (`fill-extrusion-opacity: 1.0`)** giúp nhìn xuyên suốt lòng phòng, nhãn tên `Area` và đường dẫn A\* khi xoay nghiêng `3D (52°)` mà không bị hiện tượng tường bán trong suốt chồng chéo.
3. **Cửa Gắn Tường (`Wall-Mounted Doors` — Đục Tường 3D Tự Động)**:
   - Mỗi đối tượng `DOOR` gắn trên đoạn tường tự động khoét khoảng mở (`cutout interval` theo `widthMeters`) trên đoạn tường 3D tại đúng vị trí cửa.
4. **Khối Phòng 3D (`Room Polygons` - cao `0.52m`, khi chọn nhô lên `1.0m`) & Ghost Lower Floors**:
   - Khối phòng thấp hơn vách ngăn (`1.35m`) và tường bao (`2.85m`) để lộ rõ không gian nội thất và lộ trình dẫn đường.
   - Khi xem tầng trên (Tầng 1, Tầng 2), các tầng bên dưới giữ lại dưới dạng **Ghost mờ (`opacity ~0.16–0.20`)** và render trước lớp active để bảo toàn khối kiến trúc tổng thể.

---

## 3. Đặc Tả Chi Tiết Công Cụ Số Hóa Hình Học (`FloorDigitizerStudio` — Chuẩn CAD)

Trong `FloorDigitizerStudio`, giao diện bố trí theo chuẩn CAD **`Tool Rail dọc 52px` (phím 1 ký tự `V, B, P, S, W, D, N, T, C`) · `Canvas MapLibre` · `Contextual Inspector 320px`** (3 tab: *Thuộc tính*, *Lớp & Cây*, *Kiểm tra lỗi Validator*):

### 3.1. Bộ Công Cụ Vẽ Tường, Khép Ô Tự Động, Cắt Vách & Gắn Cửa
1. **Công cụ `Vẽ Tường` (`WALL_DRAW` — `Phím W`)**:
   - Vẽ chuỗi đoạn tường liên hoàn (`Chain Mode`); khi các đoạn tường giao nhau hoặc khép kín thành vòng, hệ thống tự động tách thành các ô mặt phẳng (`Planar Faces`) để chọn gán `ROOM` / `CORRIDOR` / `VOID`.
2. **Công cụ `Hộp 2-Click Xoay Theo Ảnh` (`ROOM_BOX` — `Phím B`)**:
   - Click 2 điểm chéo nhau để dựng ngay 4 bức tường khép kín xoay **song song tự động với góc nghiêng của bản vẽ PNG tầng hiện tại (`buildOrientedBoxRing`)**; giữ **`Shift`** khi kéo nếu muốn khóa hộp thẳng theo trục Đông–Tây / Bắc–Nam.
3. **Công cụ `Đa Giác Nhiều Đỉnh` (`ROOM_POLY` — `Phím P`)**:
   - Click từng đỉnh theo chiều kim đồng hồ trên ảnh PNG $\rightarrow$ Bấm `Enter` (hoặc click lại đỉnh đầu khi hiện vòng tròn xanh lá khép kín) để tạo vòng tường đa giác.
4. **Công cụ `Dao Cắt Tường / Chia Ô` (`ROOM_SLICE` — `Phím S`, chuẩn Mappedin)**:
   - Kẻ một đường cắt 2 điểm xuyên qua ô mặt phẳng hiện có để tự động chèn vách ngăn mới tại đúng 2 giao điểm cạnh và chia đôi ô mặt phẳng thành 2 phòng độc lập.
5. **Công cụ `Gắn Cửa Lên Tường` (`DOOR_TOOL` — `Phím D`)**:
   - Rê chuột dọc theo bất kỳ đoạn tường nào (hiển thị hộp cửa chữ nhật live-preview trượt theo góc tường) và click để gắn Cửa (`DOOR`) trực tiếp lên vách tường.
   - **Hình học Cửa 2D & 3D (`buildOrientedDoorGeometry`)**: Trên 2D render thành **hộp chữ nhật xoay theo góc tường** (nền sáng `#fff8f6`, viền đỏ cam `#e11d48`) kèm chấm tâm trung điểm (`DOOR_MIDPOINT`); trên 3D vừa đục tường vừa dựng **ngưỡng cửa (`0.055m`)** và **lanh-tô trên đỉnh cửa tường bao (`2.10m – 2.85m`)**.
   - **Bề rộng linh hoạt (`0.5m – 5.0m`)**: Khi chọn Cửa, cho phép kéo trực tiếp **2 chốt mép cửa (`Left/Right Jamb Handles`)** trên bản đồ 2D để co giãn độ rộng, kéo **chốt tâm (`Midpoint Handle`)** để trượt vị trí cửa dọc theo tường, hoặc chỉnh qua Slider + ô nhập số trong Inspector.

### 3.2. Hệ Thống Bắt Dính Pixel Màn Hình (`Screen-Pixel Snapping`), Hàn Đỉnh Chung (`Vertex Welding`) & Căn Chỉnh Ảnh
- **Bắt dính theo Pixel Màn Hình (`resolveBestSnapTarget`)**: Thay thế khoảng cách mét cố định bằng **bán kính pixel trên màn hình** (tự động thích ứng mọi mức zoom) theo thứ tự ưu tiên nghiêm ngặt: **`VERTEX` (`12px` — Đỉnh góc & Trung điểm Cửa)** $\rightarrow$ **`MIDPOINT` (`10px` — Trung điểm cạnh)** $\rightarrow$ **`EDGE` (`10px` — Chiếu vuông góc lên cạnh)**. Chỉ bắt dính vào các lớp đang hiển thị (`layerVisibility`), tự tắt khi ở chế độ `CALIBRATE_PNG` / `TEST_ROUTE`, và **giữ phím `Alt`** để tạm tắt bắt dính.
- **Khử Trùng Lặp Đỉnh & Hàn Góc Tường Khi Kéo (`deduplicateFloorVertices` & `dragWeldedWallVertices`)**:
  - Khử trùng lặp các chấm tròn đỉnh tường 2D (`studioVerticesGeoJSON` từ `zoom >= 16.5`) để mỗi giao điểm chỉ hiện 1 chấm duy nhất kèm bậc nối (`degree`).
  - Khi kéo 1 chốt góc trong công cụ Chọn (`V`), hệ thống **tự động di chuyển đồng thời toàn bộ các đầu tường hàn chung tại góc đó** (giữ phím **`Alt`** khi kéo nếu muốn tách rời 1 đầu tường riêng).
  - Tự động tô **màu Đỏ/Cam cảnh báo** tại các đầu tường hở (`DEAD_END`, chưa khép kín) và hiện **vòng tròn xanh lá (`#10b981`)** tại điểm xuất phát khi có thể khép vòng (`draftPoints.length >= 3`).
- **Căn chỉnh & Xoay ảnh PNG (`CALIBRATE_PNG` - `Phím C`, chuẩn Mappedin)**: Kéo độc lập **4 góc (`NW, NE, SE, SW`)** + **tay cầm di chuyển tâm** + **trục xoay (`Rotation Stalk`)** với live-preview 60fps.
- **Tiện ích Editor & Kiểm định (`Validator`)**: Tích hợp **Undo/Redo 50 bước (`Ctrl+Z` / `Ctrl+Shift+Z`)**, cảnh báo chưa lưu + `beforeunload` guard, bật/tắt & khóa từng lớp (`Layer Visibility & Lock`), và bộ **Validator tự động** phát hiện đầu tường hở chưa khép kín (`OPEN_WALL_ENDPOINT`), phòng chưa gắn cửa (`ROOM_NO_DOOR`), nút đồ thị mồ côi, hoặc thang thiếu tầng đối ứng.

---

### 3.3. Cách Khối Phòng 3D Tự Động Nhận Nghiệp Vụ `Area` Trên `CampusMapView` (Zero Config trong Dev Tool)
- Trong `FloorDigitizerStudio`, mỗi ô phòng chỉ lưu hình học thuần túy (`ring`, `holes`, `role: "ROOM"`).
- Khi hiển thị trên **`CampusMapView`**:
  - Admin dùng tính năng **"Thêm vùng mới"** / **"Đặt vị trí trên bản đồ"** sẵn có của `CampusMapView` để đặt ghim `Area` vào trong khối phòng 3D.
  - Hệ thống dùng phép kiểm tra hình học **`isPointInPolygonWithHoles`** (tọa độ `[centerLongitude, centerLatitude]` của `Area` nằm trong `ring` và không rơi vào `holes` của phòng 3D trên cùng tầng) để **tự động gắn màu `areaLevel` (`PUBLIC` / `CONFIDENTIAL` / `HIGHLY_CONFIDENTIAL`), tên `Area` và sự kiện click chọn `Area` cho khối phòng 3D đó**!

---

## 4. Đặc Tả Chi Tiết Đồ Thị Đường Đi Tinh Gọn (`Nav Graph` — `Phím N`), Đồng Bộ Portal & Định Tuyến Cửa Hai Mặt

1. **Tinh Gọn Danh Mục Điểm Nút (`NODE` + `STAIRS` / `ELEVATOR` Symbols + Wall `DOOR` Midpoint)**:
   - 🔵/🟢 **`NODE` (Nút đường đi tự suy luận hình học & Tự gộp khi kéo trùng)**: Chỉ dùng 1 công cụ `NODE` duy nhất cho mọi điểm đường đi và ngã rẽ; hệ thống tự động suy luận nút nằm **Trong nhà (`INDOOR` — Xanh dương)** hay **Ngoài sân (`OUTDOOR` — Xanh lá ở Tầng Trệt, có xét lỗ rỗng sân trong `VOID` như Hồ Sen)** dựa vào `isPointIndoorOnFloor`. Khi kéo thả 1 nút đồ thị đè lên nút khác (`mergeOrSplitNavNodeOnDrop`), hệ thống **tự động gộp 2 nút làm 1** và nối lại toàn bộ cạnh `navEdges`; khi thả lên giữa 1 cạnh `navEdge`, tự động chẻ cạnh đó thành ngã ba T-junction.
   - 🟣 **`STAIRS` (Cầu thang bộ)** & 🔷 **`ELEVATOR` (Thang máy — Icon Phương án C `#0284c7`)**: Render bằng **biểu tượng WebGL `symbol` nâng theo đúng cao độ mặt sàn tầng (`symbol-height-offset`)** kèm huy hiệu tầng kết nối (`↕ G·1·2`) ngay dưới icon trên 2D (không vẽ dây tím vắt chéo 2D gây rối mắt). Hỗ trợ **Đồng bộ thẳng trục 1-Click lên các tầng (`syncPortalAcrossBuildingFloors`)** ngay trong Inspector và hiển thị **bóng mờ thang tầng dưới (`Ghost Portal`)** để bắt dính tự động.
   - 🟠 **`DOOR` (Cửa gắn tường `[D]` — Trung điểm làm Node đồ thị & Kết nối A\* hai mặt)**:
     - Trung điểm của Cửa (`door.coords`) đóng vai trò là Node đồ thị bắt dính ưu tiên (`VERTEX` snap `12px`) để nối dây trực tiếp hoặc tự động chiếu vuông góc vào mạng lưới đường đi.
     - Đặt `DOOR` lên vách ngăn nội bộ sẽ tự động nối thông **Phòng $\leftrightarrow$ Hành lang** (hoặc **Phòng $\leftrightarrow$ Phòng**); đặt `DOOR` lên tường bao ngoài ở Tầng Trệt (`floorOrder === 0`) sẽ tự động nối thông hành lang trong nhà với mạng lưới đường đi ngoài sân (`OUTDOOR`).
2. **Quy Tắc Định Tuyến A\* (`computeDigitizedRoute`) & Chẩn Đoán Lỗi Trực Quan**:
   - **Chiếu vuông góc T-Junction (`connectCoordsToPool`)**: Điểm đầu/cuối hoặc trung điểm cửa phòng tự động chiếu vuông góc lên cạnh hành lang/đường đi gần nhất để tạo ngã ba ảo.
   - **Bảo vệ ranh giới `OUTDOOR` & Sân trong (`isPointIndoorOnFloor`)**: Phân biệt chính xác vùng ngoài sân và lỗ khoét sân trong (`VOID` / `Hồ Sen`) với mặt sàn trong nhà.
   - **Liên kết Xuyên Tầng cô lập theo Tòa (`portalGroupId` + `resolveNodeBuilding`)**: Chỉ nối trục dọc giữa các nút `STAIRS` / `ELEVATOR` có cùng `portalGroupId` và **thuộc cùng một tòa nhà**, với trọng số chuyển tầng thống nhất **`floorDiff * 18`**.
   - **Test tuyến A\* trực tiếp (`Phím T`) kèm Toast Chẩn Đoán**: Click 2 điểm bất kỳ trên bản đồ để kiểm tra đường đi ngắn nhất; nếu đồ thị bị đứt cụm hoặc thiếu cầu nối xuyên tầng/cửa chính, hệ thống hiển thị ngay Toast báo rõ nguyên nhân (`DISCONNECTED_GRAPH`, `MISSING_PORTAL_LINK`, `MISSING_ENTRANCE_LINK`) thay vì im lặng.
