import 'package:dio/dio.dart';
import '../constants/api_endpoints.dart';
import '../utils/storage_helper.dart';

class ApiClient {
  late final Dio dio;

  ApiClient() {
    dio = Dio(
      BaseOptions(
        baseUrl: ApiEndpoints.baseUrl,
        connectTimeout: const Duration(seconds: 10),
        receiveTimeout: const Duration(seconds: 10),
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
      ),
    );

    dio.interceptors.add(
      InterceptorsWrapper(
        onRequest: (options, handler) async {
          // Always ensure the latest baseUrl is used
          options.baseUrl = ApiEndpoints.baseUrl;

          // Inject Bearer token if available
          final token = await StorageHelper.getToken();
          if (token != null && token.isNotEmpty) {
            options.headers['Authorization'] = 'Bearer $token';
          }
          return handler.next(options);
        },
        onError: (DioException error, handler) {
          return handler.next(error);
        },
      ),
    );
  }

  // Helper method to format human-readable error messages
  static String formatError(dynamic error) {
    if (error is DioException) {
      if (error.response != null) {
        final data = error.response?.data;
        if (data is Map && data.containsKey('message')) {
          return data['message'].toString();
        }
        if (data is String && data.isNotEmpty) {
          return data;
        }
        if (error.response?.statusCode == 401) {
          return 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.';
        }
        if (error.response?.statusCode == 403) {
          return 'Bạn không có quyền thực hiện thao tác này.';
        }
        if (error.response?.statusCode == 409) {
          return 'Thao tác bị xung đột hoặc đã được xử lý.';
        }
        return 'Lỗi máy chủ (${error.response?.statusCode})';
      }
      if (error.type == DioExceptionType.connectionTimeout ||
          error.type == DioExceptionType.receiveTimeout) {
        return 'Kết nối tới máy chủ quá hạn. Vui lòng kiểm tra lại mạng.';
      }
      if (error.type == DioExceptionType.connectionError) {
        return 'Không thể kết nối tới máy chủ (${ApiEndpoints.baseUrl}). Vui lòng kiểm tra địa chỉ IP / Wi-Fi.';
      }
    }
    return error.toString();
  }
}
