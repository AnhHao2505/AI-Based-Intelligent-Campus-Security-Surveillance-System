import 'dart:async';
import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import '../../../core/network/api_client.dart';
import '../../../core/utils/storage_helper.dart';
import '../models/incident_model.dart';
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

  void initStomp({String? building}) {
    stompService.connect(
      building: building,
      onNewIncident: _onNewIncidentReceived,
      onIncidentUpdate: _onIncidentUpdateReceived,
    );
  }

  void disconnectStomp() {
    stompService.disconnect();
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

    notifyListeners();
  }

  void _onIncidentUpdateReceived(Map<String, dynamic> update) async {
    final incidentId = (update['incidentId'] ?? update['id'])?.toString();
    if (incidentId == null) return;

    final newStatus = (update['status'] ?? '').toString();
    final currentUser = await StorageHelper.getUser();

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
          notifyListeners();
        }
      }
    }

    // 2. Nếu sự cố đã được Resolved
    if (newStatus.startsWith('RESOLVED')) {
      _alertQueue.removeWhere((i) => i.id == incidentId);
      if (_activeIncident?.id == incidentId) {
        _activeIncident = null;
      }
      notifyListeners();
    }
  }

  /// Sắp xếp hàng đợi theo mức độ nghiêm trọng: weight DESC, cùng weight -> thời gian ASC (FIFO)
  void _sortQueue() {
    _alertQueue.sort((a, b) {
      final sevCompare = b.severityWeight.compareTo(a.severityWeight);
      if (sevCompare != 0) return sevCompare;

      final aTime = a.detectedAt ?? DateTime.now();
      final bTime = b.detectedAt ?? DateTime.now();
      return aTime.compareTo(bTime);
    });
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

      if (idx >= 0) {
        final claimed = _alertQueue[idx].copyWith(
          status: 'CLAIMED',
          claimedById: currentUser?.id,
          claimedByName: currentUser?.fullName,
          claimedAt: DateTime.now(),
        );
        _activeIncident = claimed;
        _alertQueue.removeAt(idx);
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
      return true;
    } on DioException catch (e) {
      // 409 Conflict: Người khác đã claim
      if (e.response?.statusCode == 409) {
        _alertQueue.removeWhere((i) => i.id == incidentId);
        _errorMessage = ApiClient.formatError(e);
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
    stompService.disconnect();
    super.dispose();
  }
}
