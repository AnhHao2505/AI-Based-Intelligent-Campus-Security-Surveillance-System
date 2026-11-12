# Thiết Kế, Lộ Trình & Cách Kiểm Nghiệm: Bản Đồ 3D Đa Tòa/Đa Tầng (`Tòa Alpha` & `Tòa Epsilon`)

> [!IMPORTANT]
> **Nguyên tắc phân tách trách nhiệm (Separation of Concerns) đã chốt**:
> 1. **`FloorDigitizerStudio` (Dev Tool số hóa hình học — chỉ mở từ Admin)**:
>    - **Chỉ làm đúng 1 việc**: Dùng ảnh `.png` làm giấy can để vẽ **Hình học thuần túy (Pure Geometry)** theo mô hình **Đồ thị Tường Hợp Nhất (`Unified Wall Graph`)**: mạng lưới tường (`walls`), tự động trích xuất mặt phẳng khép kín (`Planar Faces`: `ROOM` / `CORRIDOR` / `VOID`), cửa gắn tường (`doors`) và đồ thị đường đi (`navNodes` + `navEdges`) $\rightarrow$ Lưu/Xuất ra kho hình học tĩnh (`STORAGE_KEY_3D_GEOMETRY = "icss_campus_3d_geometry_v4"` với hạt giống nhúng sẵn `DEFAULT_CAMPUS_GEOMETRY_V3`).
>    - **KHÔNG chứa bất kỳ cấu hình nghiệp vụ `Area` nào** (không chọn `PUBLIC`/`CONFIDENTIAL`/`HIGHLY_CONFIDENTIAL`, không gán `Area` DB, không đặt tên nghiệp vụ rườm rà trong Studio).
> 2. **`CampusMapView` (Màn hình `Quản lý khuôn viên` của Admin & FM — bố cục full-viewport)**:
>    - Render khối 3D từ kho hình học tĩnh đã số hóa (**không hiện ảnh PNG thô**; đã đồng bộ tên điều hướng Sidebar thành **"Quản lý khuôn viên"** ở cả Admin và FM, bỏ `PageHeader` để ưu tiên không gian bản đồ 3D).
>    - **Mọi nghiệp vụ cấu hình `Area`** (Tạo vùng, Đặt tên khu vực, Chọn cấp độ an ninh Công khai/Bảo mật/Bảo mật cao, Gắn tọa độ block 3D) vẫn do **Admin thực hiện hoàn toàn trên `CampusMapView`** (kèm trạng thái `"Chưa gắn block 3D"` khi `Area` chưa có tọa độ trong khối phòng 3D). Khi một `Area` có tọa độ `(centerLatitude, centerLongitude)` nằm trong đa giác của khối phòng 3D nào (kiểm tra bằng `isPointInPolygonWithHoles` có loại trừ lỗ `holes`), khối phòng 3D đó sẽ tự động lấy màu cấp độ an ninh và nhãn Sprite `@2x` của `Area` đó.

---

## 1. Bốn Giao Diện, Chế Độ Render 0-Pin & Quy Tắc Toàn Vẹn Nghiệp Vụ `Area`

### 1.1. Bảng Tòa/Tầng Chuẩn Hóa
- **`Tòa Alpha`** (alias tương thích ngược `Tòa HCM`): **3 tầng** (`floorOrder: 0..2` — `Tầng Trệt`, `Tầng 1`, `Tầng 2`).
- **`Tòa Epsilon`** (alias `Tòa Espilon`, `Tòa Beta`, `Tòa Tân Uyên`): **5 tầng** (`floorOrder: 0..4` — `Tầng Trệt`, `Tầng 1`, `Tầng 2`, `Tầng 3`, `Tầng 4`).

### 1.2. Ma Trận Giao Diện Theo Vai Trò
| Vai trò & Màn hình | Có hiện ảnh PNG? | Mục đích & Cách Render trên Bản đồ 3D |
| :--- | :---: | :--- |
| **1. `FloorDigitizerStudio`**<br/>*(Dev Tool số hóa hình học)* | **CÓ**<br/>*(Làm giấy can)* | • Giao diện CAD chuyên biệt (`Tool Rail 52px · Canvas · Inspector 320px`) để đồ nét hình học từ ảnh PNG ở góc nhìn phẳng `2D (0°)` và xem thử khối `3D (52°)`.<br/>• Vẽ mạng lưới Tường hợp nhất (`[W]`), tự động tách ô mặt phẳng khép kín (`ROOM` / `CORRIDOR` / `VOID`), gắn Cửa đục tường (`[D]`), và vẽ Đồ thị đường đi (`[N]`) $\rightarrow$ Lưu kho v4 / Xuất-Nhập JSON. |
| **2. `ADMIN` trên `CampusMapView`**<br/>*(Quản lý khuôn viên — Full Viewport)* | **KHÔNG** | • **Mô hình 0-Pin Indoor 3D**: Thay hoàn toàn các pin marker ghim truyền thống bằng hệ khối phòng 3D, nhãn tên Canvas Sprite `@2x` nổi trên phòng từ `minzoom >= 16.4` (`buildRoomLabelSpriteId`, `truncateLabelText`), tooltip khi rê chuột, và pill tòa nhà `.campus-bldg-pill` (click để zoom/focus tòa nhà).<br/>• **Gắn & Sửa `Area` theo block 3D**: Đặt hoặc chỉnh tọa độ `Area` bằng cách click chọn khối phòng 3D trên đúng tòa/tầng; khu vực chưa gán tọa độ hiển thị chip `"Chưa gắn block 3D"`. **Không gắn logic định tuyến A\* click-to-route trên `CampusMapView`** (dẫn đường A\* thực hiện tại `FloorDigitizerStudio [T]` và màn hình Guard).<br/>• Render **Vector 3D sạch** (`PUBLIC` Xanh, `CONFIDENTIAL` Vàng, `HIGHLY_CONFIDENTIAL` Đỏ; phòng chưa gán `Area` màu xám trung tính). Giữ thanh lọc đơn gọn ngay trên `CampusMapView`. |
| **3. `FACILITY_MANAGER` trên `CampusMapView`**<br/>*(Quản lý khuôn viên — Giám sát Vận hành & Sự cố)* | **KHÔNG** | • Bố cục full-viewport đồng bộ với Admin; các khối phòng bình thường hiển thị tông màu **trung tính dịu mắt (Slate/Blue-grey)** kèm ánh sáng 3D `MAP_LIGHT`, nhãn Canvas Sprite `@2x` (`minzoom >= 16.4`) và pill tòa `.campus-bldg-pill`.<br/>• Chỉ làm nổi bật khối phòng của `Area` đang được chọn hoặc có **Sự cố an ninh (`SecurityIncident`)**. |
| **4. `GUARD` trên Mobile `GuardMapScreen`**<br/>*(Tác chiến & Dẫn đường Real-time)* | **KHÔNG** | • **Giao diện Tác chiến Tương phản cao (High-Contrast Tactical 3D)**: Sàn hành lang sáng rõ + Khối phòng tối giản + **Khối phòng của `Area` có báo động phát sáng Đỏ**.<br/>• Vẽ đường dẫn A\* 3D từ vị trí (IPS/GPS) của Bảo vệ $\rightarrow$ sân trường $\rightarrow$ cầu thang/thang máy $\rightarrow$ cửa phòng báo động. |

### 1.3. Quy Tắc Toàn Vẹn Nghiệp Vụ `Area` (Backend & Đồng Bộ UI)
- **Ràng buộc cặp tọa độ (`U1` — `ERR_AREA_006`)**: Khi tạo (`POST`) hoặc sửa (`PUT`), cặp `(centerLongitude, centerLatitude)` phải đi cùng nhau (cả hai có giá trị hợp lệ hoặc cả hai cùng `null`); gửi lệch nửa cặp sẽ bị Backend từ chối với HTTP `400 ERR_AREA_006`.
- **Ràng buộc tòa/tầng & Ngừng hoạt động (`2A` — `ERR_AREA_021`)**:
  - `floorId` phải khớp đúng với `building` và `floor` đang hoạt động; tầng hoặc tòa nhà bị vô hiệu hóa khi tạo/sửa sẽ trả `400 ERR_AREA_021`.
  - Deactivate / Restore khu vực yêu cầu nhất quán trạng thái tầng–tòa (tối ưu/ngưỡng xử lý chuẩn hóa), không cho kích hoạt lại khu vực trên tầng/tòa đang tắt.
- **Bảo vệ phân trang/sắp xếp & Auth Error (`AreaController`, `SecurityConfig`)**: `AreaController` áp dụng sort allowlist và clamp kích thước/chỉ số `page`; `SecurityConfig` trả JSON `401` mã hóa UTF-8 chuẩn hóa thay vì HTML redirect.

---

## 2. Cơ Chế Tường Hợp Nhất (`Unified Wall Graph`), Tách Mặt Phẳng (`PSLG/DCEL`) & Chế Độ Xem Bùng Tầng (`Exploded View`)

Từ mạng lưới tường vẽ trong `FloorDigitizerStudio`, thuật toán **Planar Straight-Line Graph / Half-Edge DCEL (`extractPlanarFacesFromWalls`)** tự động tìm giao điểm đoạn thẳng, cắt đoạn tại ngã ba/ngã tư, khử nhánh cụt (dangling spurs) và trích xuất toàn bộ các **ô mặt phẳng khép kín (`Planar Faces`)** kèm cấu trúc lỗ lồng nhau (`holes` cho giếng trời / sân trong). Độ cao mặc định chuẩn hóa mỗi tầng là `FLOOR_ELEVATION_STEP = 3.5m` (có thể giãn khi xem nhiều tầng):

1. **Ba Vai Trò Mặt Phẳng Khép Kín (`ROOM` · `CORRIDOR` · `VOID`)**:
   - Click chọn bất kỳ ô khép kín nào để gán vai trò: **`ROOM`** (Mặt sàn phòng 3D), **`CORRIDOR`** (Sàn hành lang liên thông), hoặc **`VOID`** (Giếng trời / Sân trong trống không đổ sàn).
   - **Sàn Tầng & Hành Lang (`Floor Slab` - dày `0.16m`)**: Tự động hợp nhất các mặt phẳng không phải `VOID` (có đục lỗ `holes` tại các ô `VOID` như sân trong Hồ Sen) để tạo mặt sàn kiến trúc chuẩn xác.
   - **Loại bỏ bệ/mái che (`plinthsGeoJSON` / `roofsGeoJSON`)**: Bỏ hoàn toàn bệ móng và mái phủ khối ngoài (`plinthsGeoJSON === undefined`, `roofsGeoJSON === undefined`) để nhìn rõ nội thất các tầng ở chế độ 3D.
2. **Tường Bao Ngoài & Vách Ngăn Nội Bộ (`Perimeter vs Interior Partition Walls` — Đặc 100% `opacity: 1.0`)**:
   - **Tường Bao Ngoài (`Perimeter Wall` — cao `2.85m` ở chế độ 1 tầng, vát cắt cutaway `BUILDING_ALL_FLOORS_WALL_HEIGHT = 2.45m` khi xem nhiều tầng `ALL` để giữ nội thất dễ đọc)**: Các đoạn tường tiếp giáp với không gian ngoài hoặc lỗ `VOID`.
   - **Vách Ngăn Nội Bộ (`Interior Partition Wall` — cao `1.35m`, nửa bề rộng `0.11m`)**: Tường giữa `ROOM–ROOM` hoặc `ROOM–CORRIDOR`, render đặc `fill-extrusion-opacity: 1.0`.
3. **Cửa Gắn Tường (`Wall-Mounted Doors` — Đục Tường 3D Tự Động)**:
   - Khoét khoảng mở thực tế trên tường (`buildWallsWithDoorCutouts`) theo `widthMeters`.
4. **Ba Chế Độ Hiển Thị & Bùng Tầng 3 Nấc (Exploded View Kiểu Cột Trục B)**:
   - Hỗ trợ 3 ngữ cảnh phạm vi trong `buildVector3dLayers`:
     1. **`ALL` toàn khuôn viên** (`selectedBuilding: "ALL"`, `activeFloorOrder: "ALL"`),
     2. **`Toàn tòa`** (chọn 1 tòa, `activeFloorOrder: "ALL"`),
     3. **`1 Tầng`** (chọn 1 tòa + 1 tầng cụ thể; các tầng dưới render dạng Ghost mỏng).
   - **Bùng tầng 3 nấc trên `CampusMapView` (`EXPLODED_FLOOR_STEP_OPTIONS`)**: **`Khép (3.5m)`** $\rightarrow$ **`Vừa (7.5m)`** $\rightarrow$ **`Giãn (10.5m)`** (giá trị không hợp lệ tự fallback về `3.5m`).
   - **Trục Kết Nối Dọc Kiểu Cột Trục B (`portalShaftsGeoJSON`)**: Khi ở chế độ nhiều tầng (`activeFloorOrder === "ALL"`) và bước tầng `> 3.5m` (`7.5m` hoặc `10.5m`), hệ thống tự động dựng trục cột 3D nối các cụm `STAIRS` (màu tím `#a855f7`) và `ELEVATOR` (màu xanh ngọc `#06b6d4`) cùng `portalGroupId` trong **cùng 1 tòa nhà** qua các tầng từ đáy slab tầng thấp nhất tới đỉnh slab tầng cao nhất (cô lập theo từng tòa, không dựng trục cho portal chỉ đứng 1 tầng). Khi gộp `Khép (3.5m)` hoặc xem `1 Tầng`, trục dọc tự động ẩn gọn.

---

## 3. Đặc Tả Chi Tiết Công Cụ Số Hóa Hình Học (`FloorDigitizerStudio` — Chuẩn CAD)

Trong `FloorDigitizerStudio`, giao diện bố trí theo chuẩn CAD **`Tool Rail dọc 52px` (phím 1 ký tự `V, B, P, S, W, D, N, T, C`) · `Canvas MapLibre` · `Contextual Inspector 320px`** (3 tab: *Thuộc tính*, *Lớp & Cây*, *Kiểm tra lỗi Validator*):

### 3.1. Bộ Công Cụ Vẽ Tường, Chia Ô, Gắn Cửa 3 Thành Phần & Chuyển Công Cụ An Toàn
1. **`Vẽ Tường` (`WALL_DRAW` — `Phím W`)**: Vẽ chuỗi đoạn tường liên hoàn (`Chain Mode`); khi khép kín hoặc cắt nhau sẽ tự động tách ô mặt phẳng (`Planar Faces`).
2. **`Hộp 2-Click Xoay Theo Ảnh` (`ROOM_BOX` — `Phím B`)**: Tự xoay song song theo góc bản vẽ PNG tầng (`buildOrientedBoxRing`); giữ **`Shift`** để khóa thẳng trục.
3. **`Đa Giác Nhiều Đỉnh` (`ROOM_POLY` — `Phím P`)**: Click các đỉnh và kết thúc để tạo vòng tường đa giác.
4. **`Dao Cắt Tường / Chia Ô` (`ROOM_SLICE` — `Phím S`)**: Kẻ đường cắt 2 điểm xuyên ô để chèn vách ngăn tại giao điểm và chia đôi phòng.
5. **Chuyển Công Cụ Tự Lưu Nháp (`resolveToolSwitchDraftAction`) & Chống Zoom Nhầm (`doubleClickZoom` Off)**:
   - Khi đang vẽ dở trong `WALL_DRAW` ($\ge 2$ điểm) hoặc `ROOM_POLY` ($\ge 3$ điểm) mà chuyển sang công cụ khác, hệ thống tự động **`COMMIT`** đoạn tường/đa giác đang vẽ thay vì làm mất công vẽ (trường hợp dưới ngưỡng tối thiểu sẽ `DISCARD`; bấm lại cùng công cụ đang chọn là `NOOP`).
   - Tắt `doubleClickZoom` của MapLibre trên Canvas Studio để click-đúp kết thúc nét vẽ CAD không làm giật mức zoom.
6. **`Gắn Cửa Lên Tường` (`DOOR_TOOL` — `Phím D`) — Cửa 3 Thành Phần 3D (`DOOR_THRESHOLD`, `DOOR_JAMB`, `DOOR_LINTEL`)**:
   - Trên 2D render thành hộp chữ nhật xoay theo góc tường (`buildOrientedDoorGeometry`) kèm chấm tâm (`DOOR_MIDPOINT`); hỗ trợ kéo 2 chốt mép (`Left/Right Jamb Handles`) chỉnh bề rộng **`0.5m – 10.0m`** và kéo chốt tâm trượt dọc tường.
   - Trên 3D (hiển thị đầy đủ ngay cả khi xem thử 3D trong `FloorDigitizerStudio` lẫn trên `CampusMapView`), mỗi Cửa dựng đồng bộ 3 khối 3D: **`DOOR_THRESHOLD`** (ngưỡng cửa dưới sàn), **`DOOR_JAMB`** (2 thanh khung đứng hai bên mép cửa), và **`DOOR_LINTEL`** (lanh-tô trên đỉnh cửa đồng màu ngưỡng `#f59e0b`).

### 3.2. Bắt Dính Pixel Màn Hình, Chọn Ô Hẹp, Kéo Đỉnh Chống Rung & Validator Tinh Gọn
- **Bắt dính theo Pixel Màn Hình (`resolveBestSnapTarget`)**: Thứ tự ưu tiên **`VERTEX` (`12px`)** $\rightarrow$ **`MIDPOINT` (`10px`)** $\rightarrow$ **`EDGE` (`10px`)**; giữ **`Alt`** để tạm tắt bắt dính.
- **Khắc Phục Chọn Phòng/Hành Lang Hẹp (`resolveStudioSelectEntity`)**:
  - Sắp thứ tự mặt phẳng theo **`areaSqm` tăng dần** (ô nhỏ lọt trong ô lớn được ưu tiên trúng trước) với tọa độ làm tròn **`toFixed(7)`**, mở rộng vùng hit-test tường `8px` để giữ chính xác khi click sát vách mà vẫn chọn trúng **`FACE`** khi click giữa phòng/hành lang hẹp.
  - Bỏ qua các lớp đang khóa (`lockedLayers`), hỗ trợ `Shift` multi-select luôn ưu tiên `FACE`.
- **Chống Giật/Rung Khi Kéo Đỉnh Tường & Cửa (`collectWeldedDragExclusion`, `dragPan` Lock, `rAF`)**:
  - Gom toàn bộ nhóm tường hàn chung tại đỉnh đang kéo (`collectWeldedDragExclusion`) để loại trừ khỏi mục tiêu bắt dính khi kéo (trừ khi giữ `Alt` để tách rời 1 đầu tường `detachSingle`), tránh đầu tường tự hút ngược lại chính nó gây rung ở cự ly `1–10px`.
  - Khóa `dragPan` của bản đồ trong suốt lúc kéo đỉnh tường/cửa và gom cập nhật render qua `requestAnimationFrame` (`rAF`).
- **Tiện ích Editor & Kiểm Định (`Validator`)**:
  - Undo/Redo 50 bước, `beforeunload` guard, Layer Visibility & Lock.
  - Bộ **Validator tự động (`auditNavigationConnectivity`)**: giữ kiểm tra đầu tường hở (`OPEN_WALL_ENDPOINT`), phòng chưa gắn cửa (`ROOM_NO_DOOR`), nút đồ thị mồ côi, cổng chuyển tầng thiếu tầng hoặc thiếu cạnh hành lang (`PORTAL_NO_FLOOR_EDGE`), và **cảnh báo cạnh nối trực tiếp portal-to-portal (`PORTAL_DIRECT_PORTAL_EDGE`)**. **Đã gỡ bỏ cảnh báo `DOOR_NO_GRAPH_LINK`** (cửa chưa nối dây `[N]`) vì A\* tự động nối xuyên cửa hai mặt bằng khoảng mở vật lý mà không bắt buộc phải nối dây thủ công vào tâm mọi cửa.

---

## 4. Đặc Tả Đồ Thị Đường Đi (`Nav Graph`), Cô Lập Portal & Mô Phỏng Playback 3D Bám Hướng

1. **Tinh Gọn Danh Mục Điểm Nút (`NODE` + `STAIRS` / `ELEVATOR` + `DOOR` Midpoint)**:
   - 🔵/🟢 **`NODE`**: Tự động phân biệt Trong nhà / Ngoài sân (`isNodeOutdoor`, xét lỗ `VOID` qua `isPointInRingStrict`); gộp đỉnh trùng khi kéo thả (`mergeOrSplitNavNodeOnDrop`).
   - 🟣 **`STAIRS`** & 🔷 **`ELEVATOR`**: Đồng bộ trục thẳng 1-Click lên các tầng (`syncPortalAcrossBuildingFloors`), giữ bóng mờ tầng dưới (`Ghost Portal`) để căn trục thẳng hàng theo đúng `[lng, lat]`.
   - **Cô Lập Tuyệt Đối Portal-to-Portal (`!isPortalNode(n)`, `sanitizePortalNavEdges`, `PORTAL_DIRECT_PORTAL_EDGE`)**: Không tự động nối cạnh trực tiếp giữa hai portal (`STAIRS ↔ STAIRS` khác nhóm hoặc `STAIRS ↔ ELEVATOR`) cả trong auto-link hành lang lẫn thao tác kéo-thả/lưu kho; `sanitizePortalNavEdges` tự động loại bỏ cạnh portal-to-portal và self-loop trước khi lưu và khi nạp kho dữ liệu.
   - 🟠 **`DOOR` Midpoint**: Kết nối A\* hai mặt tự động giữa Phòng $\leftrightarrow$ Hành lang (hoặc Phòng $\leftrightarrow$ Phòng, Hành lang $\leftrightarrow$ Ngoài sân Tầng Trệt).
2. **Quy Tắc Định Tuyến A\* (`computeDigitizedRoute`), Test Tuyến `[T]` & Playback 3D Bám Hướng**:
   - Chiếu vuông góc T-Junction (`connectCoordsToPool`), định tuyến thẳng nội bộ trong cùng `ROOM`, ưu tiên cửa ngoại vi theo tòa (`outerGatewayDoorsByBldg`), đi vòng ngoài ranh giới khi outdoor thuần (`buildExteriorCampusCoords`), và đồng nhất trọng số chuyển tầng `floorDiff * 18` cho cả thang bộ lẫn thang máy (cô lập theo từng tòa).
   - **Mô Phỏng Playback Real-time Bám Hướng 3D (`samplePointAlongRoute` + `lerpBearingDeg`)**:
     - Tích hợp công tắc **`Bám hướng` (Chase-Cam Follow Toggle)**: Khi bật, camera 3D tự động xoay góc nhìn mượt theo hướng di chuyển qua phép nội suy góc cung ngắn nhất **`lerpBearingDeg`** (xử lý qua mốc `0°/360°`, giữ tích lũy hướng ổn định khi leo đoạn cầu thang dọc giữa các tầng).
     - Hiển thị mũi tên + vùng nón hướng nhìn (`FOV Cone`) trên cả bản đồ 2D lẫn 3D.
     - **Tách điều khiển khi người dùng tự kéo (`User-Drag Decoupling`)**: Ngay khi người dùng tự xoay/kéo bản đồ trong lúc Playback, chế độ bám camera tự nhường quyền điều khiển để không giằng giật góc nhìn khỏi tay người dùng.

---

## 5. Lưu Trữ Chung Team Qua Git (`Option A` — Hạt Giống Nhúng Sẵn Trong Repo Frontend)

Để toàn bộ team sau khi pull nhánh `main` đều thấy chung bản đồ 3D đã số hóa hoàn chỉnh mà không phụ thuộc cache riêng từng máy hay cần Backend API lưu hình học ngay ở giai đoạn hiện tại:

1. **Hạt Giống Hình Học Chuẩn Hóa (`frontend/src/data/defaultCampusGeometryV3.js`)**:
   - Đóng gói dữ liệu `epsilon (1)` chuẩn (bao gồm `Tòa Alpha` 3 tầng `0..2` và `Tòa Epsilon` 5 tầng `0..4`, tổng 404 tường, 162 phòng, 240 cửa, 433 nút đồ thị và 466 cạnh sạch) thành hằng số **`DEFAULT_CAMPUS_GEOMETRY_V3`** với `seedVersion = "2026-11-12-epsilon-1"`, chuẩn hóa khóa calibration `Tòa Alpha__0` / `Tòa Epsilon__0`.
   - Việc lưu dưới dạng JS module thay vì import `.json` trực tiếp bảo đảm tương thích đồng nhất giữa Vite (`build`), `oxlint` (`lint`), TypeScript JSDoc (`typecheck`) và trình chạy test Node ESM (`node --test`).
2. **Cơ Chế Nâng Phiên Bản Khóa `localStorage` (`STORAGE_KEY_3D_GEOMETRY`)**:
   - Trong `campusIndoor3dData.js`, `createDefaultGeometryStore()` trả về bản sao sâu (`structuredClone(DEFAULT_CAMPUS_GEOMETRY_V3)`) đã chạy `sanitizePortalNavEdges`.
   - Nâng khóa lưu trữ hoạt động lên **`STORAGE_KEY_3D_GEOMETRY = "icss_campus_3d_geometry_v4"`** (kèm `DEFAULT_GEOMETRY_SEED_VERSION`) và **không tự động import đè** các khóa cũ (`icss_campus_3d_geometry_v2`, `icss_campus_3d_geometry_v3`) lên bản phát hành mới. Nhờ đó mọi máy teammate (và máy hiện tại đang còn cache cũ) sẽ nạp ngay dữ liệu 3D chuẩn từ repo khi mở ứng dụng.
   - Khi người dùng chỉnh sửa và bấm Lưu trong `FloorDigitizerStudio`, `saveGeometryStore()` lưu bản sửa đổi vào `icss_campus_3d_geometry_v4` (kèm fallback về `createDefaultGeometryStore()` nếu cache bị lỗi/corrupt). Khi chốt phiên bản hình học mới về sau, chỉ cần cập nhật `DEFAULT_CAMPUS_GEOMETRY_V3` và tăng `STORAGE_KEY_3D_GEOMETRY` (hoặc `seedVersion`).
