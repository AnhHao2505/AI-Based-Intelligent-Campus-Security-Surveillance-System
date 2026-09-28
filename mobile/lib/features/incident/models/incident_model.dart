class IncidentModel {
  final String id;
  final String? eventId;
  final String cameraCode;
  final String? areaId;
  final String? areaName;
  final String? building;
  final String eventType;
  final String? imageUrl;
  final DateTime? detectedAt;
  final String status;
  final String? claimedById;
  final String? claimedByName;
  final DateTime? claimedAt;
  final String? resolvedById;
  final String? resolvedByName;
  final DateTime? resolvedAt;
  final String? outcome;
  final String? resolutionCategory;
  final String? resolutionNotes;
  final String? evidenceImageUrl;
  final int version;
  final String? radioChannel;

  IncidentModel({
    required this.id,
    this.eventId,
    required this.cameraCode,
    this.areaId,
    this.areaName,
    this.building,
    required this.eventType,
    this.imageUrl,
    this.detectedAt,
    required this.status,
    this.claimedById,
    this.claimedByName,
    this.claimedAt,
    this.resolvedById,
    this.resolvedByName,
    this.resolvedAt,
    this.outcome,
    this.resolutionCategory,
    this.resolutionNotes,
    this.evidenceImageUrl,
    this.version = 0,
    this.radioChannel,
  });

  /// Trọng số mức độ nghiêm trọng (Chuẩn hóa 3 loại sự cố: UNAUTHORIZED > UNKNOWN > AFTER_HOURS)
  int get severityWeight {
    switch (eventType.toUpperCase()) {
      case 'UNAUTHORIZED_ACCESS':
      case 'UNAUTHORIZED':
        return 3; // 🔴 CỰC KỲ NGUY CẤP (Critical)
      case 'UNKNOWN_PERSON':
      case 'UNKNOWN':
        return 2; // 🟠 MỨC ĐỘ CAO (High)
      case 'AFTER_HOURS_ACCESS':
      case 'AFTER_HOURS_PRESENCE':
      case 'AFTER_HOURS':
      default:
        return 1; // 🟣 CẢNH BÁO (Warning / Medium)
    }
  }

  String get severityLabel {
    switch (severityWeight) {
      case 3:
        return 'CỰC KỲ NGUY CẤP';
      case 2:
        return 'MỨC ĐỘ CAO';
      case 1:
      default:
        return 'CẢNH BÁO';
    }
  }

  String get eventTypeDisplay {
    switch (eventType.toUpperCase()) {
      case 'UNAUTHORIZED_ACCESS':
      case 'UNAUTHORIZED':
        return 'Xâm nhập trái phép';
      case 'UNKNOWN_PERSON':
      case 'UNKNOWN':
        return 'Người lạ chưa xác minh';
      case 'AFTER_HOURS_ACCESS':
      case 'AFTER_HOURS_PRESENCE':
      case 'AFTER_HOURS':
        return 'Có người ngoài giờ';
      default:
        return eventType;
    }
  }

  bool get isNew => status == 'NEW';
  bool get isClaimed => status == 'CLAIMED';
  bool get isResolved => status.startsWith('RESOLVED');

  factory IncidentModel.fromJson(Map<String, dynamic> json) {
    DateTime? parseDate(dynamic v) {
      if (v == null) return null;
      if (v is DateTime) return v;
      try {
        return DateTime.parse(v.toString());
      } catch (_) {
        return null;
      }
    }

    return IncidentModel(
      id: (json['id'] ?? json['incidentId'] ?? '').toString(),
      eventId: json['eventId']?.toString() ?? json['event_id']?.toString(),
      cameraCode: (json['cameraCode'] ?? json['camera_code'] ?? 'CAM-UNKNOWN').toString(),
      areaId: json['areaId']?.toString(),
      areaName: json['areaName']?.toString() ?? json['area_name']?.toString(),
      building: json['building']?.toString(),
      eventType: (json['eventType'] ?? json['event_type'] ?? 'SECURITY_ALERT').toString(),
      imageUrl: json['imageUrl']?.toString() ?? json['image_url']?.toString(),
      detectedAt: parseDate(json['detectedAt'] ?? json['detected_at']),
      status: (json['status'] ?? 'NEW').toString(),
      claimedById: json['claimedById']?.toString(),
      claimedByName: json['claimedByName']?.toString(),
      claimedAt: parseDate(json['claimedAt']),
      resolvedById: json['resolvedById']?.toString(),
      resolvedByName: json['resolvedByName']?.toString(),
      resolvedAt: parseDate(json['resolvedAt']),
      outcome: json['outcome']?.toString(),
      resolutionCategory: json['resolutionCategory']?.toString(),
      resolutionNotes: json['resolutionNotes']?.toString() ?? json['details']?.toString(),
      evidenceImageUrl: json['evidenceImageUrl']?.toString(),
      version: json['version'] is int ? json['version'] as int : int.tryParse(json['version']?.toString() ?? '0') ?? 0,
      radioChannel: json['radioChannel']?.toString(),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'eventId': eventId,
      'cameraCode': cameraCode,
      'areaId': areaId,
      'areaName': areaName,
      'building': building,
      'eventType': eventType,
      'imageUrl': imageUrl,
      'detectedAt': detectedAt?.toIso8601String(),
      'status': status,
      'claimedById': claimedById,
      'claimedByName': claimedByName,
      'claimedAt': claimedAt?.toIso8601String(),
      'resolvedById': resolvedById,
      'resolvedByName': resolvedByName,
      'resolvedAt': resolvedAt?.toIso8601String(),
      'outcome': outcome,
      'resolutionCategory': resolutionCategory,
      'resolutionNotes': resolutionNotes,
      'evidenceImageUrl': evidenceImageUrl,
      'version': version,
      'radioChannel': radioChannel,
    };
  }

  IncidentModel copyWith({
    String? id,
    String? eventId,
    String? cameraCode,
    String? areaId,
    String? areaName,
    String? building,
    String? eventType,
    String? imageUrl,
    DateTime? detectedAt,
    String? status,
    String? claimedById,
    String? claimedByName,
    DateTime? claimedAt,
    String? resolvedById,
    String? resolvedByName,
    DateTime? resolvedAt,
    String? outcome,
    String? resolutionCategory,
    String? resolutionNotes,
    String? evidenceImageUrl,
    int? version,
    String? radioChannel,
  }) {
    return IncidentModel(
      id: id ?? this.id,
      eventId: eventId ?? this.eventId,
      cameraCode: cameraCode ?? this.cameraCode,
      areaId: areaId ?? this.areaId,
      areaName: areaName ?? this.areaName,
      building: building ?? this.building,
      eventType: eventType ?? this.eventType,
      imageUrl: imageUrl ?? this.imageUrl,
      detectedAt: detectedAt ?? this.detectedAt,
      status: status ?? this.status,
      claimedById: claimedById ?? this.claimedById,
      claimedByName: claimedByName ?? this.claimedByName,
      claimedAt: claimedAt ?? this.claimedAt,
      resolvedById: resolvedById ?? this.resolvedById,
      resolvedByName: resolvedByName ?? this.resolvedByName,
      resolvedAt: resolvedAt ?? this.resolvedAt,
      outcome: outcome ?? this.outcome,
      resolutionCategory: resolutionCategory ?? this.resolutionCategory,
      resolutionNotes: resolutionNotes ?? this.resolutionNotes,
      evidenceImageUrl: evidenceImageUrl ?? this.evidenceImageUrl,
      version: version ?? this.version,
      radioChannel: radioChannel ?? this.radioChannel,
    );
  }
}
