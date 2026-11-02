import 'dart:convert';
import 'package:flutter/foundation.dart';
import 'package:stomp_dart_client/stomp.dart';
import 'package:stomp_dart_client/stomp_config.dart';
import 'package:stomp_dart_client/stomp_frame.dart';
import '../../../core/constants/api_endpoints.dart';
import '../../../core/utils/storage_helper.dart';
import '../models/incident_model.dart';

class StompService {
  StompClient? _client;
  bool _isConnected = false;
  bool get isConnected => _isConnected;

  VoidCallback? onConnectionChange;

  /// Header gửi kèm STOMP CONNECT để backend phân biệt app mobile với web.
  static const Map<String, String> clientHeaders = {'client-type': 'mobile'};

  void connect({
    String? building,
    String? guardId,
    required Function(IncidentModel) onNewIncident,
    required Function(Map<String, dynamic>) onIncidentUpdate,
    Function(Map<String, dynamic>)? onOfferMessage,
  }) async {
    disconnect();

    final token = await StorageHelper.getToken();
    final wsUrl = ApiEndpoints.wsSecurity;
    debugPrint('[STOMP] Connecting to: $wsUrl (guardId: $guardId)');

    _client = StompClient(
      config: StompConfig(
        url: wsUrl,
        onConnect: (StompFrame frame) {
          debugPrint('[STOMP] Connected successfully');
          _isConnected = true;
          onConnectionChange?.call();

          // 1. Guard personal alerts are routed by duty status.
          if (guardId != null && guardId.isNotEmpty) {
            final guardTopic = '/topic/guards/$guardId/alerts';
            debugPrint('[STOMP] Subscribing to personal guard topic: $guardTopic');
            _client?.subscribe(
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
              _client?.subscribe(
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
          } else {
            // Global security alerts fallback topic (for general monitoring)
            _client?.subscribe(
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
            _client?.subscribe(
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
          _client?.subscribe(
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
          debugPrint('[STOMP] WebSocket error: $error');
          _isConnected = false;
          onConnectionChange?.call();
        },
        onDisconnect: (StompFrame frame) {
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

    _client?.activate();
  }

  void disconnect() {
    if (_client != null) {
      try {
        _client?.deactivate();
      } catch (_) {}
      _client = null;
    }
    _isConnected = false;
    onConnectionChange?.call();
  }
}
