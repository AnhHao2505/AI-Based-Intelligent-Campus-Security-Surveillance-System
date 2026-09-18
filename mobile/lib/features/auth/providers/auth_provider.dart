import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import '../../../core/constants/api_endpoints.dart';
import '../../../core/network/api_client.dart';
import '../../../core/utils/storage_helper.dart';
import '../models/user_model.dart';

class AuthProvider with ChangeNotifier {
  final ApiClient _apiClient;

  UserModel? _user;
  bool _isLoading = false;
  String? _errorMessage;
  bool _isInitialized = false;

  AuthProvider(this._apiClient);

  UserModel? get user => _user;
  bool get isAuthenticated => _user != null;
  bool get isLoading => _isLoading;
  String? get errorMessage => _errorMessage;
  bool get isInitialized => _isInitialized;

  // Restore session on app launch
  Future<void> restoreSession() async {
    _isLoading = true;
    notifyListeners();

    try {
      final token = await StorageHelper.getToken();
      final savedUser = await StorageHelper.getUser();

      if (token != null && token.isNotEmpty && savedUser != null) {
        _user = savedUser;
        // Optionally ping me endpoint to verify token
        try {
          final res = await _apiClient.dio.get(ApiEndpoints.me);
          if (res.data != null) {
            _user = UserModel.fromJson(res.data);
            await StorageHelper.saveUser(_user!);
          }
        } catch (_) {
          // Token might be offline or temporary network issue; keep offline session
        }
      }
    } catch (_) {
      _user = null;
    } finally {
      _isLoading = false;
      _isInitialized = true;
      notifyListeners();
    }
  }

  // Login with credentials (with automatic server URL discovery)
  Future<bool> login(String email, String password) async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    final urlsToTry = <String>{
      ApiEndpoints.baseUrl,
      ...ApiEndpoints.candidateBaseUrls,
    }.toList();

    dynamic lastError;

    for (final candidateUrl in urlsToTry) {
      try {
        debugPrint('>>> [LOGIN] Trying candidate URL: $candidateUrl');
        ApiEndpoints.baseUrl = candidateUrl;
        final tryDio = Dio(
          BaseOptions(
            connectTimeout: const Duration(milliseconds: 2500),
            sendTimeout: const Duration(seconds: 3),
            receiveTimeout: const Duration(seconds: 4),
            headers: {
              'Content-Type': 'application/json',
              'Accept': 'application/json',
            },
          ),
        );

        final response = await tryDio.post(
          '$candidateUrl/api/auth/login',
          data: {
            'email': email.trim(),
            'password': password,
          },
        );

        final data = response.data;
        final accessToken = data['accessToken']?.toString() ?? '';
        final userData = data['user'];

        if (accessToken.isEmpty || userData == null) {
          throw 'Phản hồi đăng nhập không hợp lệ từ máy chủ';
        }

        debugPrint('>>> [LOGIN] Connected successfully to: $candidateUrl');
        ApiEndpoints.baseUrl = candidateUrl;
        _apiClient.dio.options.baseUrl = candidateUrl;
        await StorageHelper.saveBaseUrl(candidateUrl);
        await StorageHelper.saveToken(accessToken);

        final loggedInUser = UserModel.fromJson(userData);
        await StorageHelper.saveUser(loggedInUser);

        _user = loggedInUser;
        _errorMessage = null;
        _isLoading = false;
        notifyListeners();
        return true;
      } catch (e) {
        debugPrint('>>> [LOGIN] Failed for $candidateUrl with error: $e');
        lastError = e;
        if (e is DioException && e.response != null) {
          final code = e.response?.statusCode;
          if (code == 400 || code == 401 || code == 403) {
            break;
          }
        }
      }
    }

    _errorMessage = ApiClient.formatError(lastError);
    _isLoading = false;
    notifyListeners();
    return false;
  }

  // Logout
  Future<void> logout() async {
    _isLoading = true;
    notifyListeners();

    await StorageHelper.clearAuth();
    _user = null;
    _errorMessage = null;
    _isLoading = false;
    notifyListeners();
  }

  void clearError() {
    _errorMessage = null;
    notifyListeners();
  }
}
