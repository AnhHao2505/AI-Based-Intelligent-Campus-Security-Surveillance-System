import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/features/auth/models/user_model.dart';
import 'package:guard_mobile_app/features/shift/models/guard_shift_model.dart';

void main() {
  group('UserModel Serialization Tests', () {
    test('Should parse UserModel from JSON successfully', () {
      final json = {
        'id': 'b1e8e8d8-2b8d-4e9c-8b8d-8b8d8b8d8b8d',
        'fullName': 'Nguyễn Văn An',
        'email': 'guard.an@fpt.edu.vn',
        'role': 'GUARD',
        'userCode': 'G001',
      };

      final user = UserModel.fromJson(json);

      expect(user.id, 'b1e8e8d8-2b8d-4e9c-8b8d-8b8d8b8d8b8d');
      expect(user.fullName, 'Nguyễn Văn An');
      expect(user.email, 'guard.an@fpt.edu.vn');
      expect(user.role, 'GUARD');
      expect(user.userCode, 'G001');

      final map = user.toJson();
      expect(map['fullName'], 'Nguyễn Văn An');
    });
  });

  group('GuardShiftModel Serialization & Helper Tests', () {
    test('Should parse morning shift and compute correct labels and status', () {
      final json = {
        'id': 'shift-101',
        'guardId': 'guard-001',
        'guardName': 'Nguyễn Văn An',
        'shiftDate': '2026-09-16',
        'shiftType': 'SHIFT_MORNING',
        'startTime': '06:00:00',
        'endTime': '14:00:00',
        'areaName': 'Phòng Giám Sát Camera',
        'building': 'Tòa Alpha',
        'radioChannel': 'Kênh 2 (Bộ đàm)',
        'status': 'SCHEDULED',
        'notes': 'Trực camera an ninh 24/7',
      };

      final shift = GuardShiftModel.fromJson(json);

      expect(shift.id, 'shift-101');
      expect(shift.shiftDate, '2026-09-16');
      expect(shift.shortStartTime, '06:00');
      expect(shift.shortEndTime, '14:00');
      expect(shift.shiftTimeRange, '06:00 - 14:00');
      expect(shift.shiftTypeLabel, contains('Ca Sáng (06:00 - 14:00)'));
      expect(shift.areaName, 'Phòng Giám Sát Camera');
      expect(shift.radioChannel, 'Kênh 2 (Bộ đàm)');
      expect(shift.statusLabel, 'CHƯA NHẬN CA');
      expect(shift.isScheduled, isTrue);
      expect(shift.isCheckedIn, isFalse);
      expect(shift.isCompleted, isFalse);
    });

    test('Should handle CHECKED_IN and COMPLETED statuses properly', () {
      final inShift = GuardShiftModel.fromJson({
        'id': 'shift-102',
        'shiftDate': '2026-09-16',
        'shiftType': 'SHIFT_AFTERNOON',
        'startTime': '14:00',
        'endTime': '22:00',
        'status': 'CHECKED_IN',
      });

      expect(inShift.isCheckedIn, isTrue);
      expect(inShift.statusLabel, 'ĐANG TRỰC');

      final doneShift = GuardShiftModel.fromJson({
        'id': 'shift-103',
        'shiftDate': '2026-09-16',
        'shiftType': 'SHIFT_NIGHT',
        'startTime': '22:00',
        'endTime': '06:00',
        'status': 'COMPLETED',
      });

      expect(doneShift.isCompleted, isTrue);
      expect(doneShift.statusLabel, 'HOÀN THÀNH');
    });
  });
}
