import 'package:flutter/material.dart';
import '../../../core/constants/app_colors.dart';

class GuardShiftModel {
  final String id;
  final String? guardId;
  final String? guardName;
  final String? guardCode;
  final String shiftDate; // "YYYY-MM-DD"
  final String shiftType; // "SHIFT_MORNING", "SHIFT_AFTERNOON", "SHIFT_NIGHT", "SHIFT_OFF"
  final String startTime; // "HH:mm:ss" or "HH:mm"
  final String endTime;   // "HH:mm:ss" or "HH:mm"
  final String? areaId;
  final String? areaName;
  final String? building;
  final String? radioChannel;
  final String status; // "SCHEDULED", "CHECKED_IN", "COMPLETED", "ABSENT", "CANCELLED"
  final String? checkInAt;
  final String? checkOutAt;
  final String? notes;
  final bool isOvertime;

  GuardShiftModel({
    required this.id,
    this.guardId,
    this.guardName,
    this.guardCode,
    required this.shiftDate,
    required this.shiftType,
    required this.startTime,
    required this.endTime,
    this.areaId,
    this.areaName,
    this.building,
    this.radioChannel,
    required this.status,
    this.checkInAt,
    this.checkOutAt,
    this.notes,
    this.isOvertime = false,
  });

  factory GuardShiftModel.fromJson(Map<String, dynamic> json) {
    return GuardShiftModel(
      id: json['id']?.toString() ?? '',
      guardId: json['guardId']?.toString(),
      guardName: json['guardName']?.toString(),
      guardCode: json['guardCode']?.toString(),
      shiftDate: json['shiftDate']?.toString() ?? '',
      shiftType: json['shiftType']?.toString() ?? 'SHIFT_MORNING',
      startTime: json['startTime']?.toString() ?? '06:00',
      endTime: json['endTime']?.toString() ?? '14:00',
      areaId: json['areaId']?.toString(),
      areaName: json['areaName']?.toString(),
      building: json['building']?.toString(),
      radioChannel: json['radioChannel']?.toString(),
      status: json['status']?.toString() ?? 'SCHEDULED',
      checkInAt: json['checkInAt']?.toString(),
      checkOutAt: json['checkOutAt']?.toString(),
      notes: json['notes']?.toString(),
      isOvertime: json['isOvertime'] == true,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'guardId': guardId,
      'guardName': guardName,
      'guardCode': guardCode,
      'shiftDate': shiftDate,
      'shiftType': shiftType,
      'startTime': startTime,
      'endTime': endTime,
      'areaId': areaId,
      'areaName': areaName,
      'building': building,
      'radioChannel': radioChannel,
      'status': status,
      'checkInAt': checkInAt,
      'checkOutAt': checkOutAt,
      'notes': notes,
    };
  }

  // Format short time e.g. "06:00" from "06:00:00"
  String get shortStartTime {
    if (startTime.length >= 5) return startTime.substring(0, 5);
    return startTime;
  }

  String get shortEndTime {
    if (endTime.length >= 5) return endTime.substring(0, 5);
    return endTime;
  }

  String get shiftTimeRange => '$shortStartTime - $shortEndTime';

  String get shiftTypeName {
    switch (shiftType) {
      case 'SHIFT_MORNING':
        return 'Ca Sáng';
      case 'SHIFT_AFTERNOON':
        return 'Ca Chiều';
      case 'SHIFT_NIGHT':
        return 'Ca Đêm';
      default:
        return shiftType;
    }
  }

  String get shiftTypeLabel {
    switch (shiftType) {
      case 'SHIFT_MORNING':
        return 'Ca Sáng ($shortStartTime - $shortEndTime)';
      case 'SHIFT_AFTERNOON':
        return 'Ca Chiều ($shortStartTime - $shortEndTime)';
      case 'SHIFT_NIGHT':
        return 'Ca Đêm ($shortStartTime - $shortEndTime)';
      default:
        return shiftType;
    }
  }

  String get statusLabel {
    switch (status) {
      case 'SCHEDULED':
        return 'CHƯA NHẬN CA';
      case 'CHECKED_IN':
        return 'ĐANG TRỰC';
      case 'COMPLETED':
        return 'HOÀN THÀNH';
      case 'ABSENT':
        return 'VẮNG MẶT';
      case 'CANCELLED':
        return 'ĐÃ HỦY';
      default:
        return status;
    }
  }

  Color get statusColor {
    switch (status) {
      case 'CHECKED_IN':
        return AppColors.success;
      case 'SCHEDULED':
        return AppColors.warning;
      case 'COMPLETED':
        return AppColors.textMuted;
      case 'ABSENT':
      case 'CANCELLED':
        return AppColors.danger;
      default:
        return AppColors.textSecondary;
    }
  }

  Color get typeColor {
    switch (shiftType) {
      case 'SHIFT_MORNING':
        return AppColors.morningShift;
      case 'SHIFT_AFTERNOON':
        return AppColors.afternoonShift;
      case 'SHIFT_NIGHT':
        return AppColors.nightShift;
      default:
        return AppColors.offShift;
    }
  }

  IconData get typeIcon {
    switch (shiftType) {
      case 'SHIFT_MORNING':
        return Icons.wb_sunny_outlined;
      case 'SHIFT_AFTERNOON':
        return Icons.wb_twilight_outlined;
      case 'SHIFT_NIGHT':
        return Icons.nightlight_round_outlined;
      default:
        return Icons.schedule;
    }
  }

  bool get isScheduled => status == 'SCHEDULED';
  bool get isCheckedIn => status == 'CHECKED_IN';
  bool get isCompleted => status == 'COMPLETED';
  bool get isAbsent => status == 'ABSENT';

  DateTime? get shiftStartDateTime {
    try {
      final dateParts = shiftDate.split('-').map(int.parse).toList();
      final timeParts = startTime.split(':').map(int.parse).toList();
      return DateTime(dateParts[0], dateParts[1], dateParts[2], timeParts[0], timeParts[1]);
    } catch (_) {
      return null;
    }
  }

  DateTime? get shiftEndDateTime {
    try {
      final dateParts = shiftDate.split('-').map(int.parse).toList();
      final startParts = startTime.split(':').map(int.parse).toList();
      final endParts = endTime.split(':').map(int.parse).toList();

      var dt = DateTime(dateParts[0], dateParts[1], dateParts[2], endParts[0], endParts[1]);
      if (endParts[0] < startParts[0] || (endParts[0] == startParts[0] && endParts[1] < startParts[1])) {
        dt = dt.add(const Duration(days: 1));
      }
      return dt;
    } catch (_) {
      return null;
    }
  }

  // Check-in allowed window: [shiftStart - 5min, shiftStart + 5min]
  bool get canCheckInNow {
    final start = shiftStartDateTime;
    if (start == null) return false;
    final now = DateTime.now();
    final earliest = start.subtract(const Duration(minutes: 5));
    final latest = start.add(const Duration(minutes: 5));
    return !now.isBefore(earliest) && !now.isAfter(latest);
  }

  bool get isBeforeCheckInWindow {
    final start = shiftStartDateTime;
    if (start == null) return false;
    final now = DateTime.now();
    final earliest = start.subtract(const Duration(minutes: 5));
    return now.isBefore(earliest);
  }

  bool get isAfterCheckInWindow {
    final start = shiftStartDateTime;
    if (start == null) return false;
    final now = DateTime.now();
    final latest = start.add(const Duration(minutes: 5));
    return now.isAfter(latest);
  }

  // Check-out allowed window: [shiftEnd, shiftEnd + 5min]
  bool get canCheckOutNow {
    final end = shiftEndDateTime;
    if (end == null) return false;
    final now = DateTime.now();
    final latest = end.add(const Duration(minutes: 5));
    return !now.isBefore(end) && !now.isAfter(latest);
  }

  bool get isBeforeCheckOutWindow {
    final end = shiftEndDateTime;
    if (end == null) return false;
    final now = DateTime.now();
    return now.isBefore(end);
  }

  bool get isAfterCheckOutWindow {
    final end = shiftEndDateTime;
    if (end == null) return false;
    final now = DateTime.now();
    final latest = end.add(const Duration(minutes: 5));
    return now.isAfter(latest);
  }
}
