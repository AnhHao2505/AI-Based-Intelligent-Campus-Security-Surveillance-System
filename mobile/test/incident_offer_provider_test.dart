import 'package:dio/dio.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/providers/incident_provider.dart';
import 'package:guard_mobile_app/features/incident/services/incident_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// IncidentService giả: claim / decline trả lỗi HTTP cấu hình sẵn (null = thành công). Không gọi mạng.
class FakeIncidentService extends IncidentService {
  int? claimStatus;
  Map<String, dynamic>? claimBody;
  int? declineStatus;
  final List<String> declined = [];

  FakeIncidentService() : super(ApiClient());

  DioException _error(int status, Map<String, dynamic>? body) {
    final options = RequestOptions(path: '/api/incidents');
    return DioException(
      requestOptions: options,
      response: Response(requestOptions: options, statusCode: status, data: body),
      type: DioExceptionType.badResponse,
    );
  }

  @override
  Future<Map<String, dynamic>> claimIncident(String incidentId) async {
    if (claimStatus != null) throw _error(claimStatus!, claimBody);
    return {'success': true};
  }

  @override
  Future<void> declineOffer(String incidentId) async {
    declined.add(incidentId);
    if (declineStatus != null) throw _error(declineStatus!, {'message': 'Lời mời không còn hiệu lực'});
  }
}

/// Message INCIDENT_OFFER theo hợp đồng v2 (K6): { type, offerId, incidentId, expiresAt, incident: IncidentDetailResponse }.
Map<String, dynamic> offerMessage(String incidentId,
    {String offerId = 'off-1', Duration expiresIn = const Duration(seconds: 30), String severity = 'HIGH'}) {
  return {
    'type': 'INCIDENT_OFFER',
    'offerId': offerId,
    'incidentId': incidentId,
    'expiresAt': DateTime.now().add(expiresIn).toUtc().toIso8601String(),
    'incident': {
      'id': incidentId,
      'cameraCode': 'CAM-001',
      'areaName': 'Phòng Lab',
      'building': 'ALPHA',
      'eventType': 'INTRUSION',
      'detectedAt': DateTime.now().toUtc().toIso8601String(),
      'status': 'NEW',
      'severity': severity,
      'dispatchStage': 'STEP1',
    },
  };
}

/// Chờ các Future của handler (đọc SharedPreferences) chạy xong.
Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 20));

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
    SharedPreferences.setMockInitialValues({});
    service = FakeIncidentService();
    provider = IncidentProvider(incidentService: service, stompService: StompService());
  });

  tearDown(() => provider.dispose());

  group('Lời mời (INCIDENT_OFFER / INCIDENT_OFFER_CANCELLED)', () {
    test('Offer tới: hiện lời mời, sự cố vào hàng đợi ở STEP1 và KHÔNG khoá với bảo vệ được mời', () {
      provider.handleOfferMessage(offerMessage('inc-1'));

      expect(provider.currentOffer?.incidentId, equals('inc-1'));
      expect(provider.hasOfferFor('inc-1'), isTrue);
      expect(provider.alertQueue.single.id, equals('inc-1'));
      expect(provider.alertQueue.single.isOfferPhase, isTrue);
      expect(provider.isClaimLockedFor(provider.alertQueue.single), isFalse);
    });

    test('Offer bị huỷ: ẩn lời mời; sự cố còn STEP1 nên nút claim bị khoá', () {
      provider.handleOfferMessage(offerMessage('inc-1', offerId: 'off-1'));
      provider.handleOfferMessage({'type': 'INCIDENT_OFFER_CANCELLED', 'incidentId': 'inc-1', 'offerId': 'off-1', 'reason': 'SHIFT_ENDED'});

      expect(provider.currentOffer, isNull);
      expect(provider.isClaimLockedFor(provider.alertQueue.single), isTrue);
    });

    test('Huỷ cho offerId cũ không xoá lời mời mới hơn của cùng sự cố', () {
      provider.handleOfferMessage(offerMessage('inc-1', offerId: 'off-2'));
      provider.handleOfferMessage({'type': 'INCIDENT_OFFER_CANCELLED', 'incidentId': 'inc-1', 'offerId': 'off-1'});

      expect(provider.currentOffer?.offerId, equals('off-2'));
    });

    test('Offer hết hạn: tự ẩn đúng lúc expiresAt', () async {
      provider.handleOfferMessage(offerMessage('inc-1', expiresIn: const Duration(milliseconds: 300)));
      expect(provider.currentOffer, isNotNull);

      await Future<void>.delayed(const Duration(milliseconds: 450));
      expect(provider.currentOffer, isNull);
      expect(provider.isClaimLockedFor(provider.alertQueue.single), isTrue);
    });

    test('Offer đã hết hạn khi tới, type lạ, hoặc thiếu expiresAt: không hiện gì mới', () {
      provider.handleOfferMessage(offerMessage('inc-1', expiresIn: const Duration(seconds: -5)));
      provider.handleOfferMessage({'type': 'SOMETHING_ELSE', 'incidentId': 'inc-2'});
      provider.handleOfferMessage({'type': 'INCIDENT_OFFER', 'incidentId': 'inc-3'});

      expect(provider.currentOffer, isNull);
      expect(provider.alertQueue, isEmpty);
    });

    test('Message bọc trong envelope data vẫn đọc được', () {
      final inner = offerMessage('inc-1')..remove('type');
      provider.handleOfferMessage({'type': 'INCIDENT_OFFER', 'data': inner});
      expect(provider.currentOffer?.incidentId, equals('inc-1'));
      expect(provider.currentOffer?.areaName, equals('Phòng Lab'));
    });

    test('Có offeredAt: đếm ngược = expiresAt - offeredAt tính từ lúc nhận, không phụ thuộc giờ server', () {
      // Đồng hồ server chạy chậm 10 phút so với điện thoại: expiresAt theo giờ server đã "qua" theo giờ điện thoại,
      // nhưng lời mời vẫn còn 30 giây vì tính theo thời lượng.
      final serverNow = DateTime.now().subtract(const Duration(minutes: 10)).toUtc();
      provider.handleOfferMessage({
        ...offerMessage('inc-1'),
        'offeredAt': serverNow.toIso8601String(),
        'expiresAt': serverNow.add(const Duration(seconds: 30)).toIso8601String(),
      });

      final offer = provider.currentOffer;
      expect(offer, isNotNull);
      final remaining = offer!.remaining(DateTime.now()).inSeconds;
      expect(remaining, inInclusiveRange(28, 30));
    });

    test('Nhiều lời mời: currentOffer là lời mời hết hạn sớm nhất', () {
      provider.handleOfferMessage(offerMessage('inc-late', offerId: 'a', expiresIn: const Duration(seconds: 60)));
      provider.handleOfferMessage(offerMessage('inc-soon', offerId: 'b', expiresIn: const Duration(seconds: 20)));
      expect(provider.offers.length, equals(2));
      expect(provider.currentOffer?.incidentId, equals('inc-soon'));
    });

    test('Có người claim (update CLAIMED): lời mời bị gỡ', () async {
      provider.handleOfferMessage(offerMessage('inc-1'));
      provider.receiveUpdateForTest({'incidentId': 'inc-1', 'status': 'CLAIMED', 'claimedById': 'guard-b', 'claimedByName': 'B'});
      await settle();

      expect(provider.currentOffer, isNull);
      expect(provider.alertQueue, isEmpty);
    });
  });

  group('Chuyển BROADCAST và bảo vệ không được mời', () {
    test('Update STEP1 cho sự cố mới: vào danh sách, nút claim khoá; update BROADCAST: mở khoá', () async {
      provider.receiveUpdateForTest({'incidentId': 'inc-1', 'status': 'NEW', 'dispatchStage': 'STEP1', 'severity': 'HIGH', 'eventType': 'INTRUSION'});
      await settle();
      expect(provider.alertQueue.single.id, equals('inc-1'));
      expect(provider.isClaimLockedFor(provider.alertQueue.single), isTrue);

      provider.receiveUpdateForTest({'incidentId': 'inc-1', 'status': 'NEW', 'dispatchStage': 'BROADCAST'});
      await settle();
      expect(provider.alertQueue.single.dispatchStage, equals('BROADCAST'));
      expect(provider.isClaimLockedFor(provider.alertQueue.single), isFalse);
    });

    test('Cảnh báo broadcast tới sau khi đã thấy sự cố lúc STEP1: cập nhật stage, không thêm bản trùng', () async {
      provider.receiveUpdateForTest({'incidentId': 'inc-1', 'status': 'NEW', 'dispatchStage': 'STEP1', 'eventType': 'INTRUSION'});
      await settle();

      provider.receiveIncidentForTest(IncidentModel(
        id: 'inc-1', cameraCode: 'CAM-001', eventType: 'INTRUSION', status: 'NEW', severity: 'HIGH', dispatchStage: 'BROADCAST'));
      await settle();

      expect(provider.alertQueue.length, equals(1));
      expect(provider.alertQueue.single.severity, equals('HIGH'));
      expect(provider.isClaimLockedFor(provider.alertQueue.single), isFalse);
    });

    test('Backend cũ: update không có dispatchStage không thêm sự cố; cảnh báo stage null không khoá', () async {
      provider.receiveUpdateForTest({'incidentId': 'inc-x', 'status': 'NEW'});
      await settle();
      expect(provider.alertQueue, isEmpty);

      provider.receiveIncidentForTest(IncidentModel(id: 'inc-2', cameraCode: 'CAM-002', eventType: 'INTRUSION', status: 'NEW'));
      await settle();
      expect(provider.isClaimLockedFor(provider.alertQueue.single), isFalse);
      expect(provider.currentOffer, isNull);
    });
  });

  group('Sắp xếp hàng đợi', () {
    test('Severity server trước, severityWeight cũ làm tie-break', () async {
      final now = DateTime.now();
      provider.receiveIncidentForTest(IncidentModel(
          id: 'legacy-w2', cameraCode: 'C', eventType: 'INTRUSION', status: 'NEW', detectedAt: now));
      provider.receiveIncidentForTest(IncidentModel(
          id: 'srv-critical', cameraCode: 'C', eventType: 'UNKNOWN_PERSON', status: 'NEW', severity: 'CRITICAL', detectedAt: now));
      provider.receiveIncidentForTest(IncidentModel(
          id: 'srv-medium', cameraCode: 'C', eventType: 'UNAUTHORIZED_ACCESS', status: 'NEW', severity: 'MEDIUM', detectedAt: now));
      // Hàng đợi sắp xếp sau debounce 250 ms
      await Future<void>.delayed(const Duration(milliseconds: 350));

      expect(provider.alertQueue.map((i) => i.id).toList(), equals(['srv-critical', 'legacy-w2', 'srv-medium']));
    });

    test('Cập nhật severity qua /topic/incidents/updates sắp xếp lại hàng đợi', () async {
      final now = DateTime.now();
      provider.receiveIncidentForTest(IncidentModel(id: 'a', cameraCode: 'C', eventType: 'UNAUTHORIZED_ACCESS', status: 'NEW', detectedAt: now));
      provider.receiveIncidentForTest(IncidentModel(id: 'b', cameraCode: 'C', eventType: 'UNKNOWN_PERSON', status: 'NEW', detectedAt: now));
      await Future<void>.delayed(const Duration(milliseconds: 350));
      expect(provider.alertQueue.first.id, equals('a'));

      provider.receiveUpdateForTest({'incidentId': 'a', 'status': 'NEW', 'severity': 'MEDIUM'});
      provider.receiveUpdateForTest({'incidentId': 'b', 'status': 'NEW', 'severity': 'CRITICAL'});
      await settle();
      expect(provider.alertQueue.map((i) => i.id).toList(), equals(['b', 'a']));
    });
  });

  group('Lỗi claim và từ chối', () {
    test('409 có message server: hiện message server, gỡ khỏi hàng đợi và gỡ lời mời', () async {
      provider.handleOfferMessage(offerMessage('inc-1'));
      service.claimStatus = 409;
      service.claimBody = {'message': 'Sự việc vừa được tiếp nhận bởi Bảo vệ B', 'errorCode': 'INCIDENT_ALREADY_CLAIMED'};

      final ok = await provider.claimIncident('inc-1');

      expect(ok, isFalse);
      expect(provider.errorMessage, equals('Sự việc vừa được tiếp nhận bởi Bảo vệ B'));
      expect(provider.alertQueue, isEmpty);
      expect(provider.currentOffer, isNull);
    });

    test('409 không có message server: "Đã có người nhận"', () async {
      provider.handleOfferMessage(offerMessage('inc-1'));
      service.claimStatus = 409;
      service.claimBody = {'errorCode': 'INCIDENT_ALREADY_CLAIMED', 'message': '  '};

      await provider.claimIncident('inc-1');
      expect(provider.errorMessage, equals('Đã có người nhận'));

      expect(IncidentProvider.conflictMessageFrom(null), equals('Đã có người nhận'));
      expect(IncidentProvider.conflictMessageFrom('raw text'), equals('Đã có người nhận'));
    });

    test('403 INCIDENT_NOT_OFFERED: "Sự cố đang được mời bảo vệ khác", sự cố bị khoá', () async {
      provider.receiveIncidentForTest(IncidentModel(id: 'inc-1', cameraCode: 'C', eventType: 'INTRUSION', status: 'NEW'));
      await settle();
      service.claimStatus = 403;
      service.claimBody = {'message': 'Not offered', 'errorCode': 'INCIDENT_NOT_OFFERED', 'code': 'INCIDENT_NOT_OFFERED'};

      final ok = await provider.claimIncident('inc-1');

      expect(ok, isFalse);
      expect(provider.errorMessage, equals('Sự cố đang được mời bảo vệ khác'));
      expect(provider.alertQueue.single.isOfferPhase, isTrue);
      expect(provider.isClaimLockedFor(provider.alertQueue.single), isTrue);
    });

    test('403 khác (không phải INCIDENT_NOT_OFFERED) giữ cách báo lỗi cũ', () async {
      provider.receiveIncidentForTest(IncidentModel(id: 'inc-1', cameraCode: 'C', eventType: 'INTRUSION', status: 'NEW'));
      await settle();
      service.claimStatus = 403;
      service.claimBody = {'message': 'Bạn không đang trực'};

      await provider.claimIncident('inc-1');
      expect(provider.errorMessage, equals('Bạn không đang trực'));
      expect(provider.isClaimLockedFor(provider.alertQueue.single), isFalse);
    });

    test('Nhận từ lời mời thành công: sự cố thành active (dữ liệu từ lời mời), gỡ lời mời và khỏi hàng đợi', () async {
      provider.handleOfferMessage(offerMessage('inc-1'));

      final ok = await provider.claimIncident('inc-1');

      expect(ok, isTrue);
      expect(provider.activeIncident?.id, equals('inc-1'));
      expect(provider.activeIncident?.status, equals('CLAIMED'));
      expect(provider.activeIncident?.cameraCode, equals('CAM-001'));
      expect(provider.currentOffer, isNull);
      expect(provider.alertQueue, isEmpty);
    });

    test('Từ chối thành công: gọi decline và ẩn lời mời', () async {
      provider.handleOfferMessage(offerMessage('inc-1'));

      final ok = await provider.declineOffer('inc-1');

      expect(ok, isTrue);
      expect(service.declined, equals(['inc-1']));
      expect(provider.currentOffer, isNull);
    });

    test('Từ chối khi lời mời không còn (404): ẩn lời mời và báo lỗi', () async {
      provider.handleOfferMessage(offerMessage('inc-1'));
      service.declineStatus = 404;

      final ok = await provider.declineOffer('inc-1');

      expect(ok, isFalse);
      expect(provider.currentOffer, isNull);
      expect(provider.errorMessage, isNotNull);
    });

    test('isNotOfferedError đọc errorCode hoặc code', () {
      expect(IncidentProvider.isNotOfferedError({'errorCode': 'INCIDENT_NOT_OFFERED'}), isTrue);
      expect(IncidentProvider.isNotOfferedError({'code': 'INCIDENT_NOT_OFFERED'}), isTrue);
      expect(IncidentProvider.isNotOfferedError({'errorCode': 'ACCESS_DENIED'}), isFalse);
      expect(IncidentProvider.isNotOfferedError('INCIDENT_NOT_OFFERED'), isFalse);
    });
  });
}
