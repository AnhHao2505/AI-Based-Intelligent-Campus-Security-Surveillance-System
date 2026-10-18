import 'package:flutter/widgets.dart';
import 'package:provider/provider.dart';
import '../../../core/notifications/guard_duty_service.dart';
import '../../shift/providers/shift_provider.dart';
import '../providers/incident_provider.dart';

/// Bọc màn hình chính sau đăng nhập.
///
/// Khi bảo vệ có ca CHECKED_IN (đang trực): bật foreground service "Đang trực ca" và kết nối STOMP ngay,
/// không đợi mở màn bản đồ. Hết ca trực: tắt service. Đăng xuất (widget bị gỡ): tắt service và ngắt STOMP.
/// Danh sách ca lấy từ ShiftProvider (tuần hiện tại, gồm cả ca qua đêm của hôm trước).
class GuardDutyWatcher extends StatefulWidget {
  final Widget child;

  const GuardDutyWatcher({super.key, required this.child});

  @override
  State<GuardDutyWatcher> createState() => _GuardDutyWatcherState();
}

class _GuardDutyWatcherState extends State<GuardDutyWatcher> {
  bool _onDuty = false;
  IncidentProvider? _incidentProvider;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _incidentProvider ??= context.read<IncidentProvider>();
  }

  @override
  Widget build(BuildContext context) {
    final onDuty = context.select<ShiftProvider, bool>(
      (p) => p.shifts.any((s) => s.isCheckedIn),
    );
    if (onDuty != _onDuty) {
      _onDuty = onDuty;
      WidgetsBinding.instance.addPostFrameCallback((_) => _apply(onDuty));
    }
    return widget.child;
  }

  void _apply(bool onDuty) {
    if (!mounted) return;
    if (onDuty) {
      GuardDutyService.instance.start();
      if (!(_incidentProvider?.isStompConnected ?? true)) {
        _incidentProvider?.initStomp();
      }
    } else {
      GuardDutyService.instance.stop();
    }
  }

  @override
  void dispose() {
    GuardDutyService.instance.stop();
    _incidentProvider?.disconnectStomp();
    super.dispose();
  }
}
