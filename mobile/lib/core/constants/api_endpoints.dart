import 'dart:io';
import 'package:flutter/foundation.dart';

class ApiEndpoints {
  // Candidate URLs for automatic discovery (USB adb reverse, LAN Wi-Fi, emulator, web)
  static const List<String> candidateBaseUrls = [
    'http://127.0.0.1:8080',
    'http://localhost:8080',
    'http://192.168.1.6:8080',
    'http://192.168.1.7:8080',
    'http://10.0.2.2:8080',
  ];

  // Default base URL:
  // - Android with adb reverse or local: 127.0.0.1:8080
  // - Web / Desktop: localhost:8080
  static String defaultBaseUrl = kIsWeb
      ? 'http://localhost:8080'
      : (Platform.isAndroid ? 'http://127.0.0.1:8080' : 'http://localhost:8080');

  static String baseUrl = defaultBaseUrl;

  // Auth endpoints
  static String get login => '$baseUrl/api/auth/login';
  static String get me => '$baseUrl/api/auth/me';

  // Guard Shifts & Schedule endpoints
  static String get myShifts => '$baseUrl/api/guard-shifts/my-shifts';
  static String checkIn(String shiftId) => '$baseUrl/api/guard-shifts/$shiftId/check-in';
  static String checkOut(String shiftId) => '$baseUrl/api/guard-shifts/$shiftId/check-out';
  static String availableSubstitutes(String shiftId) => '$baseUrl/api/guard-shifts/$shiftId/available-substitutes';
  static String get shiftRequests => '$baseUrl/api/guard-shift-requests';
  static String get myShiftRequests => '$baseUrl/api/guard-shift-requests/my-requests';

  // Incidents endpoints (for future phase)
  static String activeIncidents(String building) => '$baseUrl/api/incidents/active?building=$building';
  static String claimIncident(String incidentId) => '$baseUrl/api/incidents/$incidentId/claim';
  static String resolveIncident(String incidentId) => '$baseUrl/api/incidents/$incidentId/resolve';
}
