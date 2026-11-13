import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import '../../../core/constants/api_endpoints.dart';
import '../data/campus_route_planner.dart';

/// Đọc tốc độ đi bộ / thời gian đổi tầng (GET /api/system-configurations/route-settings).
/// Lỗi mạng / phản hồi sai → ghi log và dùng mặc định, không chặn việc dẫn đường.
class RouteSettingsService {
  final Dio _dio;

  RouteSettingsService(this._dio);

  Future<RouteEtaSettings> load() async {
    try {
      final response = await _dio.get(ApiEndpoints.routeSettings);
      var data = response.data;
      if (data is Map && data['data'] is Map) data = data['data'];
      if (data is Map) return RouteEtaSettings.fromJson(Map<String, dynamic>.from(data));
      debugPrint('[RouteSettingsService] Phản hồi route-settings không đúng dạng, dùng mặc định');
    } catch (e) {
      debugPrint('[RouteSettingsService] Không đọc được route-settings, dùng mặc định: $e');
    }
    return RouteEtaSettings.defaults;
  }
}
