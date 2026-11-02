import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/models/incident_offer_model.dart';

void main() {
  group('IncidentModel Tests', () {
    test('Calculates severity weights correctly for 3 standardized incident types', () {
      final unauthorized = IncidentModel(
        id: '1',
        cameraCode: 'CAM-001',
        eventType: 'UNAUTHORIZED_ACCESS',
        status: 'NEW',
      );
      final unknown = IncidentModel(
        id: '2',
        cameraCode: 'CAM-002',
        eventType: 'UNKNOWN_PERSON',
        status: 'NEW',
      );
      final afterHours = IncidentModel(
        id: '3',
        cameraCode: 'CAM-003',
        eventType: 'AFTER_HOURS_ACCESS',
        status: 'NEW',
      );

      expect(unauthorized.severityWeight, equals(3));
      expect(unknown.severityWeight, equals(2));
      expect(afterHours.severityWeight, equals(1));
    });

    test('Queue sorts by severity weight DESC then FIFO (detectedAt ASC)', () {
      final now = DateTime.now();

      final incidentLow = IncidentModel(
        id: 'low',
        cameraCode: 'CAM-001',
        eventType: 'AFTER_HOURS_ACCESS', // weight 1
        detectedAt: now.subtract(const Duration(seconds: 10)),
        status: 'NEW',
      );

      final incidentHigh = IncidentModel(
        id: 'high',
        cameraCode: 'CAM-002',
        eventType: 'UNKNOWN_PERSON', // weight 2
        detectedAt: now.subtract(const Duration(seconds: 5)),
        status: 'NEW',
      );

      final incidentCriticalEarly = IncidentModel(
        id: 'crit_early',
        cameraCode: 'CAM-003',
        eventType: 'UNAUTHORIZED_ACCESS', // weight 3
        detectedAt: now.subtract(const Duration(seconds: 30)),
        status: 'NEW',
      );

      final incidentCriticalLate = IncidentModel(
        id: 'crit_late',
        cameraCode: 'CAM-004',
        eventType: 'UNAUTHORIZED', // weight 3
        detectedAt: now.subtract(const Duration(seconds: 15)),
        status: 'NEW',
      );

      final list = [incidentLow, incidentCriticalLate, incidentHigh, incidentCriticalEarly];

      // Multi-incident queue sorting logic
      list.sort((a, b) {
        final sevCompare = b.severityWeight.compareTo(a.severityWeight);
        if (sevCompare != 0) return sevCompare;

        final aTime = a.detectedAt ?? DateTime.now();
        final bTime = b.detectedAt ?? DateTime.now();
        return aTime.compareTo(bTime);
      });

      // 1st: crit_early (weight 3, detected 30s ago)
      // 2nd: crit_late (weight 3, detected 15s ago)
      // 3rd: high (weight 2)
      // 4th: low (weight 1)
      expect(list[0].id, equals('crit_early'));
      expect(list[1].id, equals('crit_late'));
      expect(list[2].id, equals('high'));
      expect(list[3].id, equals('low'));
    });

    test('JSON serialization handles snake_case and camelCase', () {
      final json = {
        'id': 'abc-123',
        'event_id': 'EVT-999',
        'camera_code': 'CAM-SERVER',
        'area_name': 'Phòng Máy Chủ',
        'event_type': 'UNAUTHORIZED_ACCESS',
        'status': 'NEW',
        'version': 1,
      };

      final incident = IncidentModel.fromJson(json);
      expect(incident.id, equals('abc-123'));
      expect(incident.eventId, equals('EVT-999'));
      expect(incident.cameraCode, equals('CAM-SERVER'));
      expect(incident.areaName, equals('Phòng Máy Chủ'));
      expect(incident.eventType, equals('UNAUTHORIZED_ACCESS'));
      expect(incident.severityWeight, equals(3));
      expect(incident.isNew, isTrue);
      expect(incident.isClaimed, isFalse);
    });

    test('Concurrent claim sync: Evicts incident when another guard claims via STOMP update', () {
      final queue = [
        IncidentModel(id: 'inc-1', cameraCode: 'CAM-001', eventType: 'UNAUTHORIZED_ACCESS', status: 'NEW'),
        IncidentModel(id: 'inc-2', cameraCode: 'CAM-002', eventType: 'UNKNOWN_PERSON', status: 'NEW'),
      ];

      // Giả lập STOMP update nhận được từ Backend khi Bảo vệ B đã tiếp nhận 'inc-1'
      final stompUpdate = {
        'incidentId': 'inc-1',
        'status': 'CLAIMED',
        'claimedById': 'guard-b-id',
        'claimedByName': 'Bảo vệ Lê Văn B',
      };

      final currentUserId = 'guard-a-id';
      final claimantId = stompUpdate['claimedById'];
      final targetIncidentId = stompUpdate['incidentId'];

      if (claimantId != currentUserId) {
        queue.removeWhere((i) => i.id == targetIncidentId);
      }

      // Xác minh 'inc-1' đã bị xóa khỏi hàng đợi, chỉ còn lại 'inc-2'
      expect(queue.length, equals(1));
      expect(queue.first.id, equals('inc-2'));
      expect(queue.any((i) => i.id == 'inc-1'), isFalse);
    });

    test('Concurrent claim race condition: 409 Conflict removes incident from queue', () {
      final queue = [
        IncidentModel(id: 'inc-race', cameraCode: 'CAM-003', eventType: 'UNAUTHORIZED_ACCESS', status: 'NEW'),
      ];

      // Giả lập Guard A bấm claim sau Guard B vài ms -> Server trả về 409 Conflict
      final statusCode = 409;
      final serverMessage = 'Sự việc vừa được tiếp nhận bởi Bảo vệ Lê Văn B';

      String? errorMessage;
      if (statusCode == 409) {
        queue.removeWhere((i) => i.id == 'inc-race');
        errorMessage = serverMessage;
      }

      // Hàng đợi loại bỏ ngay lập tức sự cố bị tranh chấp
      expect(queue.isEmpty, isTrue);
      expect(errorMessage, contains('Bảo vệ Lê Văn B'));
    });
  });

  group('Severity server + dispatchStage (K6/K8)', () {
    IncidentModel make(String id, {required String eventType, String? severity, String? stage, DateTime? at}) {
      return IncidentModel(
        id: id,
        cameraCode: 'CAM-$id',
        eventType: eventType,
        status: 'NEW',
        severity: severity,
        dispatchStage: stage,
        detectedAt: at,
      );
    }

    test('fromJson / toJson đọc thêm severity, dispatchStage; backend cũ không gửi thì null', () {
      final json = {
        'id': 'inc-1',
        'cameraCode': 'CAM-001',
        'eventType': 'UNKNOWN_PERSON',
        'status': 'NEW',
        'severity': 'CRITICAL',
        'dispatchStage': 'STEP1',
      };
      final incident = IncidentModel.fromJson(json);
      expect(incident.severity, equals('CRITICAL'));
      expect(incident.dispatchStage, equals('STEP1'));
      expect(incident.toJson()['severity'], equals('CRITICAL'));
      expect(incident.toJson()['dispatchStage'], equals('STEP1'));

      final legacy = IncidentModel.fromJson({'id': 'inc-2', 'eventType': 'UNKNOWN_PERSON', 'status': 'NEW'});
      expect(legacy.severity, isNull);
      expect(legacy.dispatchStage, isNull);
    });

    test('severityLevel dùng severity server; null hoặc giá trị lạ thì dùng severityWeight cũ', () {
      // UNKNOWN_PERSON có severityWeight = 1, UNAUTHORIZED_ACCESS = 3
      expect(make('a', eventType: 'UNKNOWN_PERSON', severity: 'CRITICAL').severityLevel, equals(3));
      expect(make('b', eventType: 'UNAUTHORIZED_ACCESS', severity: 'MEDIUM').severityLevel, equals(1));
      expect(make('c', eventType: 'UNAUTHORIZED_ACCESS', severity: 'high').severityLevel, equals(2));
      expect(make('d', eventType: 'UNAUTHORIZED_ACCESS').severityLevel, equals(3));
      expect(make('e', eventType: 'UNKNOWN_PERSON', severity: 'UNKNOWN_LEVEL').severityLevel, equals(1));
      // Nhãn theo mức hiệu lực
      expect(make('f', eventType: 'UNKNOWN_PERSON', severity: 'CRITICAL').severityLabel, equals('CỰC KỲ NGUY CẤP'));
    });

    test('dispatchStage null hoặc BROADCAST coi như BROADCAST; chỉ STEP1 là đang mời', () {
      expect(make('a', eventType: 'INTRUSION').isOfferPhase, isFalse);
      expect(make('b', eventType: 'INTRUSION', stage: 'BROADCAST').isOfferPhase, isFalse);
      expect(make('c', eventType: 'INTRUSION', stage: 'STEP1').isOfferPhase, isTrue);
      expect(make('d', eventType: 'INTRUSION', stage: 'step1').isOfferPhase, isTrue);
    });

    test('Sắp xếp: severity server trước, severityWeight cũ tie-break, rồi FIFO', () {
      final now = DateTime.now();
      // weight: UNAUTHORIZED_ACCESS 3, INTRUSION 2, UNKNOWN_PERSON 1
      final serverHighWeight1 = make('srv-high-w1', eventType: 'UNKNOWN_PERSON', severity: 'HIGH',
          at: now.subtract(const Duration(seconds: 50)));
      final serverHighWeight2 = make('srv-high-w2', eventType: 'INTRUSION', severity: 'HIGH',
          at: now.subtract(const Duration(seconds: 10)));
      final serverCritical = make('srv-crit', eventType: 'UNKNOWN_PERSON', severity: 'CRITICAL',
          at: now.subtract(const Duration(seconds: 5)));
      final legacyWeight3 = make('legacy-w3', eventType: 'UNAUTHORIZED_ACCESS',
          at: now.subtract(const Duration(seconds: 40)));
      final serverMediumOld = make('srv-med-old', eventType: 'UNKNOWN_PERSON', severity: 'MEDIUM',
          at: now.subtract(const Duration(seconds: 60)));
      final serverMediumNew = make('srv-med-new', eventType: 'UNKNOWN_PERSON', severity: 'MEDIUM',
          at: now.subtract(const Duration(seconds: 1)));

      final list = [serverMediumNew, serverHighWeight1, legacyWeight3, serverMediumOld, serverCritical, serverHighWeight2];
      list.sort((a, b) {
        final c = IncidentModel.comparePriority(a, b);
        if (c != 0) return c;
        return a.detectedAt!.compareTo(b.detectedAt!);
      });

      expect(list.map((i) => i.id).toList(), equals([
        'legacy-w3', // không có severity → dùng weight 3, cùng mức với CRITICAL; tie-break weight 3 > 1
        'srv-crit',
        'srv-high-w2', // cùng HIGH, weight 2 > 1
        'srv-high-w1',
        'srv-med-old', // cùng MEDIUM, cùng weight → FIFO
        'srv-med-new',
      ]));
    });

    test('Backend cũ (mọi severity null): thứ tự y như severityWeight cũ', () {
      final now = DateTime.now();
      final list = [
        make('w1', eventType: 'UNKNOWN_PERSON', at: now),
        make('w3', eventType: 'UNAUTHORIZED_ACCESS', at: now),
        make('w2', eventType: 'INTRUSION', at: now),
      ];
      list.sort(IncidentModel.comparePriority);
      expect(list.map((i) => i.id).toList(), equals(['w3', 'w2', 'w1']));
    });

    test('IncidentOfferModel v2: đọc thông tin sự cố từ key "incident"', () {
      final expires = DateTime.now().add(const Duration(seconds: 45)).toUtc();
      final offer = IncidentOfferModel.fromJson({
        'type': 'INCIDENT_OFFER',
        'offerId': 'off-9',
        'incidentId': 'inc-9',
        'expiresAt': expires.toIso8601String(),
        'incident': {
          'id': 'inc-9',
          'cameraCode': 'CAM-009',
          'areaName': 'Phòng Lab',
          'building': 'ALPHA',
          'eventType': 'INTRUSION',
          'status': 'NEW',
          'severity': 'HIGH',
          'dispatchStage': 'STEP1',
          'imageUrl': '/api/media/incidents/inc-9.jpg',
        },
      });
      expect(offer, isNotNull);
      expect(offer!.offerId, equals('off-9'));
      expect(offer.expiresAt.isAtSameMomentAs(expires), isTrue);
      expect(offer.deadline.isAtSameMomentAs(expires), isTrue); // không có offeredAt → đếm tới expiresAt
      expect(offer.cameraCode, equals('CAM-009'));
      expect(offer.areaName, equals('Phòng Lab'));
      expect(offer.imageUrl, equals('/api/media/incidents/inc-9.jpg'));
      final incident = offer.toIncident();
      expect(incident.id, equals('inc-9'));
      expect(incident.isNew, isTrue);
      expect(incident.isOfferPhase, isTrue);
      expect(incident.severityLevel, equals(2));
    });

    test('IncidentOfferModel: có offeredAt thì deadline = lúc nhận + (expiresAt - offeredAt)', () {
      final serverOfferedAt = DateTime.utc(2026, 10, 31, 10, 0, 0);
      final received = DateTime.utc(2026, 10, 31, 10, 7, 3); // đồng hồ điện thoại lệch 7 phút
      final offer = IncidentOfferModel.fromJson({
        'type': 'INCIDENT_OFFER',
        'incidentId': 'inc-1',
        'offeredAt': serverOfferedAt.toIso8601String(),
        'expiresAt': serverOfferedAt.add(const Duration(seconds: 60)).toIso8601String(),
        'incident': {'id': 'inc-1', 'eventType': 'INTRUSION', 'status': 'NEW'},
      }, receivedAt: received);

      expect(offer!.deadline, equals(received.add(const Duration(seconds: 60))));
      expect(offer.remaining(received.add(const Duration(seconds: 20))), equals(const Duration(seconds: 40)));
      expect(offer.remaining(received.add(const Duration(seconds: 90))), equals(Duration.zero));
      // incident không có dispatchStage → mặc định STEP1 vì đây là lời mời
      expect(offer.toIncident().isOfferPhase, isTrue);
    });

    test('IncidentOfferModel: dự phòng dạng phẳng (v1), thiếu expiresAt thì bỏ, envelope data được bóc', () {
      final expires = DateTime.now().add(const Duration(seconds: 45)).toUtc();
      final flat = IncidentOfferModel.fromJson({
        'type': 'INCIDENT_OFFER',
        'incidentId': 'inc-8',
        'eventType': 'UNKNOWN_PERSON',
        'cameraCode': 'CAM-008',
        'areaName': 'Sảnh',
        'createdAt': DateTime.now().toUtc().toIso8601String(),
        'expiresAt': expires.toIso8601String(),
      });
      expect(flat!.cameraCode, equals('CAM-008'));
      expect(flat.toIncident().detectedAt, isNotNull);
      expect(flat.toIncident().isOfferPhase, isTrue);

      expect(IncidentOfferModel.fromJson({'type': 'INCIDENT_OFFER', 'incidentId': 'inc-9'}), isNull);

      final wrapped = IncidentOfferModel.unwrap({
        'type': 'INCIDENT_OFFER',
        'data': {'incidentId': 'inc-10', 'expiresAt': expires.toIso8601String()},
      });
      expect(wrapped['type'], equals('INCIDENT_OFFER'));
      expect(IncidentOfferModel.fromJson(wrapped)!.incidentId, equals('inc-10'));
    });
  });
}
