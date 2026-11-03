import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';

// Quyết định 03/11: 5 nhãn sự cố; sai khung giờ tính như vào trái phép; vẫn hiển thị đúng tên cũ chưa migrate.
IncidentModel _incident(String eventType) =>
    IncidentModel(id: eventType, cameraCode: 'CAM-001', eventType: eventType, status: 'NEW');

void main() {
  group('5 nhãn sự cố (03/11)', () {
    test('nhãn mới có trọng số và chữ hiển thị đúng', () {
      expect(_incident('UNAUTHORIZED_PERSON').severityWeight, equals(3));
      expect(_incident('UNAUTHORIZED_PERSON').eventTypeDisplay, equals('Không đủ thẩm quyền'));

      expect(_incident('PERSON_INTRUSION').severityWeight, equals(2));
      expect(_incident('PERSON_INTRUSION').eventTypeDisplay, equals('Sau giờ hoạt động'));

      expect(_incident('SUSPICIOUS_MOVING_OBJECT').severityWeight, equals(2));
      expect(_incident('SUSPICIOUS_MOVING_OBJECT').eventTypeDisplay, equals('Chuyển động nghi vấn'));

      expect(_incident('UNKNOWN_PERSON').severityWeight, equals(1));
      expect(_incident('UNKNOWN_PERSON').eventTypeDisplay, equals('Đối tượng không xác định'));
    });

    test('sai khung giờ (tên cũ) tính và hiển thị như không đủ thẩm quyền', () {
      expect(_incident('ACCESS_OUT_OF_TIME').severityWeight, equals(3));
      expect(_incident('ACCESS_OUT_OF_TIME').eventTypeDisplay, equals('Không đủ thẩm quyền'));
    });

    test('tên cũ vẫn ra cùng nhãn với tên mới', () {
      expect(_incident('INTRUSION').eventTypeDisplay, equals(_incident('PERSON_INTRUSION').eventTypeDisplay));
      expect(_incident('UNAUTHORIZED_ACCESS').eventTypeDisplay, equals(_incident('UNAUTHORIZED_PERSON').eventTypeDisplay));
      expect(_incident('SUSPICIOUS_MOTION').eventTypeDisplay, equals(_incident('SUSPICIOUS_MOVING_OBJECT').eventTypeDisplay));
    });
  });
}
