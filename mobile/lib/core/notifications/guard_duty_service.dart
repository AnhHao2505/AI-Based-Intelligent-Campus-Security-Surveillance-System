import 'package:flutter/foundation.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';

/// Foreground service "Đang trực ca" (Android).
///
/// Android đóng băng app chạy nền chỉ sau vài giây (cached app freezer) -> kết nối STOMP ngừng nhận,
/// cảnh báo sự cố không tới. Khi bảo vệ đang trong ca (CHECKED_IN), giữ một foreground service với
/// thông báo cố định để hệ điều hành không đóng băng tiến trình; STOMP ở isolate chính vẫn chạy.
/// Loại service: remoteMessaging (nhận tin nhắn/cảnh báo thời gian thực), không cần quyền vị trí,
/// không bị giới hạn 6 giờ/ngày như dataSync.
///
/// Giới hạn: app bị người dùng vuốt tắt hẳn thì không nhận được (cần FCM — ngoài phạm vi).
/// iOS không hỗ trợ cơ chế này -> no-op.
class GuardDutyService {
  GuardDutyService._();

  static final GuardDutyService instance = GuardDutyService._();

  static const int _serviceId = 4201;

  bool _initialized = false;

  bool get _supported => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  /// Gọi một lần trong main().
  void init() {
    if (!_supported || _initialized) return;
    try {
      FlutterForegroundTask.initCommunicationPort();
      FlutterForegroundTask.init(
        androidNotificationOptions: AndroidNotificationOptions(
          channelId: 'guard_duty',
          channelName: 'Trạng thái trực ca',
          channelDescription: 'Hiển thị khi đang trong ca trực để ứng dụng tiếp tục nhận cảnh báo sự cố',
          channelImportance: NotificationChannelImportance.LOW,
          priority: NotificationPriority.LOW,
          onlyAlertOnce: true,
        ),
        iosNotificationOptions: const IOSNotificationOptions(showNotification: false),
        foregroundTaskOptions: ForegroundTaskOptions(
          eventAction: ForegroundTaskEventAction.nothing(),
          autoRunOnBoot: false,
          allowWakeLock: true,
          allowWifiLock: true,
        ),
      );
      _initialized = true;
    } catch (e) {
      debugPrint('[GuardDuty] init failed: $e');
    }
  }

  Future<void> start() async {
    if (!_initialized) return;
    try {
      if (await FlutterForegroundTask.isRunningService) return;
      final result = await FlutterForegroundTask.startService(
        serviceId: _serviceId,
        serviceTypes: const [ForegroundServiceTypes.remoteMessaging],
        notificationTitle: 'Đang trực ca',
        notificationText: 'Ứng dụng đang nhận cảnh báo sự cố',
        callback: guardDutyStartCallback,
      );
      if (result is ServiceRequestFailure) {
        debugPrint('[GuardDuty] start failed: ${result.error}');
      } else {
        debugPrint('[GuardDuty] started');
      }
    } catch (e) {
      debugPrint('[GuardDuty] start failed: $e');
    }
  }

  Future<void> stop() async {
    if (!_initialized) return;
    try {
      if (!await FlutterForegroundTask.isRunningService) return;
      await FlutterForegroundTask.stopService();
      debugPrint('[GuardDuty] stopped');
    } catch (e) {
      debugPrint('[GuardDuty] stop failed: $e');
    }
  }
}

/// Service chỉ để giữ tiến trình sống; không làm việc gì ở isolate của task.
@pragma('vm:entry-point')
void guardDutyStartCallback() {
  FlutterForegroundTask.setTaskHandler(_GuardDutyTaskHandler());
}

class _GuardDutyTaskHandler extends TaskHandler {
  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {}

  @override
  void onRepeatEvent(DateTime timestamp) {}

  @override
  Future<void> onDestroy(DateTime timestamp, bool isTimeout) async {}
}
