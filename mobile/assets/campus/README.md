# Asset bản đồ khuôn viên cho app mobile

Thư mục này **sinh tự động** từ dữ liệu bản đồ của web (`frontend/src/data/defaultCampusGeometryV3.js`) bằng script
`frontend/scripts/export-campus-map.mjs`. **Không sửa tay** các file `.json` / `.geojson` ở đây.

## Nội dung

| File | Nội dung |
|---|---|
| `manifest.json` | Phiên bản seed (`seedVersion`), danh sách file kèm kích thước và sha256, số nút / cạnh đồ thị, số ca chuẩn, danh sách toà / tầng |
| `layers/<toà>__f<tầng>.geojson` | Khối 3D của một tầng (sàn, tường, phòng, cửa, ký hiệu cầu thang / thang máy) cho lớp `fill-extrusion` của MapLibre: `baseHeight` (chân, m), `extrudeHeight` (đỉnh, m), `color`, `layer`, `role` |
| `graph.json` | Đồ thị tìm đường: nút (`id`, `lon`, `lat`, `building`, `floorOrder`, `type`, `isOutdoor`) và cạnh (`a`, `b`, `lengthMeters`, `cost`) — chi phí đúng như web dùng |
| `golden-routes.json` | Bộ ca chuẩn (điểm đi, điểm đến, kết quả của web) để app mobile kiểm thuật toán cho ra cùng đường |

## Quy trình phát hành khi sửa bản đồ (người sửa: Gia Anh)

1. Sửa trong **Floor Digitizer Studio** trên web, bấm **Lưu** (chỉ lưu trên trình duyệt của máy đó).
2. Bấm **Xuất GeoJSON** để tải file JSON.
3. Cập nhật `frontend/src/data/defaultCampusGeometryV3.js` bằng nội dung vừa xuất, **tăng `seedVersion`**; commit.
4. Sinh lại asset cho mobile:

   ```
   cd frontend
   npm run export:campus-map
   ```

5. Commit **cả** thư mục `mobile/assets/campus/` cùng commit / PR với seed.

CI (job `frontend-check`, bước "Check mobile campus map assets") chạy lại script vào thư mục tạm và so với thư mục này.
**Quên bước 4–5 thì CI fail** với thông báo: "Chạy: cd frontend && npm run export:campus-map và commit mobile/assets/campus."

## Ghi chú

- Script chạy hai lần cho ra file giống hệt (không ghi thời điểm sinh, sắp xếp cố định, toạ độ làm tròn 7 chữ số, số đo 3 chữ số).
- Khu vực (area) không nằm trong asset: app lấy từ API `GET /api/areas/map-pins` lúc chạy và tô màu phòng theo khu.
- Kiểm tra nhanh đồ thị (liên thông, khu vực tới được từ ngoài sân, thời gian tìm đường): `node scripts/export-campus-map.mjs --report`.
