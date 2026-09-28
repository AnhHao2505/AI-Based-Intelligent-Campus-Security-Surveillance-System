import '../../../core/constants/api_endpoints.dart';
import '../../../core/network/api_client.dart';
import '../models/incident_model.dart';

class IncidentService {
  final ApiClient _apiClient;

  IncidentService(this._apiClient);

  /// Lấy danh sách sự cố đang active
  Future<List<IncidentModel>> getActiveIncidents({String? building}) async {
    final url = (building != null && building.isNotEmpty)
        ? ApiEndpoints.activeIncidents(building)
        : ApiEndpoints.activeIncidentsAll;

    final response = await _apiClient.dio.get(url);
    final rawData = response.data;

    if (rawData is List) {
      return rawData
          .map((item) => IncidentModel.fromJson(item as Map<String, dynamic>))
          .toList();
    }
    return [];
  }

  /// Tiếp nhận xử lý sự cố (Claim)
  Future<Map<String, dynamic>> claimIncident(String incidentId) async {
    final response = await _apiClient.dio.post(
      ApiEndpoints.claimIncident(incidentId),
    );
    if (response.data is Map<String, dynamic>) {
      return response.data as Map<String, dynamic>;
    }
    return {'success': true};
  }

  /// Báo cáo giải quyết sự cố (Resolve)
  Future<IncidentModel> resolveIncident(
    String incidentId, {
    required String outcome,
    required String resolutionCategory,
    String? resolutionNotes,
    String? evidenceImageUrl,
  }) async {
    final response = await _apiClient.dio.post(
      ApiEndpoints.resolveIncident(incidentId),
      data: {
        'outcome': outcome,
        'resolutionCategory': resolutionCategory,
        'resolutionNotes': resolutionNotes ?? 'Bảo vệ đã kiểm tra và xử lý tại hiện trường',
        'evidenceImageUrl': evidenceImageUrl,
      },
    );

    return IncidentModel.fromJson(response.data as Map<String, dynamic>);
  }
}
