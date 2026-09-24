class AvailableSubstituteModel {
  final String id;
  final String? userCode;
  final String fullName;
  final String? email;
  final String? teamName;

  AvailableSubstituteModel({
    required this.id,
    this.userCode,
    required this.fullName,
    this.email,
    this.teamName,
  });

  factory AvailableSubstituteModel.fromJson(Map<String, dynamic> json) {
    return AvailableSubstituteModel(
      id: json['id']?.toString() ?? '',
      userCode: json['userCode']?.toString(),
      fullName: json['fullName']?.toString() ?? '',
      email: json['email']?.toString(),
      teamName: json['teamName']?.toString(),
    );
  }
}

class AvailableSwapShiftModel {
  final String targetShiftId;
  final String guardId;
  final String? userCode;
  final String fullName;
  final String? email;
  final String? teamName;
  final String shiftDate;
  final String shiftType;
  final String shiftTypeName;
  final String startTime;
  final String endTime;
  final String? areaName;
  final String? building;

  AvailableSwapShiftModel({
    required this.targetShiftId,
    required this.guardId,
    this.userCode,
    required this.fullName,
    this.email,
    this.teamName,
    required this.shiftDate,
    required this.shiftType,
    required this.shiftTypeName,
    required this.startTime,
    required this.endTime,
    this.areaName,
    this.building,
  });

  factory AvailableSwapShiftModel.fromJson(Map<String, dynamic> json) {
    return AvailableSwapShiftModel(
      targetShiftId: json['targetShiftId']?.toString() ?? '',
      guardId: json['guardId']?.toString() ?? '',
      userCode: json['userCode']?.toString(),
      fullName: json['fullName']?.toString() ?? '',
      email: json['email']?.toString(),
      teamName: json['teamName']?.toString(),
      shiftDate: json['shiftDate']?.toString() ?? '',
      shiftType: json['shiftType']?.toString() ?? '',
      shiftTypeName: json['shiftTypeName']?.toString() ?? '',
      startTime: json['startTime']?.toString() ?? '',
      endTime: json['endTime']?.toString() ?? '',
      areaName: json['areaName']?.toString(),
      building: json['building']?.toString(),
    );
  }

  String get formattedDate {
    final parts = shiftDate.split('-');
    if (parts.length == 3) {
      return '${parts[2]}-${parts[1]}-${parts[0]}';
    }
    return shiftDate;
  }

  String get timeRange => '${startTime.substring(0, 5)} — ${endTime.substring(0, 5)}';
}

class GuardShiftRequestModel {
  final String id;
  final String shiftId;
  final String requesterGuardId;
  final String? requesterGuardName;
  final String? targetSubstituteGuardId;
  final String? targetSubstituteGuardName;
  final String? targetSubstituteGuardCode;
  final String? targetShiftId;
  final String? targetShiftDate;
  final String? targetShiftType;
  final String? targetStartTime;
  final String? targetEndTime;
  final String? targetAreaName;
  final String requestType; // SWAP or LEAVE
  final bool isEmergency;
  final String reason;
  final String status; // PENDING, APPROVED, REJECTED
  final String? reviewNote;
  final String? createdAt;
  final String? shiftDate;
  final String? shiftStartTime;
  final String? shiftEndTime;

  GuardShiftRequestModel({
    required this.id,
    required this.shiftId,
    required this.requesterGuardId,
    this.requesterGuardName,
    this.targetSubstituteGuardId,
    this.targetSubstituteGuardName,
    this.targetSubstituteGuardCode,
    this.targetShiftId,
    this.targetShiftDate,
    this.targetShiftType,
    this.targetStartTime,
    this.targetEndTime,
    this.targetAreaName,
    required this.requestType,
    this.isEmergency = false,
    required this.reason,
    required this.status,
    this.reviewNote,
    this.createdAt,
    this.shiftDate,
    this.shiftStartTime,
    this.shiftEndTime,
  });

  factory GuardShiftRequestModel.fromJson(Map<String, dynamic> json) {
    final shift = json['shift'] as Map<String, dynamic>?;
    final requester = json['requesterGuard'] as Map<String, dynamic>?;
    final target = json['targetSubstituteGuard'] as Map<String, dynamic>?;

    return GuardShiftRequestModel(
      id: json['id']?.toString() ?? '',
      shiftId: json['shiftId']?.toString() ?? shift?['id']?.toString() ?? '',
      requesterGuardId: json['requesterId']?.toString() ?? json['requesterGuardId']?.toString() ?? requester?['id']?.toString() ?? '',
      requesterGuardName: json['requesterName']?.toString() ?? requester?['fullName']?.toString(),
      targetSubstituteGuardId: json['substituteGuardId']?.toString() ?? json['targetSubstituteGuardId']?.toString() ?? target?['id']?.toString(),
      targetSubstituteGuardName: json['substituteGuardName']?.toString() ?? target?['fullName']?.toString(),
      targetSubstituteGuardCode: json['substituteGuardCode']?.toString(),
      targetShiftId: json['targetShiftId']?.toString(),
      targetShiftDate: json['targetShiftDate']?.toString(),
      targetShiftType: json['targetShiftType']?.toString(),
      targetStartTime: json['targetStartTime']?.toString(),
      targetEndTime: json['targetEndTime']?.toString(),
      targetAreaName: json['targetAreaName']?.toString(),
      requestType: json['requestType']?.toString() ?? 'SWAP_SHIFT',
      isEmergency: json['isEmergency'] == true,
      reason: json['reason']?.toString() ?? '',
      status: json['status']?.toString() ?? 'PENDING',
      reviewNote: json['reviewNotes']?.toString() ?? json['reviewNote']?.toString(),
      createdAt: json['createdAt']?.toString(),
      shiftDate: json['shiftDate']?.toString() ?? shift?['shiftDate']?.toString(),
      shiftStartTime: json['startTime']?.toString() ?? shift?['startTime']?.toString(),
      shiftEndTime: json['endTime']?.toString() ?? shift?['endTime']?.toString(),
    );
  }

  bool get isSwap => requestType == 'SWAP' || requestType == 'SWAP_SHIFT';
  bool get isLeave => requestType == 'LEAVE' || requestType == 'LEAVE_REQUEST';
  bool get isPending => status == 'PENDING';
  bool get isApproved => status == 'APPROVED';
  bool get isRejected => status == 'REJECTED';

  String get typeLabel {
    if (isSwap) return '[Đổi ca]';
    return isEmergency ? '[Nghỉ đột xuất]' : '[Nghỉ phép]';
  }

  String get formattedShiftDate {
    if (shiftDate == null) return '';
    final parts = shiftDate!.split('-');
    if (parts.length == 3) {
      return '${parts[2]}-${parts[1]}-${parts[0]}';
    }
    return shiftDate!;
  }

  String get formattedTargetShiftDate {
    if (targetShiftDate == null) return '';
    final parts = targetShiftDate!.split('-');
    if (parts.length == 3) {
      return '${parts[2]}-${parts[1]}-${parts[0]}';
    }
    return targetShiftDate!;
  }

  String get statusLabel {
    switch (status) {
      case 'APPROVED':
        return 'Đã duyệt';
      case 'REJECTED':
        return 'Từ chối';
      case 'PENDING':
      default:
        return 'Chờ duyệt';
    }
  }
}
