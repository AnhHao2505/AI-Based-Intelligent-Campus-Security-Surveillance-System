import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/incident/models/incident_model.dart';
import 'package:guard_mobile_app/features/incident/providers/incident_provider.dart';
import 'package:guard_mobile_app/features/incident/services/incident_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:guard_mobile_app/features/incident/widgets/peer_claim_notice_listener.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _me = {'id': 'guard-me', 'fullName': 'Bảo vệ An', 'email': 'guard.an@fpt.edu.vn', 'role': 'GUARD'};

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUpAll(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(SystemChannels.platform, (call) async => null);
  });

  late IncidentProvider provider;

  setUp(() {
    SharedPreferences.setMockInitialValues({'guard_user_data': jsonEncode(_me)});
    provider = IncidentProvider(incidentService: IncidentService(ApiClient()), stompService: StompService());
  });

  tearDown(() => provider.dispose());

  final incident = IncidentModel(
    id: 'inc-m5',
    cameraCode: 'CAM-ALPHA-01',
    areaName: 'Phòng Giữ Nguyên',
    building: 'Tòa Alpha',
    eventType: 'UNAUTHORIZED_PERSON',
    status: 'NEW',
    severity: 'HIGH',
    dispatchStage: 'BROADCAST',
  );

  test('B3 — tin CLAIMED bởi người khác: peerClaimNotice có giá trị, sự cố rời hàng đợi', () async {
    provider.receiveIncidentForTest(incident);
    expect(provider.alertQueue.map((i) => i.id), contains('inc-m5'));

    provider.receiveUpdateForTest({
      'incidentId': 'inc-m5',
      'status': 'CLAIMED',
      'claimedById': 'guard-b',
      'claimedByName': 'Bảo Vệ Trong Ca (Demo)',
      'dispatchStage': 'BROADCAST',
    });
    await Future<void>.delayed(const Duration(milliseconds: 20));

    expect(provider.peerClaimNotice, 'Sự cố đã được Bảo Vệ Trong Ca (Demo) tiếp nhận.');
    expect(provider.alertQueue.any((i) => i.id == 'inc-m5'), isFalse);
    await Future<void>.delayed(const Duration(milliseconds: 300)); // debounce sort
  });

  testWidgets('B3 — DEF-17: PeerClaimNoticeListener hiện snackbar rồi xoá notice', (tester) async {
    final messengerKey = GlobalKey<ScaffoldMessengerState>();
    await tester.pumpWidget(
      ChangeNotifierProvider<IncidentProvider>.value(
        value: provider,
        child: MaterialApp(
          scaffoldMessengerKey: messengerKey,
          builder: (context, child) => Stack(
            children: [child ?? const SizedBox.shrink(), PeerClaimNoticeListener(messengerKey: messengerKey)],
          ),
          home: const Scaffold(body: Text('Cần xử lý')),
        ),
      ),
    );

    provider.receiveIncidentForTest(incident);
    await tester.pump(const Duration(milliseconds: 300));
    await tester.runAsync(() async {
      provider.receiveUpdateForTest({
        'incidentId': 'inc-m5',
        'status': 'CLAIMED',
        'claimedById': 'guard-b',
        'claimedByName': 'Bảo Vệ Trong Ca (Demo)',
      });
      await Future<void>.delayed(const Duration(milliseconds: 50));
    });
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 500));

    expect(find.text('Sự cố đã được Bảo Vệ Trong Ca (Demo) tiếp nhận.'), findsOneWidget);
    expect(provider.peerClaimNotice, isNull);

    // Đợi snackbar tự đóng (4 s tính từ khi hiện xong)
    await tester.pump(const Duration(seconds: 1));
    await tester.pump(const Duration(seconds: 5));
    await tester.pumpAndSettle();
    expect(find.text('Sự cố đã được Bảo Vệ Trong Ca (Demo) tiếp nhận.'), findsNothing);
  });
}
