import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/providers/incident_provider.dart';
import 'package:guard_mobile_app/features/incident/services/incident_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// V129: AI nhận ra mặt sau cảnh báo người lạ -> backend gửi /topic/incidents/updates kèm identityUpdate / identityNotice.
IncidentModel unknownIncident(String id) => IncidentModel.fromJson({
      'id': id,
      'cameraCode': 'CAM-001',
      'areaName': 'Phòng Lab 1',
      'building': 'ALPHA',
      'eventType': 'UNKNOWN_PERSON',
      'resolutionNotes': 'Vị trí: Phòng Lab 1',
      'detectedAt': DateTime.now().toUtc().toIso8601String(),
      'status': 'NEW',
    });

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 20));

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async => null);
  });

  late IncidentProvider provider;

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    provider = IncidentProvider(incidentService: IncidentService(ApiClient()), stompService: StompService());
  });

  tearDown(() => provider.dispose());

  test('Tự đóng (AUTHORIZED): gỡ sự cố khỏi hàng đợi và hiện câu báo', () async {
    provider.receiveIncidentForTest(unknownIncident('inc-1'));
    await settle();

    provider.receiveUpdateForTest({
      'incidentId': 'inc-1',
      'status': 'RESOLVED_DISMISSED',
      'autoResolved': true,
      'identityUpdate': true,
      'resolvedByName': null,
      'identityNotice': 'Sự cố người lạ tại Phòng Lab 1 đã tự đóng: AI xác minh Minh (SE1) có quyền ra vào.',
    });
    await settle();

    expect(provider.alertQueue, isEmpty);
    expect(provider.peerClaimNotice, contains('đã tự đóng'));
  });

  test('UNAUTHORIZED: cập nhật loại và nội dung sự cố trong hàng đợi, giữ trạng thái NEW', () async {
    provider.receiveIncidentForTest(unknownIncident('inc-2'));
    await settle();

    provider.receiveUpdateForTest({
      'incidentId': 'inc-2',
      'identityUpdate': true,
      'eventType': 'UNAUTHORIZED_PERSON',
      'resolutionNotes': 'Tên: Minh (SE1)\nLý do: Cấp độ không đủ\nVị trí: Phòng Lab 1',
      'identityNotice': 'Sự cố tại Phòng Lab 1: AI nhận diện được Minh (SE1), không có quyền ra vào.',
    });
    await settle();

    final incident = provider.alertQueue.single;
    expect(incident.eventType, equals('UNAUTHORIZED_PERSON'));
    expect(incident.resolutionNotes, contains('Cấp độ không đủ'));
    expect(incident.isNew, isTrue);
    expect(provider.peerClaimNotice, contains('không có quyền'));
  });

  test('Sự cố không có trong máy này: không hiện câu báo', () async {
    provider.receiveUpdateForTest({
      'incidentId': 'inc-other',
      'status': 'RESOLVED_DISMISSED',
      'identityUpdate': true,
      'identityNotice': 'Sự cố người lạ tại Phòng Lab 2 đã tự đóng.',
    });
    await settle();

    expect(provider.peerClaimNotice, isNull);
  });
}
