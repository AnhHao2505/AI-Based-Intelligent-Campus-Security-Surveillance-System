import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/chat/providers/guard_chat_provider.dart';
import 'package:guard_mobile_app/features/chat/services/guard_chat_service.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/providers/incident_provider.dart';
import 'package:guard_mobile_app/features/incident/screens/incident_list_screen.dart';
import 'package:guard_mobile_app/features/incident/services/incident_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_data.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_repository.dart';
import 'package:guard_mobile_app/features/map/providers/map_provider.dart';
import 'package:guard_mobile_app/features/map/screens/guard_map_screen.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// DEF-17: bảo vệ khác nhận sự cố → màn danh sách / bản đồ hiện SnackBar 4 giây, chỉ một lần.

const _me = {'id': 'guard-me', 'fullName': 'Bảo vệ An', 'email': 'guard.an@fpt.edu.vn', 'role': 'GUARD'};
const _notice = 'Sự cố đã được Bảo vệ Bình tiếp nhận.';

class _EmptyIncidentService extends IncidentService {
  _EmptyIncidentService() : super(ApiClient());

  @override
  Future<List<IncidentModel>> getActiveIncidents({String? building}) async => const [];

  @override
  Future<List<IncidentModel>> getIncidents({String? building, String? status}) async => const [];
}

/// Coi như đã kết nối để màn hình không mở WebSocket thật.
class _ConnectedStomp extends StompService {
  @override
  bool get isConnected => true;
}

/// Bản đồ không nạp asset trong test (MapLibre là platform view).
class _PendingCampusRepository extends CampusMapRepository {
  final _never = Completer<CampusMapData>();

  @override
  Future<CampusMapData> load() => _never.future;
}

final _incident = IncidentModel(
  id: 'inc-1',
  cameraCode: 'CAM-001',
  areaName: 'Phòng Lab',
  eventType: 'INTRUSION',
  status: 'NEW',
  severity: 'HIGH',
  dispatchStage: 'BROADCAST',
);

const _peerClaim = {
  'incidentId': 'inc-1',
  'status': 'CLAIMED',
  'claimedById': 'guard-binh',
  'claimedByName': 'Bảo vệ Bình',
};

/// Sự cố vào hàng đợi rồi bảo vệ khác nhận nó.
Future<void> _peerClaims(WidgetTester tester, IncidentProvider provider) async {
  provider.receiveIncidentForTest(_incident);
  await tester.pump(const Duration(milliseconds: 300)); // debounce sắp xếp hàng đợi
  provider.receiveUpdateForTest(Map<String, dynamic>.from(_peerClaim));
  await tester.pump();
  await tester.pump();
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() {
    // HapticFeedback gọi kênh platform; trong test trả null
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async => null);
  });

  late IncidentProvider provider;

  setUp(() {
    SharedPreferences.setMockInitialValues({'guard_user_data': jsonEncode(_me)});
    provider = IncidentProvider(incidentService: _EmptyIncidentService(), stompService: _ConnectedStomp());
  });

  tearDown(() => provider.dispose());

  testWidgets('Màn danh sách: bảo vệ khác nhận → SnackBar 4 giây đúng câu, notice về null, không hiện lại',
      (tester) async {
    tester.view.physicalSize = const Size(1400, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    await tester.pumpWidget(ChangeNotifierProvider<IncidentProvider>.value(
      value: provider,
      child: const MaterialApp(home: IncidentListScreen()),
    ));
    await tester.pumpAndSettle();

    await _peerClaims(tester, provider);

    expect(find.text(_notice), findsOneWidget);
    expect(tester.widget<SnackBar>(find.byType(SnackBar)).duration, const Duration(seconds: 4));
    expect(provider.peerClaimNotice, isNull);

    // Hết 4 giây thì ẩn; provider báo đổi khác (không phải lần nhận mới) → không hiện lại
    await tester.pumpAndSettle(); // SnackBar hiện xong, bắt đầu đếm 4 giây
    await tester.pump(const Duration(seconds: 3));
    expect(find.text(_notice), findsOneWidget);
    await tester.pump(const Duration(seconds: 2));
    await tester.pumpAndSettle();
    expect(find.text(_notice), findsNothing);
    provider.clearErrorMessage();
    await tester.pumpAndSettle();
    expect(find.text(_notice), findsNothing);
  });

  testWidgets('Màn bản đồ tuần tra: bảo vệ khác nhận → SnackBar đúng câu, notice về null', (tester) async {
    final api = ApiClient();
    await tester.pumpWidget(MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => MapProvider(api, campusRepository: _PendingCampusRepository())),
        ChangeNotifierProvider(
          create: (_) => GuardChatProvider(chatService: GuardChatService(api), stompService: StompService()),
        ),
        ChangeNotifierProvider<IncidentProvider>.value(value: provider),
      ],
      child: const MaterialApp(home: GuardMapScreen()),
    ));
    await tester.pump();

    await _peerClaims(tester, provider);

    expect(find.text(_notice), findsOneWidget);
    expect(provider.peerClaimNotice, isNull);
  });
}
