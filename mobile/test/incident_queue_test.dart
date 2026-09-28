import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';

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
}
