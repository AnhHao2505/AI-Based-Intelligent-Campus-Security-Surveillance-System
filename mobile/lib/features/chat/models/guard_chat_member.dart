class GuardChatMember {
  final String id;
  final String fullName;
  final String? email;
  final String? userCode;
  final String? phoneNumber;
  final String? avatarUrl;
  final bool onDuty;
  final String? currentShiftName;
  final String? currentShiftTime;
  final String? assignedBuilding;
  final int unreadCount;
  final String? lastMessage;
  final DateTime? lastMessageAt;

  GuardChatMember({
    required this.id,
    required this.fullName,
    this.email,
    this.userCode,
    this.phoneNumber,
    this.avatarUrl,
    this.onDuty = false,
    this.currentShiftName,
    this.currentShiftTime,
    this.assignedBuilding,
    this.unreadCount = 0,
    this.lastMessage,
    this.lastMessageAt,
  });

  factory GuardChatMember.fromJson(Map<String, dynamic> json) {
    DateTime? lastMsgTime;
    if (json['lastMessageAt'] != null) {
      lastMsgTime = DateTime.tryParse(json['lastMessageAt'].toString());
    }
    return GuardChatMember(
      id: json['id']?.toString() ?? '',
      fullName: json['fullName']?.toString() ?? 'Bảo vệ',
      email: json['email']?.toString(),
      userCode: json['userCode']?.toString(),
      phoneNumber: json['phoneNumber']?.toString(),
      avatarUrl: json['avatarUrl']?.toString(),
      onDuty: json['onDuty'] == true,
      currentShiftName: json['currentShiftName']?.toString(),
      currentShiftTime: json['currentShiftTime']?.toString(),
      assignedBuilding: json['assignedBuilding']?.toString(),
      unreadCount: (json['unreadCount'] as num?)?.toInt() ?? 0,
      lastMessage: json['lastMessage']?.toString(),
      lastMessageAt: lastMsgTime,
    );
  }

  GuardChatMember copyWith({
    String? id,
    String? fullName,
    String? email,
    String? userCode,
    String? phoneNumber,
    String? avatarUrl,
    bool? onDuty,
    String? currentShiftName,
    String? currentShiftTime,
    String? assignedBuilding,
    int? unreadCount,
    String? lastMessage,
    DateTime? lastMessageAt,
  }) {
    return GuardChatMember(
      id: id ?? this.id,
      fullName: fullName ?? this.fullName,
      email: email ?? this.email,
      userCode: userCode ?? this.userCode,
      phoneNumber: phoneNumber ?? this.phoneNumber,
      avatarUrl: avatarUrl ?? this.avatarUrl,
      onDuty: onDuty ?? this.onDuty,
      currentShiftName: currentShiftName ?? this.currentShiftName,
      currentShiftTime: currentShiftTime ?? this.currentShiftTime,
      assignedBuilding: assignedBuilding ?? this.assignedBuilding,
      unreadCount: unreadCount ?? this.unreadCount,
      lastMessage: lastMessage ?? this.lastMessage,
      lastMessageAt: lastMessageAt ?? this.lastMessageAt,
    );
  }
}

