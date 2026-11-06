import 'package:flutter/widgets.dart';
import 'package:provider/provider.dart';
import '../../../core/notifications/guard_duty_service.dart';
import '../../shift/providers/shift_provider.dart';
import '../providers/incident_provider.dart';

/// Bọc màn hình chính sau đăng nhập.
///
/// Khi bảo vệ có ca CHECKED_IN (đang trực): bật foreground service "Đang trực ca" và kết nối STOMP ngay,
/// không đợi mở màn bản đồ. Hết ca trực: tắt service. Đăng xuất (widget bị gỡ): tắt service và ngắt STOMP.
/// DEF-16: đây là nơi DUY NHẤT gọi initStomp(). L5: đặt toà đang trực cho IncidentProvider.
/// Danh sách ca lấy từ ShiftProvider (tuần hiện tại, gồm cả ca qua đêm của hôm trước).
class GuardDutyWatcher extends StatefulWidget {
  final Widget child;

  const GuardDutyWatcher({super.key, required this.child});

  @override
  State<GuardDutyWatcher> createState() => _GuardDutyWatcherState();
}

class _GuardDutyWatcherState extends State<GuardDutyWatcher> {
  bool _onDuty = false;
  String? _dutyBuildingsKey;
  IncidentProvider? _incidentProvider;

  /// L5: toà của ca CHECKED_IN (buildingName, thiếu thì building). Không có ca đang trực → tập rỗng.
  /// Có ca đang trực nhưng không biết toà → null (không lọc, giữ hành vi cũ).
  static Set<String>? dutyBuildingsOf(ShiftProvider p) {
    final checkedIn = p.shifts.where((s) => s.isCheckedIn).toList();
    if (checkedIn.isEmpty) return <String>{};
    final names = checkedIn.map((s) => s.displayBuilding).whereType<String>().toSet();
    return names.isEmpty ? null : names;
  }

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
    // L5: chỉ thấy sự cố của toà đang trực. Key dạng chuỗi để select so sánh được.
    final buildingsKey = context.select<ShiftProvider, String>((p) {
      final b = dutyBuildingsOf(p);
      return b == null ? '<unknown>' : (b.toList()..sort()).join('|');
    });
    if (buildingsKey != _dutyBuildingsKey) {
      _dutyBuildingsKey = buildingsKey;
      WidgetsBinding.instance.addPostFrameCallback((_) => _applyBuildings());
    }
    return widget.child;
  }

  void _applyBuildings() {
    if (!mounted) return;
    final buildings = dutyBuildingsOf(context.read<ShiftProvider>());
    final changed = _incidentProvider?.setDutyBuildings(buildings) ?? false;
    // Toà mới (vd. vừa check-in): tải lại để sự cố của toà đó hiện ra
    if (changed && buildings != null && buildings.isNotEmpty) {
      _incidentProvider?.loadActiveIncidents();
    }
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
