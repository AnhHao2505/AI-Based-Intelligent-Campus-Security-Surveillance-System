import 'package:flutter/foundation.dart';
import '../../../core/notifications/guard_duty_service.dart';
import '../../incident/providers/incident_provider.dart';
import '../../shift/providers/shift_provider.dart';

/// Dọn phiên bảo vệ khi đăng xuất (DEF-16). AuthProvider.logout() gọi TRƯỚC khi xoá token,
/// không dựa riêng vào dispose của widget (GuardDutyWatcher).
///
/// Thứ tự: ngắt STOMP (dừng hẳn nối lại) → dừng foreground service "Đang trực ca"
/// → xoá lời mời + danh sách sự cố → xoá ca. Máy đã đăng xuất không còn phiên WebSocket mobile
/// nên backend không mời nhận sự cố nữa.
class GuardSessionTeardown {
  GuardSessionTeardown({
    required this.incidentProvider,
    required this.shiftProvider,
    Future<void> Function()? stopDutyService,
  }) : _stopDutyService = stopDutyService ?? GuardDutyService.instance.stop;

  final IncidentProvider incidentProvider;
  final ShiftProvider shiftProvider;
  final Future<void> Function() _stopDutyService;

  Future<void> call() async {
    debugPrint('[AUTH] Ending guard session: STOMP, duty service, incidents, shifts');
    incidentProvider.disconnectStomp();
    await _stopDutyService();
    incidentProvider.clearSessionData();
    shiftProvider.clearSession();
  }
}
