import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/providers/incident_provider.dart';
import 'package:guard_mobile_app/features/incident/screens/incident_list_screen.dart';
import 'package:guard_mobile_app/features/incident/services/incident_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:guard_mobile_app/features/incident/widgets/incident_offer_banner.dart';
import 'package:guard_mobile_app/main.dart' show appNavigatorKey, openIncidentListScreen;
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _me = {'id': 'guard-me', 'fullName': 'Bảo vệ An', 'email': 'guard.an@fpt.edu.vn', 'role': 'GUARD'};

class FakeIncidentService extends IncidentService {
  final Map<String, IncidentModel> server = {};
  bool claimFails = false;

  FakeIncidentService() : super(ApiClient());

  @override
  Future<List<IncidentModel>> getActiveIncidents({String? building}) async =>
      server.values.where((i) => !i.isResolved).toList();

  @override
  Future<List<IncidentModel>> getIncidents({String? building, String? status}) async => server.values.toList();

  @override
  Future<Map<String, dynamic>> claimIncident(String incidentId) async {
    if (claimFails) {
      final options = RequestOptions(path: '/api/incidents/$incidentId/claim');
      throw DioException(
        requestOptions: options,
        response: Response(requestOptions: options, statusCode: 500, data: {'message': 'Lỗi máy chủ'}),
        type: DioExceptionType.badResponse,
      );
    }
    final current = server[incidentId];
    if (current != null) {
      server[incidentId] = current.copyWith(
        status: 'CLAIMED',
        claimedById: _me['id'],
        claimedByName: _me['fullName'],
        dispatchStage: 'STEP1',
      );
    }
    return {'success': true};
  }

  @override
  Future<IncidentModel> resolveIncident(
    String incidentId, {
    required String outcome,
    required String resolutionCategory,
    String? resolutionNotes,
    String? evidenceImageUrl,
  }) async {
    final resolved = server[incidentId]!.copyWith(status: 'RESOLVED_VERIFIED');
    server[incidentId] = resolved;
    return resolved;
  }
}

class FakeStompService extends StompService {
  @override
  bool get isConnected => true;
}

Map<String, dynamic> _offerMessage(String incidentId) => {
      'type': 'INCIDENT_OFFER',
      'offerId': 'off-test-1',
      'incidentId': incidentId,
      'expiresAt': DateTime.now().add(const Duration(seconds: 30)).toUtc().toIso8601String(),
      'incident': {
        'id': incidentId,
        'cameraCode': 'CAM-TEST',
        'areaName': 'Khu vực thử nghiệm',
        'eventType': 'INTRUSION',
        'status': 'NEW',
        'severity': 'HIGH',
        'dispatchStage': 'STEP1',
      },
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async => null);
  });

  late FakeIncidentService service;
  late IncidentProvider provider;

  setUp(() {
    SharedPreferences.setMockInitialValues({'guard_user_data': jsonEncode(_me)});
    service = FakeIncidentService();
    provider = IncidentProvider(incidentService: service, stompService: FakeStompService());
  });

  tearDown(() {
    provider.dispose();
  });

  testWidgets('nhận qua nút TIẾP NHẬN → tab "Đang xử lý" được chọn', (tester) async {
    tester.view.physicalSize = const Size(1400, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final inc = IncidentModel(
      id: 'inc-1',
      cameraCode: 'CAM-001',
      areaName: 'Phòng Lab',
      eventType: 'INTRUSION',
      status: 'NEW',
      severity: 'HIGH',
      dispatchStage: 'BROADCAST',
    );
    service.server['inc-1'] = inc;

    await tester.pumpWidget(
      ChangeNotifierProvider<IncidentProvider>.value(
        value: provider,
        child: const MaterialApp(
          home: IncidentListScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    // Ban đầu ở tab "Cần xử lý", có nút TIẾP NHẬN
    expect(find.text('TIẾP NHẬN'), findsOneWidget);
    expect(find.text('GIẢI QUYẾT'), findsNothing);

    // Bấm TIẾP NHẬN
    await tester.tap(find.text('TIẾP NHẬN'));
    await tester.pumpAndSettle();

    // Tab tự động chuyển sang "Đang xử lý": hiển thị nút GIẢI QUYẾT, không còn nút TIẾP NHẬN
    expect(find.text('GIẢI QUYẾT'), findsOneWidget);
    expect(find.text('TIẾP NHẬN'), findsNothing);
    expect(find.text('Tiếp nhận sự cố thành công!'), findsOneWidget);
  });

  testWidgets('nhận qua banner → màn danh sách mở ở tab "Đang xử lý"', (tester) async {
    tester.view.physicalSize = const Size(1400, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final inc = IncidentModel(
      id: 'inc-2',
      cameraCode: 'CAM-002',
      areaName: 'Cổng chính',
      eventType: 'INTRUSION',
      status: 'NEW',
      severity: 'HIGH',
      dispatchStage: 'STEP1',
    );
    service.server['inc-2'] = inc;

    await tester.pumpWidget(
      ChangeNotifierProvider<IncidentProvider>.value(
        value: provider,
        child: MaterialApp(
          navigatorKey: appNavigatorKey,
          home: const Scaffold(body: Center(child: Text('Home Screen'))),
          builder: (context, child) => Stack(
            children: [
              child ?? const SizedBox.shrink(),
              IncidentOfferBanner(onClaimed: (_) => openIncidentListScreen()),
            ],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    // Phát lời mời
    provider.handleOfferMessage(_offerMessage('inc-2'));
    await tester.pump();

    // Banner hiển thị nút "Nhận"
    expect(find.text('Nhận'), findsOneWidget);
    await tester.tap(find.text('Nhận'));
    await tester.pumpAndSettle();

    // Màn danh sách IncidentListScreen được mở
    expect(find.byType(IncidentListScreen), findsOneWidget);
    // Tab "Đang xử lý" được chọn: có nút GIẢI QUYẾT của sự cố vừa nhận
    expect(find.text('GIẢI QUYẾT'), findsOneWidget);
    expect(find.text('TIẾP NHẬN'), findsNothing);
  });

  testWidgets('nhận qua banner khi màn danh sách đang mở sẵn → chuyển tab, không mở thêm màn', (tester) async {
    tester.view.physicalSize = const Size(1400, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final inc = IncidentModel(
      id: 'inc-2b',
      cameraCode: 'CAM-002',
      areaName: 'Cổng chính',
      eventType: 'INTRUSION',
      status: 'NEW',
      severity: 'HIGH',
      dispatchStage: 'STEP1',
    );
    service.server['inc-2b'] = inc;

    await tester.pumpWidget(
      ChangeNotifierProvider<IncidentProvider>.value(
        value: provider,
        child: MaterialApp(
          navigatorKey: appNavigatorKey,
          home: const IncidentListScreen(),
          builder: (context, child) => Stack(
            children: [
              child ?? const SizedBox.shrink(),
              IncidentOfferBanner(onClaimed: (_) => openIncidentListScreen()),
            ],
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();

    // Màn danh sách đang mở sẵn ở tab mặc định (Cần xử lý)
    expect(find.byType(IncidentListScreen), findsOneWidget);

    // Phát lời mời
    provider.handleOfferMessage(_offerMessage('inc-2b'));
    await tester.pump();

    // Bấm Nhận trên banner
    await tester.tap(find.text('Nhận'));
    await tester.pumpAndSettle();

    // Vẫn chỉ có đúng 1 widget IncidentListScreen (không mở thêm màn chồng lên)
    expect(find.byType(IncidentListScreen), findsOneWidget);
    // Đã chuyển sang tab "Đang xử lý"
    expect(find.text('GIẢI QUYẾT'), findsOneWidget);
  });

  testWidgets('nhận thất bại → tab không đổi', (tester) async {
    tester.view.physicalSize = const Size(1400, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    final inc = IncidentModel(
      id: 'inc-3',
      cameraCode: 'CAM-003',
      areaName: 'Sân bóng',
      eventType: 'INTRUSION',
      status: 'NEW',
      severity: 'HIGH',
      dispatchStage: 'BROADCAST',
    );
    service.server['inc-3'] = inc;
    service.claimFails = true;

    await tester.pumpWidget(
      ChangeNotifierProvider<IncidentProvider>.value(
        value: provider,
        child: const MaterialApp(
          home: IncidentListScreen(),
        ),
      ),
    );
    await tester.pumpAndSettle();

    // Đang ở tab "Cần xử lý", có nút TIẾP NHẬN
    expect(find.text('TIẾP NHẬN'), findsOneWidget);

    // Bấm TIẾP NHẬN nhưng thất bại
    await tester.tap(find.text('TIẾP NHẬN'));
    await tester.pumpAndSettle();

    // Báo lỗi bằng SnackBar
    expect(find.byType(SnackBar), findsOneWidget);
    // Tab vẫn giữ nguyên là "Cần xử lý": vẫn thấy nút TIẾP NHẬN, không có nút GIẢI QUYẾT
    expect(find.text('TIẾP NHẬN'), findsOneWidget);
    expect(find.text('GIẢI QUYẾT'), findsNothing);
  });
}
