import 'package:flutter/foundation.dart';
import '../../features/incident/providers/incident_provider.dart';
import '../../features/shift/providers/shift_provider.dart';

/// DEF-16: dọn phiên khi đăng xuất, chạy TRƯỚC khi về màn đăng nhập. Thứ tự:
/// 1. xoá danh sách ca (lần đăng nhập sau không thấy ngay ca CHECKED_IN cũ → không tự kết nối sớm);
/// 2. xoá hàng đợi sự cố, sự cố đang xử lý và lời mời;
/// 3. dừng foreground service "Đang trực ca";
/// 4. đóng STOMP (mọi client) → backend không còn tính máy này là phiên mobile của tài khoản vừa đăng xuất.
/// Mỗi bước lỗi thì ghi log và làm tiếp bước sau.
Future<void> runLogoutCleanup({
  required ShiftProvider shiftProvider,
  required IncidentProvider incidentProvider,
  required Future<void> Function() stopDutyService,
}) async {
  debugPrint('[Logout] cleanup start');
  try {
    shiftProvider.clear();
  } catch (e) {
    debugPrint('[Logout] clear shifts failed: $e');
  }
  try {
    incidentProvider.resetSession();
  } catch (e) {
    debugPrint('[Logout] reset incidents failed: $e');
  }
  try {
    await stopDutyService();
  } catch (e) {
    debugPrint('[Logout] stop duty service failed: $e');
  }
  try {
    incidentProvider.disconnectStomp();
  } catch (e) {
    debugPrint('[Logout] disconnect STOMP failed: $e');
  }
  debugPrint('[Logout] cleanup done');
}
