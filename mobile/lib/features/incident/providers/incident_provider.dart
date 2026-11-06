import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart' show WidgetsBinding;
import 'dart:ui' show AppLifecycleState;
import 'package:intl/intl.dart';
import '../../../core/notifications/local_notification_service.dart';
import '../../../core/network/api_client.dart';
import '../../../core/utils/storage_helper.dart';
import '../models/incident_model.dart';
import '../models/incident_offer_model.dart';
import '../models/incident_queue_order.dart';
import '../services/incident_service.dart';
import '../services/stomp_service.dart';

class IncidentProvider extends ChangeNotifier {
  final IncidentService incidentService;
  final StompService stompService;

  IncidentProvider({
    required this.incidentService,
    required this.stompService,
  }) {
    stompService.onConnectionChange = () {
      notifyListeners();
    };
  }

  // ═══════════════ MULTI-INCIDENT STATE ═══════════════

  /// Hàng đợi cảnh báo: sắp xếp theo severity (3 -> 2 -> 1),
  /// trong cùng severity -> FIFO (thời gian đến trước hiện trước)
  final List<IncidentModel> _alertQueue = [];
  List<IncidentModel> get alertQueue => List.unmodifiable(_alertQueue);

  /// Cảnh báo đầu hàng đợi đang hiển thị
  IncidentModel? get currentAlert => _alertQueue.isNotEmpty ? _alertQueue.first : null;

  /// Incident mà guard hiện tại đang xử lý (đã claim)
  IncidentModel? _activeIncident;
  IncidentModel? get activeIncident => _activeIncident;

  /// Tổng số sự cố chưa xử lý trong hàng đợi
  int get pendingCount => _alertQueue.length;

  /// Guard có đang xử lý 1 sự cố nào không
  bool get isHandlingIncident => _activeIncident != null;

  bool _isClaimLoading = false;
  bool get isClaimLoading => _isClaimLoading;

  bool _isResolveLoading = false;
  bool get isResolveLoading => _isResolveLoading;

  bool _isFetchingActive = false;
  bool get isFetchingActive => _isFetchingActive;

  String? _errorMessage;
  String? get errorMessage => _errorMessage;

  String? _peerClaimNotice;
  String? get peerClaimNotice => _peerClaimNotice;

  Timer? _debounceSortTimer;

  // ═══════════════ L5: TOÀ ĐANG TRỰC ═══════════════

  /// Toà của ca CHECKED_IN hiện tại (đã chuẩn hoá: trim + chữ thường). null = chưa biết toà → không lọc
  /// (giữ hành vi cũ). Tập rỗng = không có ca đang trực → chỉ còn sự cố mình được mời / đang xử lý.
  Set<String>? _dutyBuildings;
  Set<String>? get dutyBuildings => _dutyBuildings == null ? null : Set.unmodifiable(_dutyBuildings!);

  // ═══════════════ L10: TRẠNG THÁI MỚI NHẤT THEO /topic/incidents/updates ═══════════════

  /// Bản cập nhật trạng thái mới nhất của từng sự cố (CLAIMED / RESOLVED_*). Màn danh sách dùng để sửa bản
  /// lịch sử đã tải trước đó (vd. sự cố đã được đóng ngoài app vẫn hiện ở tab "Đang xử lý").
  final Map<String, IncidentModel> _latestStatus = {};

  // ═══════════════ OFFER STATE (bước 1 phân phối theo đợt) ═══════════════

  static const String claimConflictMessage = 'Đã có người nhận';
  static const String notOfferedMessage = 'Sự cố đang được mời bảo vệ khác';

  /// Lời mời đang chờ trả lời của bảo vệ hiện tại, theo incidentId. Mỗi lời mời có timer tới deadline.
  final Map<String, IncidentOfferModel> _offers = {};
  final Map<String, Timer> _offerTimers = {};

  /// Lời mời đang chờ, hạn gần nhất trước.
  List<IncidentOfferModel> get offers =>
      _offers.values.toList()..sort((a, b) => a.deadline.compareTo(b.deadline));

  IncidentOfferModel? get currentOffer => offers.isNotEmpty ? offers.first : null;

  bool hasOfferFor(String incidentId) => _offers.containsKey(incidentId);

  /// Khoá nút claim: sự cố đang mời bảo vệ khác (STEP1) và bảo vệ này không có lời mời.
  /// dispatchStage null (backend cũ) = BROADCAST → không khoá.
  bool isClaimLockedFor(IncidentModel incident) =>
      incident.isNew && incident.isOfferPhase && !_offers.containsKey(incident.id);

  bool _isDeclineLoading = false;
  bool get isDeclineLoading => _isDeclineLoading;

  bool get isStompConnected => stompService.isConnected;

  void clearErrorMessage() {
    _errorMessage = null;
    notifyListeners();
  }

  void clearPeerClaimNotice() {
    _peerClaimNotice = null;
    notifyListeners();
  }

  // ═══════════════ WEBSOCKET INITIALIZATION ═══════════════

  /// Chỉ GuardDutyWatcher gọi (DEF-16): một nơi sở hữu kết nối, các màn hình không tự gọi.
  void initStomp({String? building}) async {
    // Đã đăng nhập và bắt đầu nhận cảnh báo → xin quyền hiển thị thông báo hệ thống (Android 13+ / iOS).
    LocalNotificationService.instance.requestPermission();
    final currentUser = await StorageHelper.getUser();
    stompService.connect(
      building: building,
      guardId: currentUser?.id,
      onNewIncident: _onNewIncidentReceived,
      onIncidentUpdate: _onIncidentUpdateReceived,
      onOfferMessage: handleOfferMessage,
    );
  }

  void disconnectStomp() {
    stompService.disconnect();
    _clearOffers();
  }

  /// DEF-16: đăng xuất → xoá toàn bộ dữ liệu sự cố của phiên (hàng đợi, sự cố đang xử lý, lời mời, thông báo,
  /// lọc toà, trạng thái đã nhận). Không đóng STOMP (nơi gọi đóng riêng bằng [disconnectStomp]).
  void resetSession() {
    _debounceSortTimer?.cancel();
    _debounceSortTimer = null;
    _clearOffers();
    final cleared = _alertQueue.length;
    _alertQueue.clear();
    _activeIncident = null;
    _peerClaimNotice = null;
    _errorMessage = null;
    _dutyBuildings = null;
    _latestStatus.clear();
    debugPrint('[IncidentProvider] resetSession: xoá $cleared sự cố trong hàng đợi, lời mời và sự cố đang xử lý');
    notifyListeners();
  }

  // ═══════════════ L5: LỌC THEO TOÀ ĐANG TRỰC ═══════════════

  static String? normalizeBuilding(String? building) {
    final t = building?.trim().toLowerCase();
    return (t == null || t.isEmpty) ? null : t;
  }

  /// Đặt toà đang trực (null = không lọc). Bỏ khỏi hàng đợi các sự cố ngoài phạm vi. Trả true nếu có thay đổi.
  bool setDutyBuildings(Set<String>? buildings) {
    final normalized = buildings?.map(normalizeBuilding).whereType<String>().toSet();
    if (setEquals(normalized, _dutyBuildings)) return false;
    _dutyBuildings = normalized;
    final before = _alertQueue.length;
    _alertQueue.removeWhere((i) => !isInDutyScope(i));
    debugPrint('[IncidentProvider] duty buildings = $normalized; bỏ ${before - _alertQueue.length} sự cố ngoài toà');
    notifyListeners();
    return true;
  }

  /// L5 (phương án A): sự cố của toà đang trực, CỘNG sự cố mình đang được mời hoặc đang xử lý / đã xử lý
  /// (dù khác toà). Chưa biết toà ([dutyBuildings] null) → mọi sự cố.
  bool isInDutyScope(IncidentModel incident, {String? currentUserId}) {
    final scope = _dutyBuildings;
    if (scope == null) return true;
    if (_offers.containsKey(incident.id)) return true;
    if (_activeIncident?.id == incident.id) return true;
    if (currentUserId != null &&
        (incident.claimedById == currentUserId || incident.resolvedById == currentUserId)) {
      return true;
    }
    final building = normalizeBuilding(incident.building);
    return building != null && scope.contains(building);
  }

  // ═══════════════ L10: ÁP TRẠNG THÁI MỚI NHẤT ═══════════════

  static int _statusRank(String status) {
    if (status == 'NEW') return 0;
    if (status == 'CLAIMED') return 1;
    if (status.startsWith('RESOLVED')) return 2;
    return -1;
  }

  /// Trả bản sự cố với trạng thái mới nhất đã nhận qua /topic/incidents/updates nếu trạng thái đó đi xa hơn
  /// (NEW → CLAIMED → RESOLVED_*); không bao giờ lùi trạng thái.
  IncidentModel withLatestStatus(IncidentModel incident) {
    final latest = _latestStatus[incident.id];
    if (latest == null || _statusRank(latest.status) <= _statusRank(incident.status)) return incident;
    return incident.copyWith(
      status: latest.status,
      claimedById: latest.claimedById,
      claimedByName: latest.claimedByName,
      claimedAt: latest.claimedAt,
      resolvedById: latest.resolvedById,
      resolvedByName: latest.resolvedByName,
      resolvedAt: latest.resolvedAt,
      outcome: latest.outcome,
      resolutionCategory: latest.resolutionCategory,
    );
  }

  void _rememberStatus(String incidentId, Map<String, dynamic> update) {
    final status = (update['status'] ?? '').toString();
    if (_statusRank(status) <= 0) return;
    final previous = _latestStatus[incidentId];
    if (previous != null && _statusRank(previous.status) > _statusRank(status)) return;
    _latestStatus[incidentId] = IncidentModel.fromJson({...update, 'id': incidentId, 'status': status});
  }

  /// Lấy danh sách sự cố đang active từ backend khi mở app
  Future<void> loadActiveIncidents({String? building}) async {
    _isFetchingActive = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final activeList = await incidentService.getActiveIncidents(building: building);
      final currentUser = await StorageHelper.getUser();

      for (final incident in activeList) {
        if (incident.isClaimed && currentUser != null && incident.claimedById == currentUser.id) {
          _activeIncident = incident;
        } else if (incident.isNew) {
          if (!isInDutyScope(incident)) continue; // L5: sự cố toà khác
          if (!_alertQueue.any((i) => i.id == incident.id)) {
            _alertQueue.add(incident);
          }
        }
      }
      _sortQueue();
    } catch (e) {
      debugPrint('[IncidentProvider] Error loading active incidents: $e');
    } finally {
      _isFetchingActive = false;
      notifyListeners();
    }
  }

  // ═══════════════ STOMP EVENT HANDLERS ═══════════════

  void _onNewIncidentReceived(IncidentModel incident) {
    debugPrint('[IncidentProvider] received incident ${incident.id} (${incident.eventType}), '
        'lifecycle=${WidgetsBinding.instance.lifecycleState}');
    // L5: cảnh báo của toà khác (không được mời, không đang xử lý) không vào hàng đợi
    if (!isInDutyScope(incident)) {
      debugPrint('[IncidentProvider] skip incident ${incident.id}: toà "${incident.building}" ngoài toà đang trực '
          '$_dutyBuildings');
      return;
    }
    // K8: đã có trong hàng đợi thì cập nhật severity / dispatchStage trước khi bỏ qua bản trùng
    // (vd. cảnh báo broadcast tới sau khi bảo vệ đã thấy sự cố lúc STEP1).
    final existingIdx = _alertQueue.indexWhere((i) => i.id == incident.id);
    if (existingIdx >= 0) {
      final wasOfferPhase = _alertQueue[existingIdx].isOfferPhase;
      if (_mergeDispatchInfo(existingIdx, incident.severity, incident.dispatchStage)) {
        // STEP1 → BROADCAST: bảo vệ này giờ mới nhận được, báo như sự cố mới
        if (wasOfferPhase && !_alertQueue[existingIdx].isOfferPhase) {
          _showSystemNotification(_alertQueue[existingIdx]);
        }
        _sortQueue();
        notifyListeners();
      }
    }
    // Tránh trùng lặp ID
    if (_alertQueue.any((i) => i.id == incident.id)) return;
    if (_activeIncident?.id == incident.id) return;

    _alertQueue.add(incident);

    // Debounce sort khi nhiều cảnh báo ập tới cùng lúc (< 300ms)
    _debounceSortTimer?.cancel();
    _debounceSortTimer = Timer(const Duration(milliseconds: 250), () {
      _sortQueue();
      notifyListeners();
    });

    // Báo rung thiết bị
    try {
      HapticFeedback.heavyImpact();
    } catch (_) {}

    _showSystemNotification(incident);

    notifyListeners();
  }

  /// Luôn hiện thông báo hệ thống (kể cả khi app đang mở): banner trong app chỉ có ở màn bản đồ,
  /// bảo vệ đang ở màn khác (Lịch Trực…) sẽ không thấy gì nếu chỉ dựa vào banner.
  /// Chỉ hiện khi còn phiên đăng nhập.
  Future<void> _showSystemNotification(IncidentModel incident) async {
    final user = await StorageHelper.getUser();
    if (user == null) {
      debugPrint('[IncidentProvider] skip system notification: no session');
      return;
    }

    final parts = <String>[
      incident.eventTypeDisplay,
      incident.areaName ?? 'Khu vực chưa rõ',
      if (incident.detectedAt != null) DateFormat('HH:mm').format(incident.detectedAt!.toLocal()),
    ];
    await LocalNotificationService.instance.showIncident(
      incidentId: incident.id,
      title: 'Sự cố an ninh mới',
      body: parts.join(' · '),
    );
  }

  void _onIncidentUpdateReceived(Map<String, dynamic> update) async {
    final incidentId = (update['incidentId'] ?? update['id'])?.toString();
    if (incidentId == null) return;

    final newStatus = (update['status'] ?? '').toString();
    // L10: nhớ trạng thái mới nhất để màn danh sách sửa bản lịch sử đã tải
    _rememberStatus(incidentId, update);
    final currentUser = await StorageHelper.getUser();

    // Sự cố đã có người tiếp nhận hoặc đã xử lý xong → gỡ thông báo hệ thống của sự cố đó.
    if (newStatus == 'CLAIMED' || newStatus.startsWith('RESOLVED')) {
      LocalNotificationService.instance.cancelIncident(incidentId);
      // K8: và gỡ lời mời của sự cố đó
      _removeOffer(incidentId);
    }

    // 0. Đọc thêm severity / dispatchStage (backend cũ không gửi → bỏ qua)
    final severity = update['severity']?.toString();
    final dispatchStage = update['dispatchStage']?.toString();
    if (severity != null || dispatchStage != null) {
      final idx = _alertQueue.indexWhere((i) => i.id == incidentId);
      if (idx >= 0) {
        if (_mergeDispatchInfo(idx, severity, dispatchStage)) {
          _sortQueue();
          notifyListeners();
        }
      } else if ((newStatus.isEmpty || newStatus == 'NEW') &&
          dispatchStage?.toUpperCase() == 'STEP1' &&
          _activeIncident?.id != incidentId) {
        // Bảo vệ không được mời: chỉ thêm vào danh sách (nút claim bị khoá), không rung, không thông báo
        final pending = IncidentModel.fromJson({...update, 'id': incidentId, 'status': 'NEW'});
        if (isInDutyScope(pending)) _alertQueue.add(pending); // L5: toà khác thì không thêm
        _sortQueue();
        notifyListeners();
      }
    }

    // 1. Nếu có người khác Claim
    if (newStatus == 'CLAIMED') {
      final claimantId = update['claimedById']?.toString();
      final claimantName = update['claimedByName']?.toString() ?? 'Đồng đội khác';

      // Nếu không phải chính guard này
      if (currentUser == null || claimantId != currentUser.id) {
        final removedCount = _alertQueue.where((i) => i.id == incidentId).length;
        if (removedCount > 0) {
          _alertQueue.removeWhere((i) => i.id == incidentId);
          _peerClaimNotice = 'Sự cố đã được $claimantName tiếp nhận.';
          debugPrint('[IncidentProvider] incident $incidentId claimed by peer $claimantId → peerClaimNotice');
          notifyListeners();
        }
      }
    }

    // 2. Nếu sự cố đã được Resolved (kể cả đóng ngoài app) → bỏ khỏi hàng đợi và khỏi "Đang xử lý" (L10)
    if (newStatus.startsWith('RESOLVED')) {
      _alertQueue.removeWhere((i) => i.id == incidentId);
      if (_activeIncident?.id == incidentId) {
        debugPrint('[IncidentProvider] incident $incidentId đang xử lý đã đóng ($newStatus) → bỏ khỏi "Đang xử lý"');
        _activeIncident = null;
      }
      notifyListeners();
    }
  }

  /// Sắp xếp hàng đợi: mức server ↓ → trọng số nhãn ↓ → cũ trước (FIFO). Cùng hàm với màn danh sách (L12).
  void _sortQueue() {
    _alertQueue.sort(compareIncidentQueueOrder);
  }

  // ═══════════════ USER ACTIONS ═══════════════

  /// Guard bấm "Bỏ qua" sự cố đang hiện -> Đẩy xuống cuối hàng đợi
  void dismissCurrentAlert() {
    if (_alertQueue.isEmpty) return;
    final dismissed = _alertQueue.removeAt(0);
    _alertQueue.add(dismissed);
    notifyListeners();
  }

  /// Guard bấm "Tiếp nhận & Dẫn đường"
  Future<bool> claimIncident(String incidentId) async {
    if (_isClaimLoading) return false;
    _isClaimLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      await incidentService.claimIncident(incidentId);

      // Chuyển incident sang active
      final idx = _alertQueue.indexWhere((i) => i.id == incidentId);
      final currentUser = await StorageHelper.getUser();
      // Bản ghi đang nằm ở vị trí idx sau khi chờ getUser (có thể không còn là sự cố vừa nhận)
      final atIdx = (idx >= 0 && idx < _alertQueue.length) ? _alertQueue[idx] : null;
      final offer = _offers[incidentId];
      _removeOffer(incidentId);

      if (idx >= 0 && idx < _alertQueue.length) {
        final claimed = _alertQueue[idx].copyWith(
          status: 'CLAIMED',
          claimedById: currentUser?.id,
          claimedByName: currentUser?.fullName,
          claimedAt: DateTime.now(),
        );
        _activeIncident = claimed;
        _alertQueue.removeAt(idx);
      } else if (offer != null) {
        _activeIncident = offer.toIncident().copyWith(
          status: 'CLAIMED',
          claimedById: currentUser?.id,
          claimedByName: currentUser?.fullName,
          claimedAt: DateTime.now(),
        );
      } else {
        _activeIncident = IncidentModel(
          id: incidentId,
          cameraCode: 'CAM-ACTIVE',
          eventType: 'SECURITY_ALERT',
          status: 'CLAIMED',
          claimedById: currentUser?.id,
          claimedByName: currentUser?.fullName,
          claimedAt: DateTime.now(),
        );
      }
      // Vá phòng ngừa: idx lấy trước await nên có thể trỏ nhầm sự cố khác nếu trong lúc chờ hàng đợi được thêm
      // hoặc sắp xếp lại. Tìm lại theo incidentId: sự cố vừa nhận phải là active (CLAIMED) và không còn sót trong
      // hàng đợi; sự cố bị lấy nhầm ở vị trí idx được trả lại hàng đợi. Sự cố vẫn còn trong hàng đợi (idx không còn
      // hợp lệ vì hàng đợi ngắn đi) → lấy dữ liệu từ hàng đợi thay cho bản tạm CAM-ACTIVE.
      if (_activeIncident?.id != incidentId || _alertQueue.any((i) => i.id == incidentId)) {
        final latestIdx = _alertQueue.indexWhere((i) => i.id == incidentId);
        final source = latestIdx >= 0 ? _alertQueue[latestIdx] : offer?.toIncident();
        _activeIncident = (source ??
                IncidentModel(id: incidentId, cameraCode: 'CAM-ACTIVE', eventType: 'SECURITY_ALERT', status: 'NEW'))
            .copyWith(
          status: 'CLAIMED',
          claimedById: currentUser?.id,
          claimedByName: currentUser?.fullName,
          claimedAt: DateTime.now(),
        );
        if (atIdx != null && atIdx.id != incidentId && !_alertQueue.any((i) => i.id == atIdx.id)) {
          _alertQueue.add(atIdx);
          _sortQueue();
        }
      }
      _alertQueue.removeWhere((i) => i.id == incidentId);
      return true;
    } on DioException catch (e) {
      // 409 Conflict: Người khác đã claim
      if (e.response?.statusCode == 409) {
        _alertQueue.removeWhere((i) => i.id == incidentId);
        _errorMessage = ApiClient.formatError(e);
        // K8: gỡ lời mời; message server nếu có, không có thì "Đã có người nhận"
        _removeOffer(incidentId);
        _errorMessage = conflictMessageFrom(e.response?.data);
      } else if (e.response?.statusCode == 403 && isNotOfferedError(e.response?.data)) {
        // 403 INCIDENT_NOT_OFFERED: sự cố đang ở STEP1 và bảo vệ này không (còn) được mời → khoá nút claim
        _removeOffer(incidentId);
        final idx = _alertQueue.indexWhere((i) => i.id == incidentId);
        if (idx >= 0) {
          _alertQueue[idx] = _alertQueue[idx].copyWith(dispatchStage: 'STEP1');
        }
        _errorMessage = notOfferedMessage;
      } else {
        _errorMessage = ApiClient.formatError(e);
      }
      return false;
    } catch (e) {
      _errorMessage = 'Không thể tiếp nhận sự cố: $e';
      return false;
    } finally {
      _isClaimLoading = false;
      notifyListeners();
    }
  }

  /// Câu báo lỗi 409 khi claim: message server trả về nếu có, không có thì "Đã có người nhận".
  static String conflictMessageFrom(dynamic body) {
    if (body is Map) {
      final message = body['message']?.toString().trim();
      if (message != null && message.isNotEmpty) return message;
    }
    return claimConflictMessage;
  }

  /// Body lỗi của backend có mã INCIDENT_NOT_OFFERED (field errorCode hoặc code).
  static bool isNotOfferedError(dynamic body) {
    if (body is! Map) return false;
    return body['errorCode']?.toString() == 'INCIDENT_NOT_OFFERED' ||
        body['code']?.toString() == 'INCIDENT_NOT_OFFERED';
  }

  /// Guard bấm "Từ chối" lời mời. Thành công hoặc lời mời không còn (404/409) → ẩn lời mời.
  Future<bool> declineOffer(String incidentId) async {
    if (_isDeclineLoading) return false;
    _isDeclineLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      await incidentService.declineOffer(incidentId);
      _removeOffer(incidentId);
      return true;
    } on DioException catch (e) {
      final statusCode = e.response?.statusCode;
      if (statusCode == 404 || statusCode == 409) {
        _removeOffer(incidentId);
      }
      _errorMessage = ApiClient.formatError(e);
      return false;
    } catch (e) {
      _errorMessage = 'Không thể từ chối lời mời: $e';
      return false;
    } finally {
      _isDeclineLoading = false;
      notifyListeners();
    }
  }

  // ═══════════════ OFFER HANDLERS ═══════════════

  /// Message trên /topic/guards/{id}/offers, phân biệt bằng field type.
  void handleOfferMessage(Map<String, dynamic> raw) {
    final data = IncidentOfferModel.unwrap(raw);
    final type = data['type']?.toString().toUpperCase();
    debugPrint('[IncidentProvider] offer message type=$type incidentId=${data['incidentId']} '
        'offerId=${data['offerId']} expiresAt=${data['expiresAt']}');
    if (type == IncidentOfferModel.typeOffer) {
      final offer = IncidentOfferModel.fromJson(data);
      if (offer != null) {
        _onOfferReceived(offer);
      } else {
        debugPrint('[IncidentProvider] ignore offer: không đọc được (thiếu incidentId / expiresAt)');
      }
    } else if (type == IncidentOfferModel.typeCancelled) {
      final incidentId = (data['incidentId'] ?? data['id'])?.toString();
      if (incidentId != null && incidentId.isNotEmpty) {
        debugPrint('[IncidentProvider] offer cancelled incident=$incidentId reason=${data['reason']}');
        _removeOffer(incidentId, offerId: data['offerId']?.toString());
      }
    } else {
      debugPrint('[IncidentProvider] ignore offer message with type=$type');
    }
  }

  void _onOfferReceived(IncidentOfferModel offer) {
    final id = offer.incidentId;
    // Đã hết hạn khi tới nơi, hoặc bảo vệ đang xử lý chính sự cố này → bỏ qua
    if (!offer.deadline.isAfter(DateTime.now())) {
      debugPrint('[IncidentProvider] ignore offer $id: đã hết hạn khi tới (deadline ${offer.deadline.toIso8601String()})');
      return;
    }
    if (_activeIncident?.id == id) {
      debugPrint('[IncidentProvider] ignore offer $id: đang xử lý chính sự cố này');
      return;
    }
    debugPrint('[IncidentProvider] offer received $id offerId=${offer.offerId} '
        'remaining=${offer.remaining(DateTime.now()).inSeconds}s');

    _offers[id] = offer;
    _offerTimers[id]?.cancel();
    _offerTimers[id] = Timer(offer.remaining(DateTime.now()), () {
      _offerTimers.remove(id);
      // Chỉ hết hạn đúng lời mời đã đặt timer (không xoá lời mời mới hơn cho cùng sự cố)
      if (_offers[id]?.offerId == offer.offerId) _removeOffer(id);
    });

    // Sự cố hiện trong hàng đợi của bảo vệ được mời (nút claim không bị khoá vì có lời mời)
    final idx = _alertQueue.indexWhere((i) => i.id == id);
    if (idx >= 0) {
      _mergeDispatchInfo(idx, offer.severity, 'STEP1');
    } else {
      _alertQueue.add(offer.toIncident());
    }
    _sortQueue();

    try {
      HapticFeedback.heavyImpact();
    } catch (_) {}

    final lifecycle = WidgetsBinding.instance.lifecycleState;
    if (lifecycle != null && lifecycle != AppLifecycleState.resumed) {
      _showOfferNotification(offer);
    }
    notifyListeners();
  }

  Future<void> _showOfferNotification(IncidentOfferModel offer) async {
    final user = await StorageHelper.getUser();
    if (user == null) return;
    final incident = offer.toIncident();
    final parts = <String>[
      incident.eventTypeDisplay,
      offer.areaName ?? 'Khu vực chưa rõ',
      'trả lời trước ${DateFormat('HH:mm:ss').format(offer.deadline.toLocal())}',
    ];
    await LocalNotificationService.instance.showIncident(
      incidentId: offer.incidentId,
      title: 'Bạn được mời xử lý sự cố',
      body: parts.join(' · '),
    );
  }

  /// Ẩn lời mời (bị huỷ, hết hạn, đã claim, đã từ chối). offerId khác lời mời đang giữ → bỏ qua (message cũ).
  void _removeOffer(String incidentId, {String? offerId}) {
    final current = _offers[incidentId];
    if (current == null) return;
    if (offerId != null && current.offerId != null && offerId != current.offerId) return;
    _offers.remove(incidentId);
    _offerTimers.remove(incidentId)?.cancel();
    LocalNotificationService.instance.cancelIncident(incidentId);
    notifyListeners();
  }

  void _clearOffers() {
    for (final timer in _offerTimers.values) {
      timer.cancel();
    }
    _offerTimers.clear();
    _offers.clear();
  }

  /// Cập nhật severity / dispatchStage của sự cố trong hàng đợi. Trả true nếu có thay đổi.
  bool _mergeDispatchInfo(int idx, String? severity, String? dispatchStage) {
    final current = _alertQueue[idx];
    final newSeverity = severity ?? current.severity;
    final newStage = dispatchStage ?? current.dispatchStage;
    if (newSeverity == current.severity && newStage == current.dispatchStage) return false;
    // Không sắp xếp ở đây để idx của nơi gọi còn đúng; nơi gọi tự gọi _sortQueue()
    _alertQueue[idx] = current.copyWith(severity: newSeverity, dispatchStage: newStage);
    return true;
  }

  @visibleForTesting
  void receiveIncidentForTest(IncidentModel incident) => _onNewIncidentReceived(incident);

  @visibleForTesting
  void receiveUpdateForTest(Map<String, dynamic> update) => _onIncidentUpdateReceived(update);

  /// Guard hoàn tất xử lý sự cố (Submit resolution)
  Future<bool> resolveIncident({
    required String outcome,
    required String resolutionCategory,
    String? resolutionNotes,
    String? evidenceImageUrl,
  }) async {
    if (_activeIncident == null || _isResolveLoading) return false;

    _isResolveLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final resolved = await incidentService.resolveIncident(
        _activeIncident!.id,
        outcome: outcome,
        resolutionCategory: resolutionCategory,
        resolutionNotes: resolutionNotes,
        evidenceImageUrl: evidenceImageUrl,
      );

      _activeIncident = null;
      _alertQueue.removeWhere((i) => i.id == resolved.id);
      _latestStatus[resolved.id] = resolved;
      return true;
    } on DioException catch (e) {
      _errorMessage = ApiClient.formatError(e);
      return false;
    } catch (e) {
      _errorMessage = 'Lỗi gửi báo cáo xử lý: $e';
      return false;
    } finally {
      _isResolveLoading = false;
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _debounceSortTimer?.cancel();
    _clearOffers();
    stompService.disconnect();
    super.dispose();
  }
}
