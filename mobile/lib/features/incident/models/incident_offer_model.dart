import 'incident_model.dart';

/// Lời mời xử lý sự cố gửi riêng cho một bảo vệ (bước 1 của phân phối theo đợt).
///
/// Hợp đồng WebSocket (đặc tả v2, K6): topic `/topic/guards/{id}/offers`, phân biệt bằng field `type`:
/// - `INCIDENT_OFFER`: { type, offerId, incidentId, expiresAt, [offeredAt], incident: IncidentDetailResponse }
/// - `INCIDENT_OFFER_CANCELLED`: { type, offerId, incidentId, reason }
/// Dự phòng: message bọc trong `data` / `payload`, hoặc thông tin sự cố nằm phẳng ở cấp ngoài.
class IncidentOfferModel {
  static const String typeOffer = 'INCIDENT_OFFER';
  static const String typeCancelled = 'INCIDENT_OFFER_CANCELLED';

  final String incidentId;
  final String? offerId;
  /// Hạn trả lời theo đồng hồ server.
  final DateTime expiresAt;
  /// Lúc server tạo lời mời (nếu message có).
  final DateTime? offeredAt;
  /// Lúc điện thoại nhận message.
  final DateTime receivedAt;
  /// Thông tin sự cố (IncidentDetailResponse), dispatchStage mặc định STEP1.
  final IncidentModel incident;

  IncidentOfferModel({
    required this.incidentId,
    this.offerId,
    required this.expiresAt,
    this.offeredAt,
    required this.receivedAt,
    required this.incident,
  });

  String get eventType => incident.eventType;
  String? get severity => incident.severity;
  String? get building => incident.building;
  String? get areaName => incident.areaName;
  String get cameraCode => incident.cameraCode;
  String? get imageUrl => incident.imageUrl;

  /// Hạn trả lời theo đồng hồ điện thoại. Có offeredAt: receivedAt + (expiresAt - offeredAt), để đồng hồ
  /// điện thoại lệch server không làm sai thời gian đếm ngược. Không có offeredAt: expiresAt.
  DateTime get deadline {
    final offered = offeredAt;
    if (offered != null && expiresAt.isAfter(offered)) {
      return receivedAt.add(expiresAt.difference(offered));
    }
    return expiresAt;
  }

  /// Message có thể là object phẳng hoặc bọc trong `data` / `payload`; trả object phẳng có `type`.
  static Map<String, dynamic> unwrap(Map<String, dynamic> raw) {
    final inner = raw['data'] ?? raw['payload'];
    if (inner is Map) {
      return {
        ...Map<String, dynamic>.from(inner),
        'type': raw['type'] ?? inner['type'],
      };
    }
    return raw;
  }

  static DateTime? _parseDate(dynamic v) {
    if (v == null) return null;
    if (v is DateTime) return v;
    if (v is num) return DateTime.fromMillisecondsSinceEpoch(v.toInt());
    return DateTime.tryParse(v.toString());
  }

  /// Null nếu thiếu incidentId hoặc expiresAt (không đếm ngược được thì không hiện lời mời).
  /// [receivedAt] mặc định là lúc gọi (lúc điện thoại nhận message).
  static IncidentOfferModel? fromJson(Map<String, dynamic> json, {DateTime? receivedAt}) {
    final nested = json['incident'];
    // v2: thông tin sự cố ở key "incident"; dự phòng: nằm phẳng cùng cấp (createdAt dùng làm detectedAt)
    final Map<String, dynamic> incidentJson = nested is Map
        ? Map<String, dynamic>.from(nested)
        : {'detectedAt': json['createdAt'], ...json};
    final incidentId = (json['incidentId'] ?? incidentJson['id'] ?? json['id'])?.toString() ?? '';
    final expiresAt = _parseDate(json['expiresAt']);
    if (incidentId.isEmpty || expiresAt == null) return null;

    var incident = IncidentModel.fromJson({...incidentJson, 'id': incidentId});
    if (incident.dispatchStage == null) {
      incident = incident.copyWith(dispatchStage: 'STEP1');
    }

    return IncidentOfferModel(
      incidentId: incidentId,
      offerId: json['offerId']?.toString(),
      expiresAt: expiresAt,
      offeredAt: _parseDate(json['offeredAt']),
      receivedAt: receivedAt ?? DateTime.now(),
      incident: incident,
    );
  }

  /// Thời gian còn lại tới hạn trả lời (không âm), tính theo deadline.
  Duration remaining(DateTime now) {
    final diff = deadline.difference(now);
    return diff.isNegative ? Duration.zero : diff;
  }

  /// Incident tương ứng để đưa vào hàng đợi của bảo vệ được mời (đang ở STEP1).
  IncidentModel toIncident() => incident;
}
