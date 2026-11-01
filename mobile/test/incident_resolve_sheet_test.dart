import 'dart:async';
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

/// IncidentService giả, không gọi mạng. Danh sách active / lịch sử trả theo trạng thái server giả lập.
class FakeIncidentService extends IncidentService {
  final Map<String, IncidentModel> server = {};
  bool resolveFails = false;

  /// Chạy sau [afterClaimHops] lượt microtask kể từ lúc claim trả về: rơi vào lúc provider đã lấy idx
  /// và đang chờ StorageHelper.getUser().
  void Function()? afterClaimReturns;
  int afterClaimHops = 1;

  FakeIncidentService() : super(ApiClient());

  @override
  Future<List<IncidentModel>> getActiveIncidents({String? building}) async =>
      server.values.where((i) => !i.isResolved).toList();

  @override
  Future<List<IncidentModel>> getIncidents({String? building, String? status}) async => server.values.toList();

  @override
  Future<Map<String, dynamic>> claimIncident(String incidentId) async {
    final current = server[incidentId];
    if (current != null) {
      server[incidentId] = current.copyWith(
          status: 'CLAIMED', claimedById: _me['id'], claimedByName: _me['fullName'], dispatchStage: 'STEP1');
    }
    final hook = afterClaimReturns;
    if (hook != null) {
      void schedule(int hops) => scheduleMicrotask(() => hops == 0 ? hook() : schedule(hops - 1));
      schedule(afterClaimHops);
    }
    return {'success': true};
  }

  @override
  Future<IncidentModel> resolveIncident(String incidentId,
      {required String outcome, required String resolutionCategory, String? resolutionNotes, String? evidenceImageUrl}) async {
    if (resolveFails) {
      final options = RequestOptions(path: '/api/incidents/$incidentId/resolve');
      throw DioException(
        requestOptions: options,
        response: Response(requestOptions: options, statusCode: 409, data: {'message': 'Sự cố đã được xử lý'}),
        type: DioExceptionType.badResponse,
      );
    }
    final resolved = server[incidentId]!.copyWith(status: 'RESOLVED_VERIFIED');
    server[incidentId] = resolved;
    return resolved;
  }
}

/// StompService giả: coi như đã kết nối để màn danh sách không mở WebSocket thật.
class FakeStompService extends StompService {
  @override
  bool get isConnected => true;
}

Map<String, dynamic> _offerMessage(String incidentId) => {
      'type': 'INCIDENT_OFFER',
      'offerId': 'off-1',
      'incidentId': incidentId,
      'expiresAt': DateTime.now().add(const Duration(seconds: 30)).toUtc().toIso8601String(),
      'incident': {
        'id': incidentId,
        'cameraCode': 'CAM-001',
        'areaName': 'Phòng Lab',
        'eventType': 'INTRUSION',
        'status': 'NEW',
        'severity': 'HIGH',
        'dispatchStage': 'STEP1',
      },
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() {
    // HapticFeedback gọi kênh platform; trong test trả null
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

  /// Dựng giống GuardMobileApp: banner lời mời nằm trong Stack của MaterialApp.builder.
  Widget app() => ChangeNotifierProvider<IncidentProvider>.value(
        value: provider,
        child: MaterialApp(
          navigatorKey: appNavigatorKey,
          home: const Scaffold(body: SizedBox.shrink()),
          builder: (context, child) => Stack(
            children: [
              child ?? const SizedBox.shrink(),
              IncidentOfferBanner(onClaimed: (_) => openIncidentListScreen()),
            ],
          ),
        ),
      );

  testWidgets('Lỗi 2: nhận qua banner lời mời → sự cố CLAIMED, hiện ở tab "Đang xử lý"', (tester) async {
    tester.view.physicalSize = const Size(1400, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);

    service.server['inc-1'] = IncidentModel(
        id: 'inc-1', cameraCode: 'CAM-001', areaName: 'Phòng Lab', eventType: 'INTRUSION', status: 'NEW',
        severity: 'HIGH', dispatchStage: 'STEP1');
    await tester.pumpWidget(app());
    provider.handleOfferMessage(_offerMessage('inc-1'));
    await tester.pump();

    await tester.tap(find.text('Nhận'));
    await tester.pumpAndSettle();

    expect(find.byType(IncidentListScreen), findsOneWidget);
    expect(provider.activeIncident?.status, equals('CLAIMED'));
    expect(provider.activeIncident?.claimedById, equals('guard-me'));
    expect(provider.alertQueue.where((i) => i.id == 'inc-1'), isEmpty);

    // Tab mặc định "Cần xử lý": không còn sự cố
    expect(find.text('TIẾP NHẬN'), findsNothing);
    await tester.tap(find.text('Đang xử lý').first);
    await tester.pumpAndSettle();
    expect(find.text('GIẢI QUYẾT'), findsOneWidget);

    provider.dispose();
  });

  group('Lỗi 2: hàng đợi đổi trong lúc claim đang chờ', () {
    /// Update CLAIMED cũng chờ getUser trước khi gỡ khỏi hàng đợi, nên phải bắt đầu sớm hơn 1 lượt microtask
    /// để việc gỡ rơi vào lúc claim đã lấy idx và đang chờ getUser.
    const shrinkHops = 0;

    void expectClaimedAndRestored() {
      final active = provider.activeIncident;
      expect(active?.id, equals('inc-1'));
      expect(active?.status, equals('CLAIMED'));
      expect(active?.claimedById, equals('guard-me'));
      expect(active?.claimedByName, equals('Bảo vệ An'));
      expect(active?.claimedAt, isNotNull);
      expect(provider.alertQueue.where((i) => i.id == 'inc-1'), isEmpty);
      // Sự cố khác không bị lấy nhầm / mất khỏi hàng đợi
      expect(provider.alertQueue.where((i) => i.id == 'inc-2').single.status, equals('NEW'));
    }

    test('Thêm: lời mời CRITICAL tới và lên đầu hàng đợi → sự cố vừa nhận vẫn CLAIMED, không còn trong hàng đợi',
        () async {
      await SharedPreferences.getInstance();
      provider.handleOfferMessage(_offerMessage('inc-1'));
      var mutated = false;
      service.afterClaimReturns = () {
        mutated = true;
        provider.handleOfferMessage({
          ..._offerMessage('inc-2'),
          'offerId': 'off-2',
          'incident': {...(_offerMessage('inc-2')['incident'] as Map), 'severity': 'CRITICAL'},
        });
      };

      final ok = await provider.claimIncident('inc-1');

      expect(ok, isTrue);
      expect(mutated, isTrue);
      expectClaimedAndRestored();
      provider.dispose();
    });

    test('Sắp xếp lại: bỏ qua cảnh báo đầu hàng đợi → sự cố vừa nhận vẫn CLAIMED, không còn trong hàng đợi', () async {
      await SharedPreferences.getInstance();
      final now = DateTime.now();
      provider.receiveIncidentForTest(IncidentModel(
          id: 'inc-1', cameraCode: 'C', eventType: 'INTRUSION', status: 'NEW', detectedAt: now));
      provider.receiveIncidentForTest(IncidentModel(
          id: 'inc-2', cameraCode: 'C', eventType: 'INTRUSION', status: 'NEW', detectedAt: now.add(const Duration(seconds: 1))));
      // Hàng đợi sắp xếp sau debounce 250 ms
      await Future<void>.delayed(const Duration(milliseconds: 350));
      expect(provider.alertQueue.map((i) => i.id).toList(), equals(['inc-1', 'inc-2']));
      var mutated = false;
      service.afterClaimReturns = () {
        mutated = true;
        provider.dismissCurrentAlert();
      };

      final ok = await provider.claimIncident('inc-1');

      expect(ok, isTrue);
      expect(mutated, isTrue);
      expectClaimedAndRestored();
      provider.dispose();
    });

    test('Ngắn đi: người khác nhận sự cố đứng trước → không ném lỗi, sự cố vừa nhận vẫn CLAIMED (dữ liệu từ hàng đợi)',
        () async {
      await SharedPreferences.getInstance();
      final now = DateTime.now();
      provider.receiveIncidentForTest(IncidentModel(
          id: 'inc-2', cameraCode: 'CAM-002', eventType: 'INTRUSION', status: 'NEW', detectedAt: now));
      provider.receiveIncidentForTest(IncidentModel(
          id: 'inc-1', cameraCode: 'CAM-001', eventType: 'INTRUSION', status: 'NEW',
          detectedAt: now.add(const Duration(seconds: 1))));
      // Hàng đợi sắp xếp sau debounce 250 ms
      await Future<void>.delayed(const Duration(milliseconds: 350));
      expect(provider.alertQueue.map((i) => i.id).toList(), equals(['inc-2', 'inc-1']));
      var mutated = false;
      service.afterClaimHops = shrinkHops;
      service.afterClaimReturns = () {
        mutated = true;
        // Update CLAIMED của bảo vệ khác → provider gỡ inc-2, hàng đợi còn 1 phần tử (idx = 1 hết hợp lệ)
        provider.receiveUpdateForTest(
            {'incidentId': 'inc-2', 'status': 'CLAIMED', 'claimedById': 'guard-b', 'claimedByName': 'B'});
      };

      final ok = await provider.claimIncident('inc-1');

      expect(mutated, isTrue);
      expect(provider.errorMessage, isNull);
      expect(ok, isTrue);
      final active = provider.activeIncident;
      expect(active?.id, equals('inc-1'));
      expect(active?.status, equals('CLAIMED'));
      expect(active?.claimedById, equals('guard-me'));
      expect(active?.claimedByName, equals('Bảo vệ An'));
      expect(active?.cameraCode, equals('CAM-001'));
      expect(provider.alertQueue, isEmpty);
      provider.dispose();
    });
  });

  group('Panel "BÁO CÁO XỬ LÝ HIỆN TRƯỜNG"', () {
    /// Mở màn danh sách với 1 sự cố đang do bảo vệ này xử lý, mở panel và bấm "Xử lý xong".
    Future<void> openSheetAndSubmit(WidgetTester tester) async {
      tester.view.physicalSize = const Size(1400, 2000);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);

      service.server['inc-1'] = IncidentModel(
          id: 'inc-1', cameraCode: 'CAM-001', areaName: 'Phòng Lab', eventType: 'INTRUSION', status: 'CLAIMED',
          claimedById: 'guard-me', claimedByName: 'Bảo vệ An');
      await tester.pumpWidget(ChangeNotifierProvider<IncidentProvider>.value(
        value: provider,
        child: const MaterialApp(home: IncidentListScreen()),
      ));
      await tester.pumpAndSettle();
      expect(provider.activeIncident?.id, equals('inc-1'));

      await tester.tap(find.text('Đang xử lý').first);
      await tester.pumpAndSettle();
      await tester.tap(find.text('GIẢI QUYẾT'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Báo cáo xử lý'));
      await tester.pumpAndSettle();
      expect(find.text('BÁO CÁO XỬ LÝ HIỆN TRƯỜNG'), findsOneWidget);

      await tester.tap(find.text('Xử lý xong'));
      await tester.pumpAndSettle();
    }

    testWidgets('Resolve thành công: panel đóng, không còn sự cố đang xử lý', (tester) async {
      await openSheetAndSubmit(tester);

      expect(find.text('BÁO CÁO XỬ LÝ HIỆN TRƯỜNG'), findsNothing);
      expect(find.text('Xử lý xong'), findsNothing);
      expect(provider.activeIncident, isNull);
      expect(find.text('Đã hoàn tất xử lý và đóng sự cố thành công.'), findsOneWidget);

      provider.dispose();
    });

    testWidgets('Resolve thất bại: panel còn, hiện lỗi', (tester) async {
      service.resolveFails = true;
      await openSheetAndSubmit(tester);

      expect(find.text('BÁO CÁO XỬ LÝ HIỆN TRƯỜNG'), findsOneWidget);
      expect(find.text('Xử lý xong'), findsOneWidget);
      expect(provider.activeIncident?.id, equals('inc-1'));
      expect(find.text('Sự cố đã được xử lý'), findsOneWidget);

      provider.dispose();
    });
  });
}
