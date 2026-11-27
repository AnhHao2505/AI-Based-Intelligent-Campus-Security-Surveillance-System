# Thiết kế Kỹ thuật: Cảnh báo Camera Livestream Mất Kết Nối Đột Ngột (Video Loss Alert)

- **Ngày tạo**: 2026-11-27
- **Trạng thái**: Đã triển khai (Implemented)
- **Tác giả**: Antigravity & User
- **Phạm vi**: `backend` (Spring Boot), `frontend` (React), `mobile` (Flutter)

---

## 1. Mục đích & Bối cảnh (Intent & Why)

Khi một camera giám sát đang hoạt động (`ACTIVE` và đang `ONLINE`) bị mất luồng video đột ngột (đứt cáp, mất nguồn, treo RTSP camera, sập trạm biên), hệ thống hiện tại đã tự động cập nhật `operationalStatus` sang `OFFLINE` qua chu kỳ quét 15 giây tại `CameraService.syncLiveOperationalStatuses()`, nhưng **hoàn toàn im lặng** — không phát bất kỳ thông báo nào.

Hệ quả:
- **Quản lý CSVC (`FACILITY_MANAGER`) và Quản trị viên (`ADMIN`)** không biết đang có điểm mù an ninh để kiểm tra hạ tầng hoặc kết nối lại luồng camera.
- **Bảo vệ trực màn hình livestream (`/guard` trên Web)** nếu đang lọc theo toà/tầng khác hoặc ở trang lưới khác sẽ không thấy ô camera bị rớt tín hiệu.
- **Bảo vệ đang đi tuần trên Mobile (`GUARD` đang `CHECKED_IN`)** không biết khu vực thuộc ca trực của mình vừa mất góc nhìn giám sát.

Tính năng này bổ sung cơ chế **Cảnh báo mất kết nối camera (Video Loss)** chuẩn theo mô hình các hệ thống VMS lớn: phát hiện từ phía Server/Gateway (tránh báo giả do rớt Wi-Fi trình duyệt người xem), có độ trễ xác nhận chống nháy mạng, phân biệt rõ tắt chủ động với đứt đột ngột, và gửi thông báo vận hành gọn nhẹ (không tạo phiếu sự cố an ninh).

---

## 2. Mục tiêu, Ngoài phạm vi & Điều cấm (Goals, Non-Goals, Anti-Goals)

### 2.1 Goals (Mục tiêu)
- Phát hiện chính xác sự kiện camera `ACTIVE` chuyển từ `ONLINE` sang `OFFLINE` ngoài ý muốn và duy trì trạng thái mất kết nối liên tục đủ **45 giây** (3 nhịp quét 15 giây).
- Gửi thông báo In-App (lưu bảng `notifications`) kèm toast thời gian thực trên Web cho **`FACILITY_MANAGER`**, **`ADMIN`** và **`GUARD` đang trực**.
- Cập nhật tức thì trạng thái ô camera sang `OFFLINE` (và `ONLINE` khi hồi phục) trên màn hình livestream `/guard` kể cả khi bảo vệ đang xem tầng/trang khác.
- Đẩy thông báo hệ thống (Local Notification) qua kênh STOMP riêng xuống điện thoại của **bảo vệ đang `CHECKED_IN`** tại toà nhà của camera đó (fallback toàn bộ bảo vệ đang `CHECKED_IN` nếu toà đó không có ca trực hoặc camera chưa gắn toà), chỉ để nắm thông tin (không cần tiếp nhận hay lập biên bản).
- Tự động gỡ thông báo trên điện thoại bảo vệ (và đóng toast lỗi trên Web) ngay khi camera kết nối `ONLINE` trở lại.

### 2.2 Non-Goals (Ngoài phạm vi)
- **Không** tạo bản ghi `SecurityIncident`, không đưa vào luồng điều phối nhận ca (`claim`), xử lý hiện trường (`resolve`) hay leo thang sự cố (`IncidentEscalationJob`).
- **Không** dùng FCM / APNs push từ đám mây (giữ nguyên giới hạn kiến trúc hiện tại của mobile: nhận qua STOMP kết hợp `GuardDutyService` foreground service trên Android).
- **Không** tạo bảng lịch sử thời gian chết (`camera_outages`) hay màn hình báo cáo SLA hạ tầng camera.
- **Không** tạo màn hình danh sách thông báo hay màn xem livestream mới trên ứng dụng Mobile.

### 2.3 Anti-Goals (Điều cấm)
- **Cấm phát cảnh báo dựa trên lỗi WebRTC cục bộ của trình duyệt** (`WebRtcPlayer` rớt mạng máy trạm canh gác không được làm nổ thông báo cho FM và điện thoại bảo vệ; ô video chỉ hiển thị trạng thái mất tín hiệu cục bộ tại chỗ).
- **Cấm báo động khi ngắt chủ động:** Các thao tác `stopCameraStream`, vô hiệu hoá camera, xoá mềm hoặc ngừng sử dụng (`DECOMMISSIONED`) không được sinh thông báo mất kết nối.
- **Cấm spam lặp lại:** Mỗi đợt mất kết nối của một camera chỉ được gửi **tối đa 1 thông báo** cho tới khi camera đó thực sự `ONLINE` trở lại.
- **Cấm làm ảnh hưởng luồng sự cố an ninh trên Mobile:** Không gửi bản tin trạng thái camera vào topic `/topic/guards/{id}/alerts` đang parse cứng `IncidentModel.fromJson`.

---

## 3. Ràng buộc hệ thống hiện có (Constraints)

1. **Quyền quản trị Camera (`CameraController`):** Chỉ `ADMIN` có quyền cấu hình và kết nối lại stream camera; `FACILITY_MANAGER` quản lý khu vực; `GUARD` giám sát màn `/guard` và tuần tra mobile. Do đó trên Web cả 3 vai trò này đều cần nhận cảnh báo theo phạm vi tương ứng.
2. **Ràng buộc `CHECK` trong PostgreSQL (`notifications`):** Bảng `notifications` có constraint `chk_notifications_type` (cập nhật gần nhất ở `V111` với 28 loại). Thêm loại thông báo mới bắt buộc phải có Flyway migration mới (`V151`) giữ nguyên toàn bộ 28 loại cũ và thêm loại mới.
3. **Quy tắc lọc `ADMIN` (`BR-NT-26` trong `InAppNotificationService.adminExcluded`):** Hàm `adminExcluded` chỉ chặn `ADMIN` với `referenceType` là `ACCESS_REQUEST` hoặc `GUEST_VISIT`. Với `referenceType = "CAMERA"`, `ADMIN` được phép nhận bình thường.
4. **Phân quyền STOMP (`WebSocketAuthChannelInterceptor`):**
   - Mọi topic `/topic/**` (ngoài `/topic/guards/{uuid}/**`) đã cho phép `ROLE_ADMIN`, `ROLE_FACILITY_MANAGER`, `ROLE_GUARD` subscribe.
   - Pattern `^/topic/guards/{uuid}(/.*)?$` đã cho phép chính chủ `ROLE_GUARD` (và overseer `ADMIN`/`FM`) subscribe mọi nhánh con, nên topic `/topic/guards/{guardId}/camera-alerts` tương thích sẵn mà không cần mở lỗ hổng phân quyền mới.
5. **Quy tắc Frontend CSS (`AGENTS.md`):** Nếu phát sinh style mới trên Web, bắt buộc đặt trong `frontend/src/styles/`, không tạo file `.css` rải rác trong `components/` hoặc `pages/`.

---

## 4. Các phương án đã cân nhắc (Approaches)

### Phương án 1: Theo dõi rớt mạng gọn nhẹ trong bộ nhớ (In-Memory Tracker) + Kênh STOMP kỹ thuật riêng ⭐ [ĐƯỢC CHỌN]
- **Mô tả:** Bám vào `CameraService.syncLiveOperationalStatuses()` (chu kỳ 15s, đã hội tụ cả MediaMTX Local và Edge Heartbeat). Dùng `CameraHealthAlertService` giữ trạng thái rớt mạng trong bộ nhớ (`ConcurrentHashMap<UUID, CameraOutageState>`), đánh dấu tắt chủ động từ `CameraStreamLifecycleService`. Đủ 45s `OFFLINE` liên tục thì ghi `InAppNotification` (`CAMERA_OFFLINE`) và phát STOMP qua `/topic/cameras/status-alerts` (Web) cùng `/topic/guards/{guardId}/camera-alerts` (Mobile). Khi `ONLINE` lại thì phát tin thu hồi để tự gỡ thông báo trên mobile.
- **Đánh đổi:** Rất gọn, chỉ cần 1 migration cập nhật `chk_notifications_type`, tách biệt hoàn toàn với 221 test sự cố mobile. Đổi lại, nếu điện thoại bảo vệ đang đứt mạng đúng lúc bắn tin STOMP (hoặc mới vào ca sau mốc 45s) sẽ không nhận bù thông báo đẩy của đợt rớt trước đó, và nếu Backend restart đúng giữa 45s đếm ngược thì bộ đếm tính lại.
- **Lý do chọn:** Đúng tinh thần thông báo vận hành gọn nhẹ đã chốt, tận dụng tối đa hạ tầng sẵn có và giảm thiểu rủi ro hồi quy.

### Phương án 2: Lưu vòng đời mất kết nối vào bảng DB (`camera_outages`) + Đồng bộ bù cho Mobile
- **Mô tả:** Tạo thêm bảng `camera_outages` lưu từng đợt mất kết nối (`offline_since`, `notified_at`, `recovered_at`, `is_planned`) và mở thêm REST endpoint để Mobile gọi trong callback `StompService.onReconnected` nhằm hiện bù thông báo bị lỡ lúc mất mạng.
- **Đánh đổi:** Chịu lỗi tuyệt đối qua các lần restart Backend và bù được tin STOMP khi điện thoại rớt mạng, đồng thời có dữ liệu thống kê downtime. Tuy nhiên tốn thêm bảng DB, repository và luồng đồng bộ API trên mobile (tăng ~35–40% khối lượng code) trong khi chưa có yêu cầu báo cáo SLA hạ tầng.

---

## 5. Thiết kế chi tiết (Detailed Design)

### 5.1 Các quyết định đã thống nhất với người dùng
1. **Bản chất thông báo trên Mobile:** Bảo vệ đang trực chỉ nhận thông báo hệ thống để biết thông tin, không có hành động tiếp nhận/xử lý.
2. **Người nhận trên Web:**
   - Mọi `FACILITY_MANAGER` đang hoạt động (`isActive = true`, `deletedAt == null`).
   - Mọi `ADMIN` đang hoạt động.
   - Các `GUARD` đang trực (vừa lưu vào chuông thông báo, vừa hiện toast nổi và cập nhật ô lưới trên màn hình livestream `/guard`).
3. **Người nhận trên Mobile:**
   - Bảo vệ đang `CHECKED_IN` trong khung giờ ca trực thuộc **toà nhà của camera** bị mất kết nối.
   - Nếu camera chưa gắn toà nhà hoặc toà nhà đó hiện không có bảo vệ nào đang `CHECKED_IN`, fallback gửi cho **toàn bộ bảo vệ đang `CHECKED_IN`** toàn khuôn viên.
4. **Ngưỡng chống báo giả & thu hồi:**
   - Ngưỡng xác nhận mất kết nối liên tục: **45 giây** (3 nhịp quét 15s liên tiếp).
   - Bỏ qua khi tắt stream chủ động.
   - Chỉ báo **1 lần duy nhất** mỗi đợt mất kết nối.
   - Khi camera `ONLINE` trở lại (sau khi đã từng phát cảnh báo `CAMERA_OFFLINE` cho đợt đó): tự động gỡ thông báo khỏi khay thông báo trên điện thoại bảo vệ.
5. **Hành vi màn hình livestream `/guard`:**
   - Nhận cảnh báo STOMP để hiện toast + cập nhật chuông + đổi trạng thái ô camera sang `OFFLINE` tức thì kể cả khi đang lọc tầng/trang khác.
   - Khi `WebRtcPlayer` mất kết nối cục bộ, chỉ hiện trạng thái mất tín hiệu trên ô phát đó; việc phát thông báo hệ thống vẫn chỉ dựa trên xác nhận từ Backend.

### 5.2 Chi tiết kỹ thuật các thành phần (Đề xuất kỹ thuật của người thiết kế)

#### A. Cơ sở dữ liệu & Enum (`backend`)
- **Migration `V151__notification_camera_offline.sql`:**
  1. `DROP CONSTRAINT IF EXISTS chk_notifications_type` và `ADD CONSTRAINT chk_notifications_type` giữ đủ 28 giá trị của `V111` cộng thêm `'CAMERA_OFFLINE'`.
  2. Thêm cấu hình động `NOTIFICATION_CAMERA_OFFLINE_SECONDS` vào bảng `system_configurations` (nhóm `'NOTIFICATION'`, kiểu `'INTEGER'`, mặc định `'45'`, khoảng `15–600` giây, `editable = true`) để Admin chỉnh trực tiếp trên màn Cấu hình hệ thống mà không cần khởi động lại Backend.
- **Enum `NotificationType`:** Thêm `CAMERA_OFFLINE`.
- **Enum `ConfigKey`:** Thêm `NOTIFICATION_CAMERA_OFFLINE_SECONDS("NOTIFICATION_CAMERA_OFFLINE_SECONDS", "45")`.
- **Hằng số `referenceType`:** `"CAMERA"`, `referenceId = camera.getId()`.

#### B. Theo dõi trạng thái & Phát cảnh báo (`CameraHealthAlertService` — `backend`)
- Đọc động thời gian xác nhận mỗi chu kỳ qua `systemConfigService.getInt(ConfigKey.NOTIFICATION_CAMERA_OFFLINE_SECONDS)` (mặc định `45` giây).
- Cấu trúc trạng thái trong RAM cho từng `cameraId`:
  - `lastKnownOnline` (`boolean`): Chỉ những camera đã từng được ghi nhận `ONLINE` (từ DB trước lúc chuyển trạng thái trong `syncLiveOperationalStatuses()`, hoặc từng thấy `ONLINE` trong phiên chạy) mới bắt đầu đếm ngược khi rớt sang `OFFLINE`. Camera vốn đã `OFFLINE` sẵn từ lúc khởi động server sẽ không bị báo giả.
  - `offlineSince` (`OffsetDateTime`): Mốc đầu tiên phát hiện chuyển từ `ONLINE` sang `OFFLINE`.
  - `alertSent` (`boolean`): Đánh dấu đã gửi cảnh báo cho đợt rớt này.
  - `notifiedGuardIds` (`Set<UUID>`): Lưu danh sách bảo vệ đã nhận tin STOMP `CAMERA_OFFLINE` của đợt này để khi camera `ONLINE` lại, hệ thống gửi tin thu hồi `CAMERA_ONLINE` tới đúng các bảo vệ đó (cộng với bảo vệ đang trực hiện tại) để không sót thông báo treo nếu ca trực vừa đổi.
  - `suppressedPlannedStop` (`boolean`): Được bật khi gọi `markPlannedStop(UUID cameraId)` từ:
    - `CameraStreamLifecycleService.stopCameraStream(...)`
    - Các luồng vô hiệu hoá / xoá mềm / ngừng sử dụng camera trong `CameraService`.
    Cờ này tự xoá khi camera được bật stream lại (`startCameraStream`) hoặc tự `ONLINE` trở lại.
- **Thời gian ân hạn khi Backend mới khởi động (Boot Grace):** Trong **60 giây đầu** sau khi ứng dụng Spring Boot sẵn sàng (`ApplicationReadyEvent`), nếu camera chuyển `ONLINE -> OFFLINE` do MediaMTX / MQTT Edge chưa kịp đồng bộ nhịp đầu tiên thì chỉ bắt đầu tính `offlineSince` sau khi hết 60s khởi động hoặc khi đã nhận nhịp xác nhận tiếp theo, tránh bão cảnh báo giả lúc deploy/restart server.
- **Xử lý khi sập toàn bộ MediaMTX Gateway hoặc toàn bộ Trạm biên Edge (Quyết định kỹ thuật):**
  - Hiện tại `CameraService.syncLiveOperationalStatuses()` thoát sớm khi `mediaMtxService.getLivePathStatuses() == null` (MediaMTX sập) hoặc rơi xuống nhánh MediaMTX cục bộ khi mọi trạm Edge đều `OFFLINE` (`hasActiveOnlineEdge() == false`), khiến camera vẫn treo `ONLINE` trong DB mà không báo động.
  - Cập nhật `syncLiveOperationalStatuses()`: Khi `mediaMtxService.getLivePathStatuses() == null` (bao gồm cả trường hợp MediaMTX cục bộ sập ở chế độ Standalone lẫn trường hợp Cloud có `mediamtx.api.url` rỗng khi mọi trạm Edge đều `OFFLINE`), đặt `gatewayUnreachable = true`, bỏ qua `reregisterMissingMediaMtxPath` và coi `isReady = false` để chuyển các camera đang `ONLINE` sang `OFFLINE` (nhưng vẫn giữ tương thích nếu môi trường dev cục bộ có bản ghi Edge cũ `OFFLINE` mà MediaMTX local đang chạy).
  - Vẫn giữ cảnh báo 1–1 theo từng camera (`referenceId = camera.getId()`) thay vì gộp chung, để khi từng camera lên hình lại ở các thời điểm khác nhau, lệnh thu hồi `CAMERA_ONLINE` gỡ đúng thông báo và toast của từng camera.
- **Phân tách tập người nhận DB In-App (Web) và STOMP (Mobile) (Quyết định kỹ thuật):**
  - **Bản ghi `notifications` trong DB (phục vụ chuông thông báo Web):** Gửi cho mọi `FACILITY_MANAGER` đang hoạt động, mọi `ADMIN` đang hoạt động, và **mọi tài khoản `GUARD` đang hoạt động** (`userRepository.findActiveUsersByRole(Role.GUARD)` — theo đúng quy ước hiện có của `EVENT_MODE_CHANGED` tại `AreaService:1452`). Lý do: bảo vệ ngồi trực màn `/guard` trên Web không cần check-in ca trên app Mobile, và `AppLayout.jsx` cũng subscribe `/topic/cameras/status-alerts` cho `ROLE_GUARD`, nên lưu DB cho các `GUARD` đang hoạt động giúp toast và số chuông trên Web luôn khớp 100%.
  - **Tin đẩy STOMP Mobile (`/topic/guards/{guardId}/camera-alerts`):** Chỉ gửi cho tập kết quả của `findOnDutyGuardsForCameraBuilding(OffsetDateTime now, UUID buildingId, String buildingName)` trong `IncidentRecipientResolver`: lọc từ tập ca `CHECKED_IN` hợp lệ (tài khoản hoạt động, trong khung giờ `[start, end)`) khớp `buildingId` hoặc khớp tên qua `StringNormalizer.matchesBuildingName` (lấy `Building` từ `area.getFloorEntity().getBuilding()` / `area.getBuilding()` với camera gắn khu vực, hoặc `camera.getMapBuilding()` với camera `CORRIDOR`); nếu kết quả rỗng hoặc camera chưa có thông tin toà thì fallback trả về toàn bộ `findOnDutyGuards(now)`.
- **Nội dung thông báo In-App:**
  - Tiêu đề: `"Camera mất kết nối đột ngột"`
  - Nội dung: `"Camera <tên> (<cameraCode>) tại <khu vực / Hành lang> (<toà nhà> · <tầng>) đã mất kết nối lúc <HH:mm>."` (nếu khuyết toà hoặc tầng thì chỉ ghép phần có dữ liệu).
- **Hợp đồng bản tin STOMP:**
  - Kênh Web chung: `/topic/cameras/status-alerts`
  - Kênh Mobile riêng từng bảo vệ: `/topic/guards/{guardId}/camera-alerts`
  - Payload JSON:
    ```json
    {
      "event": "CAMERA_OFFLINE",
      "cameraId": "uuid",
      "cameraCode": "CAM-001",
      "cameraName": "Cổng chính",
      "areaName": "Sảnh A",
      "building": "Tòa Alpha",
      "floor": "Tầng 1",
      "offlineSince": "2026-11-27T18:00:00+07:00",
      "timestamp": "2026-11-27T18:00:45+07:00"
    }
    ```
    Khi camera có tín hiệu lại (chỉ gửi nếu `alertSent == true`), phát cùng cấu trúc với `"event": "CAMERA_ONLINE"`.

#### C. Giao diện Web (`frontend`)
- **`AppLayout.jsx`:**
  - Mở rộng điều kiện subscribe `/topic/cameras/status-alerts` cho cả 3 vai trò: `FACILITY_MANAGER`, `ADMIN`, `GUARD`.
  - Khi nhận `CAMERA_OFFLINE`: hiện `toast.error` (id cố định `camera-offline-${cameraId}`, thời lượng `15000ms`), bắn `window.dispatchEvent(new CustomEvent('notification-updated'))` để cập nhật badge chuông ngay, đồng thời bắn `window.dispatchEvent(new CustomEvent('camera-health-alert', { detail: payload }))`.
  - Khi nhận `CAMERA_ONLINE`: gọi `toast.dismiss('camera-offline-' + cameraId)` và hiện `toast.success` ngắn (`5000ms`) báo camera đã kết nối lại.
- **`GuardDashboardPage.jsx`:**
  - Lắng nghe sự kiện `window` `'camera-health-alert'` (tái sử dụng kết nối STOMP của `AppLayout`, không mở thêm subscription trùng) để cập nhật ngay `status` (`"OFFLINE"` / `"ONLINE"`) của camera tương ứng trong `cameraList`.
- **`NotificationsPage.jsx` (Tôn trọng phân quyền Phase 9 `resolveCameraInspectorAccess`):**
  - Hiển thị icon cảnh báo camera cho loại `CAMERA_OFFLINE`.
  - Khi bấm vào thông báo `CAMERA_OFFLINE`:
    - `ADMIN`: điều hướng tới `/admin/cameras/${referenceId}` (mở thẳng tab Camera & `CameraInspectorDrawer` của camera đó; nếu thiếu `referenceId` thì về `/admin/areas`).
    - `FACILITY_MANAGER`: điều hướng tới `/admin/areas` (vì theo quy tắc Phase 9 tại `campusCameraHelpers.js:537` và `CameraController:92`, `FM` không có quyền mở tab Camera / `CameraInspectorDrawer` mà chỉ xem tổng quan khu vực và số lượng camera online/offline trên bản đồ khuôn viên).
    - `GUARD`: điều hướng tới `/guard`.

#### D. Ứng dụng Mobile (`mobile`)
- **`LocalNotificationService` (`local_notification_service.dart`):**
  - Thêm hàm `showCameraOffline({ required String cameraId, required String title, required String body })` và `cancelCameraOffline(String cameraId)` dùng ID hash ổn định `idFor('cam:$cameraId')` và `payload: 'cam:$cameraId'` trên kênh thông báo riêng `'camera_health_alerts'` (`'Cảnh báo kết nối camera'`, `Importance.high`, `NotificationVisibility.private`).
- **`main.dart`:**
  - Trong `onIncidentTap`: nếu `payload` bắt đầu bằng `'cam:'` thì **không** mở `IncidentListScreen` (chỉ để hệ điều hành đưa app lên foreground ở màn hình chính hiện tại), tránh mở nhầm danh sách sự cố an ninh.
- **`StompService` (`stomp_service.dart`) & `IncidentProvider` (`incident_provider.dart`):**
  - Trong `StompService.connect(...)`: khi có `guardId`, subscribe thêm `/topic/guards/$guardId/camera-alerts` và truyền qua callback `onCameraAlert`.
  - Handler kiểm tra còn phiên đăng nhập (`StorageHelper.getUser() != null`): nếu `event == 'CAMERA_OFFLINE'` thì gọi `showCameraOffline(...)`; nếu `event == 'CAMERA_ONLINE'` thì gọi `cancelCameraOffline(cameraId)`. Không thêm bất kỳ phần tử nào vào `_alertQueue` của `IncidentProvider`.

---

## 6. Phần để ngỏ cho người lập kế hoạch / triển khai (Left to the Builder)

- Cấu trúc class/package nội bộ cho DTO STOMP của cảnh báo camera (ví dụ `CameraStatusAlertPayload` trong `dto.camera`).
- Cách viết unit test / integration test giả lập đồng hồ (`Clock` hoặc truyền `OffsetDateTime now` vào hàm xử lý của `CameraHealthAlertService` giống mẫu `NotificationScanScheduler.tick(OffsetDateTime now)` để test mốc 45s không cần `Thread.sleep`).
- Lựa chọn icon cụ thể từ `lucide-react` (ví dụ `VideoOff` hoặc `Camera`) và câu chữ hiển thị chi tiết trên badge của `NotificationsPage.jsx` theo đúng quy chuẩn tiếng Việt hiện có.
