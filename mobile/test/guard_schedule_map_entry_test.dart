import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/core/theme/theme_provider.dart';
import 'package:guard_mobile_app/features/auth/providers/auth_provider.dart';
import 'package:guard_mobile_app/features/chat/providers/guard_chat_provider.dart';
import 'package:guard_mobile_app/features/chat/services/guard_chat_service.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/providers/incident_provider.dart';
import 'package:guard_mobile_app/features/incident/services/incident_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_data.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_repository.dart';
import 'package:guard_mobile_app/features/map/providers/map_provider.dart';
import 'package:guard_mobile_app/features/map/screens/guard_map_screen.dart';
import 'package:guard_mobile_app/features/shift/providers/shift_provider.dart';
import 'package:guard_mobile_app/features/shift/screens/guard_schedule_screen.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _NoNetworkShiftProvider extends ShiftProvider {
  _NoNetworkShiftProvider(super.apiClient);

  @override
  Future<void> fetchWeeklyShifts([DateTime? referenceDate]) async {}
}

class _NoNetworkChatProvider extends GuardChatProvider {
  _NoNetworkChatProvider(ApiClient api)
      : super(chatService: GuardChatService(api), stompService: StompService());

  @override
  Future<void> fetchGuards() async {}
}

class _EmptyIncidentService extends IncidentService {
  _EmptyIncidentService() : super(ApiClient());

  @override
  Future<List<IncidentModel>> getActiveIncidents({String? building}) async => const [];

  @override
  Future<List<IncidentModel>> getIncidents({String? building, String? status}) async => const [];
}

class _ConnectedStomp extends StompService {
  @override
  bool get isConnected => true;
}

/// Bản đồ không nạp asset trong test (MapLibre là platform view) — chỉ cần màn được mở.
class _PendingCampusRepository extends CampusMapRepository {
  final _never = Completer<CampusMapData>();

  @override
  Future<CampusMapData> load() => _never.future;
}

Future<void> _pumpSchedule(WidgetTester tester) async {
  final api = ApiClient();
  await tester.pumpWidget(
    MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => ThemeProvider()),
        ChangeNotifierProvider(create: (_) => AuthProvider(api)),
        ChangeNotifierProvider<ShiftProvider>(create: (_) => _NoNetworkShiftProvider(api)),
        ChangeNotifierProvider<GuardChatProvider>(create: (_) => _NoNetworkChatProvider(api)),
        ChangeNotifierProvider(
          create: (_) => IncidentProvider(incidentService: _EmptyIncidentService(), stompService: _ConnectedStomp()),
        ),
        ChangeNotifierProvider(create: (_) => MapProvider(api, campusRepository: _PendingCampusRepository())),
      ],
      child: const MaterialApp(home: GuardScheduleScreen()),
    ),
  );
  await tester.pump();
  await tester.pump(const Duration(milliseconds: 100));
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('Lịch Trực: thanh tiêu đề không còn icon bản đồ, các icon còn lại giữ thứ tự', (tester) async {
    await _pumpSchedule(tester);

    expect(find.byTooltip('Bản đồ tuần tra an ninh'), findsNothing);
    expect(find.byIcon(Icons.map_outlined), findsNothing);

    final tooltips = tester
        .widgetList<IconButton>(find.byType(IconButton))
        .map((b) => b.tooltip)
        .whereType<String>()
        .toList();
    const expectedOrder = ['Làm mới lịch trực', 'Sự cố an ninh', 'Lịch sử đổi / xin nghỉ ca'];
    final present = tooltips.where(expectedOrder.contains).toList();
    expect(present, expectedOrder);
    expect(find.byTooltip('Tài khoản & Tùy chọn'), findsOneWidget);
  });

  testWidgets('Lịch Trực: vẫn có nút nổi "Bản đồ tuần tra" và bấm mở GuardMapScreen', (tester) async {
    await _pumpSchedule(tester);

    final fab = find.widgetWithText(FloatingActionButton, 'Bản đồ tuần tra');
    expect(fab, findsOneWidget);

    await tester.tap(fab);
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));
    expect(find.byType(GuardMapScreen), findsOneWidget);
  });
}
