import 'dart:convert';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:shared_preferences/shared_preferences.dart';
import '../../features/auth/models/user_model.dart';
import '../constants/api_endpoints.dart';

class StorageHelper {
  static const _tokenKey = 'guard_auth_token';
  static const _userKey = 'guard_user_data';
  static const _customBaseUrlKey = 'custom_base_url';

  static const _secureStorage = FlutterSecureStorage();

  // Save Token
  static Future<void> saveToken(String token) async {
    await _secureStorage.write(key: _tokenKey, value: token);
  }

  // Get Token
  static Future<String?> getToken() async {
    try {
      return await _secureStorage.read(key: _tokenKey);
    } catch (_) {
      return null;
    }
  }

  // Delete Token
  static Future<void> clearAuth() async {
    await _secureStorage.delete(key: _tokenKey);
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_userKey);
  }

  // Save User
  static Future<void> saveUser(UserModel user) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_userKey, jsonEncode(user.toJson()));
  }

  // Get User
  static Future<UserModel?> getUser() async {
    final prefs = await SharedPreferences.getInstance();
    final jsonStr = prefs.getString(_userKey);
    if (jsonStr == null) return null;
    try {
      return UserModel.fromJson(jsonDecode(jsonStr));
    } catch (_) {
      return null;
    }
  }

  // Save Custom Base URL
  static Future<void> saveBaseUrl(String url) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_customBaseUrlKey, url);
    ApiEndpoints.baseUrl = url;
  }

  // Load Custom Base URL
  static Future<void> loadBaseUrl() async {
    final prefs = await SharedPreferences.getInstance();
    final saved = prefs.getString(_customBaseUrlKey);
    if (saved != null && saved.isNotEmpty) {
      ApiEndpoints.baseUrl = saved;
    }
  }
}
