import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/models/incident_queue_order.dart';
import 'package:guard_mobile_app/features/incident/providers/incident_provider.dart';
import 'package:guard_mobile_app/features/incident/screens/incident_list_screen.dart';
import 'package:guard_mobile_app/features/incident/services/incident_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _meId = 'guard-me';
const _me = {'id': _meId, 'fullName': 'Bảo vệ An', 'email': 'guard.an@fpt.edu.vn', 'role': 'GUARD'};

class FakeIncidentService extends IncidentService {
  List<IncidentModel> active = [];
  FakeIncidentService() : super(ApiClient());

  @override
  Future<List<IncidentModel>> getActiveIncidents({String? building}) async => active;

  @override
  Future<Map<String, dynamic>> claimIncident(String incidentId) async => {'success': true};
}

IncidentModel inc(String id,
        {String building = 'Tòa Alpha',
        String status = 'NEW',
        String eventType = 'UNAUTHORIZED_PERSON',
        String? severity = 'HIGH',
        DateTime? at,
        String? claimedById,
        String? resolvedById}) =>
    IncidentModel(
      id: id,
      cameraCode: 'CAM',
      building: building,
      eventType: eventType,
      status: status,
      severity: severity,
      dispatchStage: 'BROADCAST',
      detectedAt: at,
      claimedById: claimedById,
      resolvedById: resolvedById,
    );

Map<String, dynamic> offerFor(String id, String building) => {
      'type': 'INCIDENT_OFFER',
      'offerId': 'off-$id',
      'incidentId': id,
      'expiresAt': DateTime.now().add(const Duration(seconds: 30)).toUtc().toIso8601String(),
      'incident': {
        'id': id,
        'cameraCode': 'CAM',
        'building': building,
        'eventType': 'UNAUTHORIZED_PERSON',
        'status': 'NEW',
        'severity': 'HIGH',
        'dispatchStage': 'STEP1',
      },
    };

Future<void> settle() => Future<void>.delayed(const Duration(milliseconds: 300));

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
    provider = IncidentProvider(incidentService: service, stompService: StompService());
  });

  tearDown(() => provider.dispose());

  List<String> ids(List<IncidentModel> list) => list.map((i) => i.id).toList();

  group('B4 — L12: cùng mức thì cũ trước, hàng đợi và màn danh sách dùng chung một hàm', () {
    final t0 = DateTime(2026, 11, 7, 0, 22, 0);

    test('compareIncidentQueueOrder: mức ↓ → trọng số nhãn ↓ → cũ trước; không có giờ đứng sau', () {
      final list = [
        inc('high-new', at: t0.add(const Duration(minutes: 5))),
        inc('crit', severity: 'CRITICAL', at: t0.add(const Duration(minutes: 9))),
        inc('high-old', at: t0),
        inc('high-nodate'),
        inc('high-mid', at: t0.add(const Duration(minutes: 2))),
        inc('med', severity: 'MEDIUM', at: t0.subtract(const Duration(days: 1))),
      ]..sort(compareIncidentQueueOrder);
      expect(ids(list), ['crit', 'high-old', 'high-mid', 'high-new', 'high-nodate', 'med']);
    });

    test('Cùng mức server khác nhãn: trọng số nhãn cao hơn đứng trước rồi mới tới giờ', () {
      final list = [
        inc('intrusion-old', eventType: 'PERSON_INTRUSION', severity: null, at: t0),
        inc('unauth-new', eventType: 'UNAUTHORIZED_PERSON', severity: null, at: t0.add(const Duration(minutes: 1))),
      ]..sort(compareIncidentQueueOrder);
      expect(ids(list), ['unauth-new', 'intrusion-old']);
    });

    test('_sortQueue (alertQueue) và tab "Cần xử lý" cho cùng thứ tự, cũ trước', () async {
      provider.receiveIncidentForTest(inc('b-new', at: t0.add(const Duration(minutes: 3))));
      provider.receiveIncidentForTest(inc('a-old', at: t0));
      provider.receiveIncidentForTest(inc('c-crit', severity: 'CRITICAL', at: t0.add(const Duration(minutes: 9))));
      await settle();

      final history = [inc('h-mid', at: t0.add(const Duration(minutes: 1)))];
      final tab = IncidentListScreen.buildTabList(
          history: history, provider: provider, tab: IncidentFilterTab.pending, currentUserId: _meId);

      expect(ids(provider.alertQueue), ['c-crit', 'a-old', 'b-new']);
      expect(ids(tab), ['c-crit', 'a-old', 'h-mid', 'b-new']);
      final expected = [...provider.alertQueue, ...history]..sort(compareIncidentQueueOrder);
      expect(ids(tab), ids(expected));
    });
  });

  group('B5 — L5: chỉ sự cố của toà đang trực, cộng sự cố được mời / đang xử lý', () {
    test('Chưa đặt toà (null): không lọc (giữ hành vi cũ)', () async {
      provider.receiveIncidentForTest(inc('beta', building: 'TOA_BETA'));
      await settle();
      expect(ids(provider.alertQueue), ['beta']);
    });

    test('Cảnh báo /alerts của toà khác bị loại; cùng toà (khác hoa thường, khoảng trắng) được giữ', () async {
      provider.setDutyBuildings({'Tòa Alpha'});
      provider.receiveIncidentForTest(inc('beta', building: 'TOA_BETA'));
      provider.receiveIncidentForTest(inc('alpha', building: ' tòa alpha '));
      await settle();
      expect(ids(provider.alertQueue), ['alpha']);
    });

    test('Lời mời của sự cố toà khác vẫn vào hàng đợi', () async {
      provider.setDutyBuildings({'Tòa Alpha'});
      provider.handleOfferMessage(offerFor('beta-offer', 'TOA_BETA'));
      expect(ids(provider.alertQueue), ['beta-offer']);
      // Tin BROADCAST cùng sự cố tới sau vẫn được nhận (đang được mời)
      provider.receiveIncidentForTest(inc('beta-offer', building: 'TOA_BETA'));
      await settle();
      expect(ids(provider.alertQueue), ['beta-offer']);
    });

    test('loadActiveIncidents: bỏ NEW toà khác, giữ sự cố mình đang xử lý dù khác toà', () async {
      service.active = [
        inc('alpha-new'),
        inc('beta-new', building: 'TOA_BETA'),
        inc('beta-mine', building: 'TOA_BETA', status: 'CLAIMED', claimedById: _meId),
      ];
      provider.setDutyBuildings({'Tòa Alpha'});
      await provider.loadActiveIncidents();

      expect(ids(provider.alertQueue), ['alpha-new']);
      expect(provider.activeIncident?.id, 'beta-mine');
      final claimedTab = IncidentListScreen.buildTabList(
          history: const [], provider: provider, tab: IncidentFilterTab.claimed, currentUserId: _meId);
      expect(ids(claimedTab), ['beta-mine']);
    });

    test('Đặt toà sau khi đã tải: sự cố toà khác bị bỏ khỏi hàng đợi', () async {
      provider.receiveIncidentForTest(inc('alpha'));
      provider.receiveIncidentForTest(inc('beta', building: 'TOA_BETA'));
      await settle();
      expect(provider.alertQueue.length, 2);

      expect(provider.setDutyBuildings({'Tòa Alpha'}), isTrue);
      expect(ids(provider.alertQueue), ['alpha']);
      expect(provider.setDutyBuildings({'tòa alpha'}), isFalse); // cùng tập sau chuẩn hoá
    });

    test('Không có ca đang trực (tập rỗng): chỉ còn sự cố được mời', () async {
      provider.setDutyBuildings(<String>{});
      provider.receiveIncidentForTest(inc('alpha'));
      provider.handleOfferMessage(offerFor('offered', 'Tòa Alpha'));
      await settle();
      expect(ids(provider.alertQueue), ['offered']);
    });

    test('Màn danh sách: lịch sử toà khác bị ẩn, trừ sự cố mình đã nhận / đã xử lý', () {
      provider.setDutyBuildings({'Tòa Alpha'});
      final history = [
        inc('alpha-new'),
        inc('beta-new', building: 'TOA_BETA'),
        inc('beta-resolved-by-me', building: 'TOA_BETA', status: 'RESOLVED_DISMISSED', resolvedById: _meId),
        inc('beta-resolved-other', building: 'TOA_BETA', status: 'RESOLVED_DISMISSED', resolvedById: 'x'),
      ];
      final all = IncidentListScreen.buildTabList(
          history: history, provider: provider, tab: IncidentFilterTab.all, currentUserId: _meId);
      expect(ids(all).toSet(), {'alpha-new', 'beta-resolved-by-me'});
    });
  });

  group('B7 — L10: sự cố đóng ngoài app rời tab "Đang xử lý"', () {
    test('Tin RESOLVED của sự cố đang xử lý: xoá activeIncident và bản lịch sử cũ (CLAIMED) chuyển sang "Đã giải quyết"',
        () async {
      provider.receiveIncidentForTest(inc('m2'));
      await settle();
      expect(await provider.claimIncident('m2'), isTrue);
      expect(provider.activeIncident?.id, 'm2');

      // Màn danh sách đã tải lịch sử SAU khi nhận → bản CLAIMED cũ
      final staleHistory = [inc('m2', status: 'CLAIMED', claimedById: _meId)];
      expect(
          ids(IncidentListScreen.buildTabList(
              history: staleHistory, provider: provider, tab: IncidentFilterTab.claimed, currentUserId: _meId)),
          ['m2']);

      provider.receiveUpdateForTest({
        'incidentId': 'm2',
        'status': 'RESOLVED_DISMISSED',
        'resolvedById': _meId,
        'resolvedByName': 'Bảo vệ An',
      });
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(provider.activeIncident, isNull);
      final claimedTab = IncidentListScreen.buildTabList(
          history: staleHistory, provider: provider, tab: IncidentFilterTab.claimed, currentUserId: _meId);
      expect(claimedTab, isEmpty);
      final resolvedTab = IncidentListScreen.buildTabList(
          history: staleHistory, provider: provider, tab: IncidentFilterTab.resolved, currentUserId: _meId);
      expect(ids(resolvedTab), ['m2']);
    });

    test('Bản lịch sử NEW đã được người khác nhận: rời tab "Cần xử lý"', () async {
      final history = [inc('m5')];
      provider.receiveUpdateForTest({'incidentId': 'm5', 'status': 'CLAIMED', 'claimedById': 'guard-b'});
      await Future<void>.delayed(const Duration(milliseconds: 20));

      expect(
          IncidentListScreen.buildTabList(
              history: history, provider: provider, tab: IncidentFilterTab.pending, currentUserId: _meId),
          isEmpty);
    });

    test('Không lùi trạng thái: tin CLAIMED tới sau tin RESOLVED không làm sự cố quay lại "Đang xử lý"', () async {
      provider.receiveUpdateForTest({'incidentId': 'x', 'status': 'RESOLVED_VERIFIED'});
      provider.receiveUpdateForTest({'incidentId': 'x', 'status': 'CLAIMED', 'claimedById': 'guard-b'});
      await Future<void>.delayed(const Duration(milliseconds: 20));
      expect(provider.withLatestStatus(inc('x')).status, 'RESOLVED_VERIFIED');
    });
  });
}
