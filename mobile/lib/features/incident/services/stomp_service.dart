import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:stomp_dart_client/stomp.dart';
import 'package:stomp_dart_client/stomp_config.dart';
import 'package:stomp_dart_client/stomp_frame.dart';
import '../../../core/constants/api_endpoints.dart';
import '../../../core/utils/storage_helper.dart';
import '../models/incident_model.dart';

/// Tạo StompClient từ cấu hình (test thay bằng client giả).
typedef StompClientFactory = StompClient Function(StompConfig config);

/// Đọc JWT để gửi kèm CONNECT (test thay bằng hàm giả).
typedef StompTokenReader = Future<String?> Function();

class StompService {
  StompService({StompClientFactory? clientFactory, StompTokenReader? tokenReader})
      : _clientFactory = clientFactory ?? ((config) => StompClient(config: config)),
        _tokenReader = tokenReader ?? StorageHelper.getToken;

  final StompClientFactory _clientFactory;
  final StompTokenReader _tokenReader;

  /// DEF-16: mọi client đã tạo mà chưa đóng. Bình thường chỉ có 1; disconnect() đóng hết, kể cả client cũ.
  final List<StompClient> _clients = [];

  /// DEF-16: thế hệ kết nối. Mỗi lần connect() / disconnect() tăng lên; connect() đang chờ đọc token mà thế hệ
  /// đã đổi (có lần connect/disconnect mới hơn) thì bỏ, không tạo client.
  int _generation = 0;

  bool _isConnected = false;
  bool get isConnected => _isConnected;

  @visibleForTesting
  int get generation => _generation;

  /// Số client đang sống (đã tạo, chưa deactivate). Sau connect() xong phải là 1, sau disconnect() là 0.
  @visibleForTesting
  int get openClientCount => _clients.length;

  VoidCallback? onConnectionChange;

  /// Header gửi kèm STOMP CONNECT để backend phân biệt app mobile với web.
  static const Map<String, String> clientHeaders = {'client-type': 'mobile'};

  Future<void> connect({
    String? building,
    String? guardId,
    required Function(IncidentModel) onNewIncident,
    required Function(Map<String, dynamic>) onIncidentUpdate,
    Function(Map<String, dynamic>)? onOfferMessage,
  }) async {
    final gen = ++_generation;
    debugPrint('[STOMP] connect requested (gen=$gen, guardId: $guardId)');

    final token = await _tokenReader();
    if (gen != _generation) {
      debugPrint('[STOMP] connect gen=$gen bỏ qua: đã có lần connect/disconnect mới hơn (gen=$_generation)');
      return;
    }
    // Chỉ giữ một client: đóng mọi client cũ trước khi tạo client mới.
    _closeAllClients();

    final wsUrl = ApiEndpoints.wsSecurity;
    debugPrint('[STOMP] Connecting to: $wsUrl (guardId: $guardId, gen=$gen)');

    late final StompClient client;
    client = _clientFactory(
      StompConfig(
        url: wsUrl,
        onConnect: (StompFrame frame) {
          if (gen != _generation) {
            debugPrint('[STOMP] onConnect của client cũ gen=$gen (hiện gen=$_generation): đóng, không subscribe');
            try {
              client.deactivate();
            } catch (_) {}
            return;
          }
          debugPrint('[STOMP] Connected successfully (gen=$gen)');
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
              final offerTopic = '/topic/guards/$guardId/offers';
              debugPrint('[STOMP] Subscribing to offer topic: $offerTopic (gen=$gen)');
              client.subscribe(
                destination: offerTopic,
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
          debugPrint('[STOMP] WebSocket error (gen=$gen): $error');
          if (gen != _generation) return;
          _isConnected = false;
          onConnectionChange?.call();
        },
        onDisconnect: (StompFrame frame) {
          debugPrint('[STOMP] Disconnected (gen=$gen)');
          if (gen != _generation) return;
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

    _clients.add(client);
    client.activate();
  }

  /// Đóng MỌI client đã tạo (DEF-16) và huỷ các lần connect() đang chờ token.
  void disconnect() {
    final gen = ++_generation;
    final closed = _closeAllClients();
    debugPrint('[STOMP] disconnect (gen=$gen): đã đóng $closed client');
    _isConnected = false;
    onConnectionChange?.call();
  }

  int _closeAllClients() {
    final count = _clients.length;
    for (final c in List<StompClient>.of(_clients)) {
      try {
        c.deactivate();
      } catch (_) {}
    }
    _clients.clear();
    return count;
  }
}
