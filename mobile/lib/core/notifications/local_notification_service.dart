import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

/// Thông báo hệ thống (local notification) cho cảnh báo sự cố khi app đang chạy nền.
///
/// Chỉ hiện được khi app còn sống và còn kết nối STOMP. Không có push từ server (không FCM):
/// app bị đóng hẳn hoặc bị hệ điều hành dừng thì không nhận được.
/// Nội dung không chứa họ tên, mã người hay ảnh (tối thiểu dữ liệu cá nhân trên màn hình khoá).
class LocalNotificationService {
  LocalNotificationService._();

  static final LocalNotificationService instance = LocalNotificationService._();

  static const String _channelId = 'incident_alerts';
  static const String _channelName = 'Cảnh báo sự cố';
  static const String _channelDescription =
      'Thông báo khi có sự cố an ninh mới lúc ứng dụng đang chạy nền';

  final FlutterLocalNotificationsPlugin _plugin = FlutterLocalNotificationsPlugin();
  bool _ready = false;

  /// Gọi khi bảo vệ bấm vào thông báo sự cố (payload = incident id). Gán trong main().
  void Function(String? incidentId)? onIncidentTap;

  bool get _supported =>
      !kIsWeb &&
      (defaultTargetPlatform == TargetPlatform.android ||
          defaultTargetPlatform == TargetPlatform.iOS);

  /// Gọi một lần trong main(). Không xin quyền ở đây (xin sau khi đăng nhập).
  Future<void> init() async {
    if (!_supported || _ready) return;
    try {
      const settings = InitializationSettings(
        android: AndroidInitializationSettings('@mipmap/ic_launcher'),
        iOS: DarwinInitializationSettings(
          requestAlertPermission: false,
          requestBadgePermission: false,
          requestSoundPermission: false,
        ),
      );
      // Bấm vào thông báo: hệ điều hành đưa app lên foreground, rồi mở màn bản đồ (hàng đợi sự cố).
      await _plugin.initialize(
        settings: settings,
        onDidReceiveNotificationResponse: (response) {
          debugPrint('[LocalNotification] tapped payload=${response.payload} '
              'type=${response.notificationResponseType} hasHandler=${onIncidentTap != null}');
          onIncidentTap?.call(response.payload);
        },
      );
      _ready = true;
    } catch (e) {
      debugPrint('[LocalNotification] init failed: $e');
    }
  }

  /// Xin quyền hiển thị thông báo (Android 13+, iOS). Gọi khi bảo vệ đã đăng nhập và bắt đầu nhận cảnh báo.
  Future<void> requestPermission() async {
    if (!_ready) return;
    try {
      if (defaultTargetPlatform == TargetPlatform.android) {
        await _plugin
            .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>()
            ?.requestNotificationsPermission();
      } else if (defaultTargetPlatform == TargetPlatform.iOS) {
        await _plugin
            .resolvePlatformSpecificImplementation<IOSFlutterLocalNotificationsPlugin>()
            ?.requestPermissions(alert: true, badge: true, sound: true);
      }
    } catch (e) {
      debugPrint('[LocalNotification] request permission failed: $e');
    }
  }

  /// Id ổn định theo incident id (FNV-1a 31 bit) — giữ nguyên giữa các lần chạy app,
  /// để cùng một sự cố không hiện hai thông báo và huỷ được đúng thông báo.
  static int idFor(String incidentId) {
    var hash = 0x811c9dc5;
    for (final unit in incidentId.codeUnits) {
      hash ^= unit;
      hash = (hash * 0x01000193) & 0xffffffff;
    }
    return hash & 0x7fffffff;
  }

  Future<void> showIncident({
    required String incidentId,
    required String title,
    required String body,
  }) async {
    if (!_ready) {
      debugPrint('[LocalNotification] skip show: plugin not initialized');
      return;
    }
    try {
      const details = NotificationDetails(
        android: AndroidNotificationDetails(
          _channelId,
          _channelName,
          channelDescription: _channelDescription,
          importance: Importance.high,
          priority: Priority.high,
          category: AndroidNotificationCategory.alarm,
          // Màn hình khoá có bảo mật: ẩn nội dung, chỉ báo có thông báo.
          visibility: NotificationVisibility.private,
          ticker: 'Có sự cố an ninh mới',
        ),
        iOS: DarwinNotificationDetails(
          presentAlert: true,
          presentBanner: true,
          presentList: true,
          presentSound: true,
          presentBadge: false,
        ),
      );
      await _plugin.show(
        id: idFor(incidentId),
        title: title,
        body: body,
        notificationDetails: details,
        payload: incidentId,
      );
      debugPrint('[LocalNotification] shown for incident $incidentId');
    } catch (e) {
      debugPrint('[LocalNotification] show failed: $e');
    }
  }

  Future<void> cancelIncident(String incidentId) async {
    if (!_ready) return;
    try {
      await _plugin.cancel(id: idFor(incidentId));
    } catch (e) {
      debugPrint('[LocalNotification] cancel failed: $e');
    }
  }

  Future<void> cancelAll() async {
    if (!_ready) return;
    try {
      await _plugin.cancelAll();
    } catch (e) {
      debugPrint('[LocalNotification] cancelAll failed: $e');
    }
  }
}
