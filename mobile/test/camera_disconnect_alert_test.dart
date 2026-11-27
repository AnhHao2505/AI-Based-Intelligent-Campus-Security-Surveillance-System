import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/core/notifications/local_notification_service.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/providers/incident_provider.dart';
import 'package:guard_mobile_app/features/incident/services/incident_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:stomp_dart_client/stomp.dart';
import 'package:stomp_dart_client/stomp_config.dart';
import 'package:stomp_dart_client/stomp_frame.dart';
import 'package:stomp_dart_client/stomp_handler.dart';

const _me = {
  'id': '11111111-1111-1111-1111-111111111111',
  'fullName': 'Bảo vệ Minh',
  'email': 'guard.minh@fpt.edu.vn',
  'role': 'GUARD',
};
const _token = 'test-jwt';

class _SubscribingFakeClient extends StompClient {
  _SubscribingFakeClient(StompConfig config) : super(config: config);

  final Map<String, StompFrameCallback> subscriptions = {};

  @override
  void activate() {
    config.onConnect(StompFrame(command: 'CONNECTED'));
  }

  @override
  StompUnsubscribe subscribe({
    required String destination,
    required StompFrameCallback callback,
    Map<String, String>? headers,
  }) {
    subscriptions[destination] = callback;
    return ({Map<String, String>? unsubscribeHeaders}) {
      subscriptions.remove(destination);
    };
  }

  @override
  void deactivate() {
    subscriptions.clear();
  }
}

class _NoNetworkIncidentService extends IncidentService {
  _NoNetworkIncidentService() : super(ApiClient());

  @override
  Future<List<IncidentModel>> getActiveIncidents({String? building}) async => const [];
}

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({'guard_user_data': jsonEncode(_me)});
    FlutterSecureStorage.setMockInitialValues({'guard_auth_token': _token});
  });

  test('V151: StompService đăng ký /topic/guards/{id}/camera-alerts và chuyển tiếp CAMERA_OFFLINE / CAMERA_ONLINE không đẩy vào hàng đợi sự cố', () async {
    _SubscribingFakeClient? fakeClient;
    final stomp = StompService(
      clientFactory: (cfg) => fakeClient = _SubscribingFakeClient(cfg),
      tokenReader: () async => _token,
    );
    final provider = IncidentProvider(
      incidentService: _NoNetworkIncidentService(),
      stompService: stomp,
    );

    provider.initStomp();
    await Future<void>.delayed(const Duration(milliseconds: 30));

    const expectedTopic = '/topic/guards/11111111-1111-1111-1111-111111111111/camera-alerts';
    expect(fakeClient, isNotNull);
    expect(fakeClient!.subscriptions.containsKey(expectedTopic), isTrue);

    fakeClient!.subscriptions[expectedTopic]!(
      StompFrame(
        command: 'MESSAGE',
        body: jsonEncode({
          'event': 'CAMERA_OFFLINE',
          'cameraId': 'cam-uuid-1',
          'cameraCode': 'CAM-001',
          'cameraName': 'Góc cửa chính',
          'areaName': 'Phòng Server',
          'building': 'Tòa Alpha',
          'floor': 'Tầng 2',
          'offlineSince': '2026-11-27T10:00:00+07:00',
        }),
      ),
    );
    await Future<void>.delayed(const Duration(milliseconds: 30));

    // Không làm bẩn hàng đợi sự cố an ninh
    expect(provider.alertQueue, isEmpty);
    expect(provider.activeIncident, isNull);

    // Gửi CAMERA_ONLINE thu hồi không phát sinh lỗi
    fakeClient!.subscriptions[expectedTopic]!(
      StompFrame(
        command: 'MESSAGE',
        body: jsonEncode({
          'event': 'CAMERA_ONLINE',
          'cameraId': 'cam-uuid-1',
          'cameraCode': 'CAM-001',
        }),
      ),
    );
    await Future<void>.delayed(const Duration(milliseconds: 30));
    expect(provider.alertQueue, isEmpty);
  });

  test('V151: cameraNotificationIdFor tách không gian id với sự cố thông thường cùng chuỗi UUID', () {
    const sampleUuid = '22222222-2222-2222-2222-222222222222';
    expect(
      LocalNotificationService.cameraNotificationIdFor(sampleUuid),
      isNot(equals(LocalNotificationService.idFor(sampleUuid))),
    );
  });
}
