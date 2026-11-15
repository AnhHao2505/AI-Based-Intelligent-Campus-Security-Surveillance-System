import 'dart:async';
import 'dart:convert';

import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/core/utils/storage_helper.dart';
import 'package:guard_mobile_app/features/auth/providers/auth_provider.dart';
import 'package:guard_mobile_app/features/auth/services/guard_session_teardown.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/providers/incident_provider.dart';
import 'package:guard_mobile_app/features/incident/services/incident_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:guard_mobile_app/features/shift/providers/shift_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:stomp_dart_client/stomp.dart';
import 'package:stomp_dart_client/stomp_config.dart';

/// DEF-16: máy đã đăng xuất vẫn bị mời nhận sự cố vì còn kết nối STOMP mồ côi.

const _me = {'id': 'guard-me', 'fullName': 'Bảo vệ An', 'email': 'guard.an@fpt.edu.vn', 'role': 'GUARD'};
const _token = 'test-only-jwt';

/// Client giả: không mở socket, chỉ đếm activate / deactivate.
class _FakeClient extends StompClient {
  _FakeClient(StompConfig config) : super(config: config);

  int activations = 0;
  int deactivations = 0;

  @override
  void activate() => activations++;

  @override
  void deactivate() => deactivations++;
}

class _ClientRecorder {
  final List<_FakeClient> clients = [];

  StompClient create(StompConfig config) {
    final client = _FakeClient(config);
    clients.add(client);
    return client;
  }

  int get activated => clients.fold(0, (sum, c) => sum + c.activations);
}

class _NoNetworkIncidentService extends IncidentService {
  _NoNetworkIncidentService() : super(ApiClient());

  @override
  Future<List<IncidentModel>> getActiveIncidents({String? building}) async => const [];
}

void _connect(StompService stomp, {String? guardId = 'guard-me'}) {
  stomp.connect(guardId: guardId, onNewIncident: (_) {}, onIncidentUpdate: (_) {});
}

/// Chờ các Future (đọc token / SharedPreferences) chạy xong.
Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 20));

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

  setUp(() {
    SharedPreferences.setMockInitialValues({'guard_user_data': jsonEncode(_me)});
    FlutterSecureStorage.setMockInitialValues({'guard_auth_token': _token});
  });

  group('StompService: tối đa 1 client', () {
    test('connect() 2 lần liên tiếp không await → chỉ 1 client được tạo và activate', () async {
      final recorder = _ClientRecorder();
      final stomp = StompService(clientFactory: recorder.create, tokenReader: () async => _token);

      _connect(stomp);
      _connect(stomp);
      await settle();

      expect(recorder.clients, hasLength(1));
      expect(recorder.activated, 1);
      expect(stomp.hasClient, isTrue);

      // Gọi lại khi đã có client cùng guardId → không làm gì
      _connect(stomp);
      await settle();
      expect(recorder.clients, hasLength(1));
      expect(recorder.clients.single.deactivations, 0);
    });

    test('connect() 2 lần khác guardId khi token chưa đọc xong → chỉ client của lần sau', () async {
      final recorder = _ClientRecorder();
      final stomp = StompService(clientFactory: recorder.create, tokenReader: () async => _token);

      _connect(stomp, guardId: 'guard-old');
      _connect(stomp, guardId: 'guard-new');
      await settle();

      expect(recorder.clients, hasLength(1));
      expect(recorder.activated, 1);
    });

    test('connect() rồi disconnect() trước khi token đọc xong → không client nào được activate', () async {
      final recorder = _ClientRecorder();
      final token = Completer<String?>();
      final stomp = StompService(clientFactory: recorder.create, tokenReader: () => token.future);

      _connect(stomp);
      stomp.disconnect();
      token.complete(_token);
      await settle();

      expect(recorder.clients, isEmpty);
      expect(stomp.hasClient, isFalse);
      expect(stomp.isConnected, isFalse);
    });

    test('disconnect() deactivate client đang giữ (dừng cả vòng nối lại), connect() sau đó tạo client mới', () async {
      final recorder = _ClientRecorder();
      final stomp = StompService(clientFactory: recorder.create, tokenReader: () async => _token);

      _connect(stomp);
      await settle();
      stomp.disconnect();

      expect(recorder.clients.single.deactivations, 1);
      expect(stomp.hasClient, isFalse);

      _connect(stomp);
      await settle();
      expect(recorder.clients, hasLength(2));
      expect(recorder.clients.last.activations, 1);
    });
  });

  group('IncidentProvider.initStomp (3 nơi gọi: watcher, Lịch Trực, danh sách sự cố)', () {
    test('initStomp() 2 lần liên tiếp → chỉ 1 client', () async {
      final recorder = _ClientRecorder();
      final stomp = StompService(clientFactory: recorder.create, tokenReader: () async => _token);
      final provider = IncidentProvider(incidentService: _NoNetworkIncidentService(), stompService: stomp);
      addTearDown(provider.dispose);

      provider.initStomp();
      provider.initStomp();
      await settle();

      expect(recorder.clients, hasLength(1));
      expect(recorder.activated, 1);
    });

    test('initStomp() rồi disconnectStomp() khi đang đọc user → không kết nối', () async {
      final recorder = _ClientRecorder();
      final stomp = StompService(clientFactory: recorder.create, tokenReader: () async => _token);
      final provider = IncidentProvider(incidentService: _NoNetworkIncidentService(), stompService: stomp);
      addTearDown(provider.dispose);

      provider.initStomp();
      provider.disconnectStomp();
      await settle();

      expect(recorder.clients, isEmpty);
      expect(stomp.hasClient, isFalse);
    });
  });

  test('logout(): ngắt STOMP → dừng service trực → xoá sự cố → xoá ca → xoá token', () async {
    final recorder = _ClientRecorder();
    final stomp = StompService(clientFactory: recorder.create, tokenReader: () async => _token);
    final incidents = IncidentProvider(incidentService: _NoNetworkIncidentService(), stompService: stomp);
    addTearDown(incidents.dispose);

    // Ca đang trực (CHECKED_IN) lấy qua Dio giả
    final api = ApiClient();
    api.dio.interceptors.add(InterceptorsWrapper(
      onRequest: (options, handler) => handler.resolve(Response(
        requestOptions: options,
        statusCode: 200,
        data: [
          {'id': 'shift-1', 'shiftDate': '2026-11-14', 'status': 'CHECKED_IN'},
        ],
      )),
    ));
    final shifts = ShiftProvider(api);
    await shifts.fetchWeeklyShifts();
    expect(shifts.shifts.single.isCheckedIn, isTrue);

    incidents.initStomp();
    await settle();
    incidents.handleOfferMessage(_offerMessage('inc-1'));
    expect(stomp.hasClient, isTrue);
    expect(incidents.offers, hasLength(1));
    expect(incidents.alertQueue, hasLength(1));

    final steps = <String>[];
    final teardown = GuardSessionTeardown(
      incidentProvider: incidents,
      shiftProvider: shifts,
      stopDutyService: () async {
        steps.add('stop duty: client=${stomp.hasClient} queue=${incidents.alertQueue.length} '
            'shifts=${shifts.shifts.length} token=${await StorageHelper.getToken()}');
      },
    );
    final auth = AuthProvider(api, beforeClearAuth: teardown.call);

    await auth.logout();

    // Lúc dừng service: STOMP đã ngắt; sự cố, ca, token chưa xoá
    expect(steps, ['stop duty: client=false queue=1 shifts=1 token=$_token']);
    expect(recorder.clients.single.deactivations, 1);
    expect(stomp.hasClient, isFalse);
    expect(stomp.isConnected, isFalse);
    expect(incidents.offers, isEmpty);
    expect(incidents.alertQueue, isEmpty);
    expect(incidents.activeIncident, isNull);
    expect(shifts.shifts, isEmpty);
    expect(await StorageHelper.getToken(), isNull);
    expect(auth.isAuthenticated, isFalse);
  });
}
