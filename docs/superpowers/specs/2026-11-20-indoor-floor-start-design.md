# Thiết kế: Chọn tầng xuất phát kết hợp GPS `(x, y)` khi dẫn đường trong nhà

**Ngày:** 2026-11-20  
**Phạm vi:** Thay đổi nhỏ (`mobile/lib/features/map/`)  
**Phương án đã chọn:** Phương án 1 — Hỏi nhanh bằng Bottom Sheet 1 chạm khi bấm **Dẫn đường**

---

## 1. Mục tiêu & Lý do
- Thiết bị di động hiện chỉ có GPS 2D `(x, y)`, không có cảm biến xác định độ cao tầng (`z`) đáng tin cậy trong nhà.
- Hiện tại `planCampusRoute` đang có 2 hạn chế:
  1. Nếu GPS nằm trong tòa nhà (`gpsBuilding != null`), hệ thống bỏ qua `(x, y)` thật và ép xuất phát từ nút cầu thang/thang máy của tầng đang xem (`selectedFloorOrder`).
  2. Dễ nhầm lẫn giữa **tầng đang xem trên bản đồ** (thanh chọn tầng dọc `4, 3, 2, 1, G`) và **tầng bảo vệ thực sự đang đứng**.
- **Mục tiêu**: Tách bạch hoàn toàn hai khái niệm này và dùng `(x, y)` thật của GPS gắn vào đúng tầng bảo vệ chọn.

## 2. Giới hạn phạm vi (Non-goals & Anti-goals)
- **Không** dùng Barometer, BLE Beacon hay Wi-Fi RTT.
- **Không** làm phình `AreaDetailCard` bằng các hàng chip chọn tầng thường trực.
- **Anti-goal**: Khi bảo vệ đang đứng ngoài sân, tuyệt đối không bắt chọn tầng thừa; khi đang mở xem Tầng 4 trên bản đồ để kiểm tra sự cố, không lấy nhầm Tầng 4 làm nơi xuất phát nếu bảo vệ đang ở Tầng 1 hoặc ngoài sân.

## 3. Ràng buộc kỹ thuật
- **Ngoài sân (`gpsBuilding == null` và gần khuôn viên)**: Tự động coi là `Tầng G` (`floorOrder = 0`), dẫn đường ngay lập tức (0 chạm thêm).
- **Trong tòa nhà (`gpsBuilding != null`) hoặc mất/không có GPS**: Bắt buộc hỏi tầng xuất phát trước khi tính tuyến.
- **Real-time GPS**: Khi đang dẫn đường, `MapProvider` giữ nguyên tầng xuất phát đã chọn (`navigationStartFloorOrder`) để `updateGuardLocation` tái tính tuyến mượt khi bảo vệ di chuyển $\ge 1\,\text{m}$, không hỏi lại liên tục.

## 4. Các phương án đã cân nhắc
1. **Phương án 1 (Đã chọn)**: Bottom Sheet 1 chạm khi bấm **Dẫn đường** (nếu GPS trong tòa nhà hoặc không có GPS). Gọn dock, tách biệt hoàn toàn tầng xem và tầng đứng, có nút phụ `"Ngoài sân (Tầng G)"` phòng trường hợp GPS trôi sai số vào trong tòa.
2. **Phương án 2**: Hàng chip chọn tầng đứng đặt sẵn trên `AreaDetailCard` — bị loại vì làm thẻ dưới lớn lên và dễ quên chỉnh trước khi bấm Dẫn đường.
3. **Phương án 3**: Kết hợp PA1 + nhớ tầng trong phiên và sửa nhanh trên dock — chưa cần thiết ở bước này để giữ trạng thái đơn giản, dễ kiểm soát.

## 5. Thiết kế chi tiết
1. **`planCampusRoute` (`campus_route_planner.dart`)**:
   - Nhận tham số tách biệt `guardFloorOrder` (tầng bảo vệ đang đứng, `int?`) và giữ `gps` `(x, y)`.
   - Khi `outdoorStart != null` (GPS ngoài sân): giữ nguyên `CampusRouteStartMode.outdoorGps` (`floorOrder = 0`), dẫn đường ngay.
   - Khi `gpsBuilding != null` và có `gps` + `guardFloorOrder`:
     - Tìm nút trong nhà gần tọa độ `(x, y)` của GPS nhất trên đúng `guardFloorOrder` thuộc `gpsBuilding` (ưu tiên nút hành lang/nút thường, nối đoạn thẳng từ `(x, y)` tới nút đó nếu $> 0.5\,\text{m}$).
     - `startPoint` dùng đúng tọa độ GPS `(x, y)` trên `guardFloorOrder`.
   - Khi chọn `"Ngoài sân (Tầng G)"` từ sheet (trường hợp GPS bị trôi vào footprint tòa nhà): ép xuất phát từ nút ngoài trời gần `(x, y)` nhất ở `floorOrder = 0` (hoặc fallback nếu không có GPS).
   - Khi không có GPS: xuất phát từ cầu thang/thang máy của `guardFloorOrder` (hoặc `selectedFloorOrder` như cũ).
2. **`MapProvider` (`map_provider.dart`)**:
   - Thêm helper kiểm tra nhanh trạng thái vị trí (`needsStartFloorPrompt`: `true` khi `guardLocation == null` hoặc `campus.buildingAt(...) != null`).
   - Lưu `_navigationStartBuilding` & `_navigationStartFloorOrder` khi gọi `calculateRouteTo(destination, startFloorOrder: ..., forceOutdoorStart: ...)`; dùng lại giá trị này trong `updateGuardLocation` cho tới khi `clearRoute()`.
3. **`GuardMapScreen` (`guard_map_screen.dart`)**:
   - Thanh chọn tầng dọc (`4, 3, 2, 1, G`) **chỉ** đổi tầng đang xem (`selectFloor`), không ảnh hưởng tới logic nhận diện tầng đứng.
   - Khi bấm **"Dẫn đường"** (`onNavigateTap`):
     - Nếu `!mapProvider.needsStartFloorPrompt` (GPS ngoài sân): gọi `calculateRouteTo` trực tiếp → tự chuyển bản đồ về `Tầng G` và fit tuyến.
     - Nếu `mapProvider.needsStartFloorPrompt`: mở Bottom Sheet gọn *"Bạn đang ở tầng nào?"* với các nút `G`, `1`, `2`, `3`, `4` (theo danh sách tầng của tòa) + nút phụ `Ngoài sân (Tầng G)` → chọn 1 chạm → gọi `calculateRouteTo` → tự chuyển bản đồ về đúng tầng vừa chọn và fit tuyến.

## 6. Kiểm thử
- Cập nhật & bổ sung unit test trong `campus_route_planner_test.dart` và `guard_map_route_card_test.dart` cho:
  - GPS trong tòa nhà + `guardFloorOrder` gắn đúng vào nút gần `(x, y)` nhất trên tầng đó.
  - GPS ngoài sân tự động chạy ngay ở `Tầng G` không cần hỏi.
  - Real-time `updateGuardLocation` giữ nguyên `navigationStartFloorOrder` đã chọn.
