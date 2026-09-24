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

class GuardShiftRequestModel {
  final String id;
  final String shiftId;
  final String requesterGuardId;
  final String? requesterGuardName;
  final String? targetSubstituteGuardId;
  final String? targetSubstituteGuardName;
  final String requestType; // SWAP or LEAVE
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
    required this.requestType,
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
      requestType: json['requestType']?.toString() ?? 'SWAP_SHIFT',
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

  String get typeLabel => isSwap ? '[Nhờ trực thay]' : '[Nghỉ đột xuất]';

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
