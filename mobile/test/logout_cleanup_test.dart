import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/core/session/logout_cleanup.dart';
import 'package:guard_mobile_app/features/auth/models/user_model.dart';
import 'package:guard_mobile_app/features/auth/providers/auth_provider.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/providers/incident_provider.dart';
import 'package:guard_mobile_app/features/incident/services/incident_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:guard_mobile_app/features/shift/models/guard_shift_model.dart';
import 'package:guard_mobile_app/features/shift/providers/shift_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _me = {'id': 'guard-me', 'fullName': 'Bảo vệ An', 'email': 'guard.an@fpt.edu.vn', 'role': 'GUARD'};

/// Ghi lại thứ tự các bước dọn.
final List<String> steps = [];

class RecordingStompService extends StompService {
  int disconnectCalls = 0;

  @override
  void disconnect() {
    disconnectCalls++;
    steps.add('disconnectStomp');
    super.disconnect();
  }
}

Map<String, dynamic> offerMessage(String incidentId) => {
      'type': 'INCIDENT_OFFER',
      'offerId': 'off-1',
      'incidentId': incidentId,
      'expiresAt': DateTime.now().add(const Duration(seconds: 30)).toUtc().toIso8601String(),
      'incident': {
        'id': incidentId,
        'cameraCode': 'CAM-001',
        'building': 'Tòa Alpha',
        'eventType': 'UNAUTHORIZED_PERSON',
        'status': 'NEW',
        'severity': 'HIGH',
        'dispatchStage': 'STEP1',
      },
    };

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async => null);
  });

  test('B2 — DEF-16: logout xoá ca, hàng đợi, lời mời; dừng service; đóng STOMP; theo đúng thứ tự', () async {
    steps.clear();
    SharedPreferences.setMockInitialValues({'guard_user_data': jsonEncode(_me)});
    FlutterSecureStorage.setMockInitialValues({'guard_auth_token': 'token'});

    final shiftProvider = ShiftProvider(ApiClient());
    shiftProvider.setShiftsForTest([
      GuardShiftModel(
        id: 'shift-1',
        shiftDate: '2026-11-06',
        shiftType: 'SHIFT_NIGHT',
        startTime: '23:10:00',
        endTime: '06:00:00',
        status: 'CHECKED_IN',
        buildingName: 'Tòa Alpha',
      ),
    ]);
    shiftProvider.addListener(() {
      if (shiftProvider.shifts.isEmpty && !steps.contains('clearShifts')) steps.add('clearShifts');
    });

    final stomp = RecordingStompService();
    final incidentProvider = IncidentProvider(incidentService: IncidentService(ApiClient()), stompService: stomp);
    incidentProvider.receiveIncidentForTest(
        IncidentModel(id: 'inc-1', cameraCode: 'CAM-001', eventType: 'UNAUTHORIZED_PERSON', status: 'NEW'));
    incidentProvider.handleOfferMessage(offerMessage('inc-2'));
    incidentProvider.addListener(() {
      if (incidentProvider.alertQueue.isEmpty &&
          incidentProvider.currentOffer == null &&
          !steps.contains('resetIncidents')) {
        steps.add('resetIncidents');
      }
    });
    expect(incidentProvider.alertQueue, isNotEmpty);
    expect(incidentProvider.currentOffer, isNotNull);

    var stopCalls = 0;
    final auth = AuthProvider(
      ApiClient(),
      onLogout: () => runLogoutCleanup(
        shiftProvider: shiftProvider,
        incidentProvider: incidentProvider,
        stopDutyService: () async {
          stopCalls++;
          steps.add('stopDutyService');
        },
      ),
    );
    var authenticatedWhenCleanupDone = false;
    auth.addListener(() {
      if (steps.contains('disconnectStomp') && !auth.isAuthenticated) authenticatedWhenCleanupDone = true;
    });
    await auth.restoreSession();
    expect(auth.isAuthenticated, isTrue);

    await auth.logout();

    expect(shiftProvider.shifts, isEmpty);
    expect(incidentProvider.alertQueue, isEmpty);
    expect(incidentProvider.currentOffer, isNull);
    expect(incidentProvider.activeIncident, isNull);
    expect(stopCalls, 1);
    expect(stomp.disconnectCalls, 1);
    expect(steps, ['clearShifts', 'resetIncidents', 'stopDutyService', 'disconnectStomp']);
    // Màn đăng nhập (isAuthenticated = false) chỉ hiện SAU khi dọn xong
    expect(auth.isAuthenticated, isFalse);
    expect(authenticatedWhenCleanupDone, isTrue);
    expect(await SharedPreferences.getInstance().then((p) => p.getString('guard_user_data')), isNull);

    incidentProvider.dispose();
  });

  test('B2 — một bước dọn lỗi vẫn chạy các bước sau và vẫn đăng xuất', () async {
    SharedPreferences.setMockInitialValues({'guard_user_data': jsonEncode(UserModel.fromJson(_me).toJson())});
    FlutterSecureStorage.setMockInitialValues({'guard_auth_token': 'token'});
    final shiftProvider = ShiftProvider(ApiClient());
    final stomp = RecordingStompService();
    final incidentProvider = IncidentProvider(incidentService: IncidentService(ApiClient()), stompService: stomp);
    final auth = AuthProvider(
      ApiClient(),
      onLogout: () => runLogoutCleanup(
        shiftProvider: shiftProvider,
        incidentProvider: incidentProvider,
        stopDutyService: () async => throw StateError('service lỗi'),
      ),
    );
    await auth.restoreSession();
    await auth.logout();

    expect(stomp.disconnectCalls, 1);
    expect(auth.isAuthenticated, isFalse);
    incidentProvider.dispose();
  });
}
