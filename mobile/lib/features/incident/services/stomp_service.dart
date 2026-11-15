import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:stomp_dart_client/stomp.dart';
import 'package:stomp_dart_client/stomp_config.dart';
import 'package:stomp_dart_client/stomp_frame.dart';
import '../../../core/constants/api_endpoints.dart';
import '../../../core/utils/storage_helper.dart';
import '../models/incident_model.dart';

/// Tạo StompClient từ cấu hình. Seam cho test (tiêm client giả); mặc định là StompClient thật.
typedef StompClientFactory = StompClient Function(StompConfig config);

/// Kết nối STOMP của app. Tại mọi thời điểm tồn tại tối đa 1 client (DEF-16).
///
/// Mỗi connect() và disconnect() tăng [_generation]. connect() đang chờ đọc token mà thế hệ đã đổi
/// (có connect() khác hoặc disconnect() xen vào) thì huỷ, không tạo client — tránh client mồ côi tự
/// nối lại mãi sau đăng xuất. Đang kết nối / đã kết nối với cùng guardId thì connect() lần sau không làm gì.
class StompService {
  StompService({StompClientFactory? clientFactory, Future<String?> Function()? tokenReader})
      : _clientFactory = clientFactory ?? _defaultClientFactory,
        _tokenReader = tokenReader ?? StorageHelper.getToken;

  static StompClient _defaultClientFactory(StompConfig config) => StompClient(config: config);

  final StompClientFactory _clientFactory;
  final Future<String?> Function() _tokenReader;

  StompClient? _client;
  bool _isConnected = false;
  bool get isConnected => _isConnected;

  int _generation = 0;
  bool _connecting = false;
  String? _guardId;
  String? _building;

  /// Có client đang giữ (đã activate, kể cả đang chờ nối lại) hay không.
  @visibleForTesting
  bool get hasClient => _client != null;

  VoidCallback? onConnectionChange;
  void Function(Map<String, dynamic>)? onChatMessage;

  /// Header gửi kèm STOMP CONNECT để backend phân biệt app mobile với web.
  static const Map<String, String> clientHeaders = {'client-type': 'mobile'};

  void connect({
    String? building,
    String? guardId,
    required Function(IncidentModel) onNewIncident,
    required Function(Map<String, dynamic>) onIncidentUpdate,
    Function(Map<String, dynamic>)? onOfferMessage,
  }) async {
    if ((_connecting || _client != null) && _guardId == guardId && _building == building) {
      debugPrint('[STOMP] Already connecting/connected (guardId: $guardId), skip');
      return;
    }
    disconnect();
    final generation = ++_generation;
    _connecting = true;
    _guardId = guardId;
    _building = building;

    final token = await _tokenReader();
    if (generation != _generation) {
      debugPrint('[STOMP] Connect #$generation cancelled before creating client');
      return;
    }
    _connecting = false;

    final wsUrl = ApiEndpoints.wsSecurity;
    debugPrint('[STOMP] Connecting to: $wsUrl (guardId: $guardId)');

    late final StompClient client;
    client = _clientFactory(
      StompConfig(
        url: wsUrl,
        onConnect: (StompFrame frame) {
          if (generation != _generation) return;
          debugPrint('[STOMP] Connected successfully');
          _isConnected = true;
          onConnectionChange?.call();

          // 1. Guard personal alerts are routed by duty status.
          if (guardId != null && guardId.isNotEmpty) {
            final guardTopic = '/topic/guards/$guardId/alerts';
            debugPrint('[STOMP] Subscribing to personal guard topic: $guardTopic');
            client.subscribe(
              destination: guardTopic,
              callback: (StompFrame frame) {
                if (frame.body == null || frame.body!.isEmpty) return;
                try {
                  final Map<String, dynamic> data = jsonDecode(frame.body!);
                  final incident = IncidentModel.fromJson(data);
                  onNewIncident(incident);
                } catch (e) {
                  debugPrint('[STOMP] Error parsing personal guard alert: $e');
                }
              },
            );

            // 1b. Lời mời xử lý sự cố (INCIDENT_OFFER / INCIDENT_OFFER_CANCELLED). Backend chưa có topic này
            // thì không có message nào tới, app không hiện gì mới.
            if (onOfferMessage != null) {
              client.subscribe(
                destination: '/topic/guards/$guardId/offers',
                callback: (StompFrame frame) {
                  if (frame.body == null || frame.body!.isEmpty) return;
                  try {
                    final decoded = jsonDecode(frame.body!);
                    if (decoded is Map<String, dynamic>) {
                      onOfferMessage(decoded);
                    }
                  } catch (e) {
                    debugPrint('[STOMP] Error parsing offer message: $e');
                  }
                },
              );
            }

            // 1c. Tin nhắn nội bộ giữa các bảo vệ
            final chatTopic = '/topic/guards/$guardId/messages';
            debugPrint('[STOMP] Subscribing to guard chat topic: $chatTopic');
            client.subscribe(
              destination: chatTopic,
              callback: (StompFrame frame) {
                if (frame.body == null || frame.body!.isEmpty) return;
                try {
                  final decoded = jsonDecode(frame.body!);
                  if (decoded is Map<String, dynamic>) {
                    onChatMessage?.call(decoded);
                  }
                } catch (e) {
                  debugPrint('[STOMP] Error parsing chat message: $e');
                }
              },
            );
          } else {
            // Global security alerts fallback topic (for general monitoring)
            client.subscribe(
              destination: '/topic/security-alerts',
              callback: (StompFrame frame) {
                if (frame.body == null || frame.body!.isEmpty) return;
                try {
                  final Map<String, dynamic> data = jsonDecode(frame.body!);
                  final incident = IncidentModel.fromJson(data);
                  onNewIncident(incident);
                } catch (e) {
                  debugPrint('[STOMP] Error parsing security alert: $e');
                }
              },
            );
          }

          // 2. Building specific topic (if applicable)
          if (building != null && building.isNotEmpty) {
            final buildingTopic = '/topic/buildings/${building.toUpperCase()}/alerts';
            client.subscribe(
              destination: buildingTopic,
              callback: (StompFrame frame) {
                if (frame.body == null || frame.body!.isEmpty) return;
                try {
                  final Map<String, dynamic> data = jsonDecode(frame.body!);
                  final incident = IncidentModel.fromJson(data);
                  onNewIncident(incident);
                } catch (e) {
                  debugPrint('[STOMP] Error parsing building alert: $e');
                }
              },
            );
          }

          // 3. Incident status updates (claims, resolutions)
          client.subscribe(
            destination: '/topic/incidents/updates',
            callback: (StompFrame frame) {
              if (frame.body == null || frame.body!.isEmpty) return;
              try {
                final Map<String, dynamic> data = jsonDecode(frame.body!);
                onIncidentUpdate(data);
              } catch (e) {
                debugPrint('[STOMP] Error parsing incident update: $e');
              }
            },
          );
        },
        onWebSocketError: (dynamic error) {
          if (generation != _generation) return;
          debugPrint('[STOMP] WebSocket error: $error');
          _isConnected = false;
          onConnectionChange?.call();
        },
        onDisconnect: (StompFrame frame) {
          if (generation != _generation) return;
          debugPrint('[STOMP] Disconnected');
          _isConnected = false;
          onConnectionChange?.call();
        },
        stompConnectHeaders: {
          ...clientHeaders,
          if (token != null) 'Authorization': 'Bearer $token',
        },
        webSocketConnectHeaders: token != null ? {'Authorization': 'Bearer $token'} : null,
        reconnectDelay: const Duration(seconds: 4),
      ),
    );

    _client = client;
    debugPrint('[STOMP] Client #$generation created');
    client.activate();
  }

  /// Ngắt hẳn: huỷ connect() đang chờ, deactivate client (dừng cả vòng nối lại 4 s của thư viện).
  void disconnect() {
    _generation++;
    _connecting = false;
    _guardId = null;
    _building = null;
    final client = _client;
    _client = null;
    if (client != null) {
      try {
        client.deactivate();
      } catch (_) {}
      debugPrint('[STOMP] Client deactivated');
    }
    _isConnected = false;
    onConnectionChange?.call();
  }
}
