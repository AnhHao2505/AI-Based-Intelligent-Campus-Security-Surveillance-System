import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/chat/providers/guard_chat_provider.dart';
import 'package:guard_mobile_app/features/chat/services/guard_chat_service.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_data.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_repository.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_style.dart';
import 'package:guard_mobile_app/features/map/providers/map_provider.dart';
import 'package:guard_mobile_app/features/map/screens/guard_map_screen.dart';
import 'package:guard_mobile_app/features/map/widgets/osm_attribution_bar.dart';
import 'package:guard_mobile_app/features/chat/widgets/floating_guard_chat_button.dart';
import 'package:guard_mobile_app/features/map/models/area_pin_model.dart';
import 'package:guard_mobile_app/features/map/widgets/area_detail_card.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Kho asset không bao giờ nạp xong: màn bản đồ giữ trạng thái "đang tải", không dựng platform view MapLibre
/// (platform view không chạy được trong widget test) — vẫn phải thấy dòng ghi công.
class _PendingCampusRepository extends CampusMapRepository {
  final _never = Completer<CampusMapData>();

  @override
  Future<CampusMapData> load() => _never.future;
}

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  testWidgets('OsmAttributionBar hiện đúng dòng ghi công OpenStreetMap', (tester) async {
    await tester.pumpWidget(const MaterialApp(home: Scaffold(body: OsmAttributionBar())));
    expect(find.text('© OpenStreetMap contributors'), findsOneWidget);
    expect(osmAttributionText, '© OpenStreetMap contributors');
  });

  testWidgets('GuardMapScreen luôn có dòng ghi công ở đáy, không bị nút nổi che', (tester) async {
    final api = ApiClient();
    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider(create: (_) => MapProvider(api, campusRepository: _PendingCampusRepository())),
          ChangeNotifierProvider(
            create: (_) => GuardChatProvider(chatService: GuardChatService(api), stompService: StompService()),
          ),
        ],
        child: const MaterialApp(home: GuardMapScreen()),
      ),
    );
    await tester.pump();

    final attribution = find.text('© OpenStreetMap contributors');
    expect(attribution, findsOneWidget);

    final screen = tester.getRect(find.byType(GuardMapScreen));
    final bar = tester.getRect(find.byKey(const ValueKey('osm-attribution-bar')));
    expect(bar.bottom, closeTo(screen.bottom, 0.01), reason: 'dải ghi công sát đáy màn');

    // Không nút nổi nào chồng lên dải ghi công
    for (final fab in tester.widgetList<FloatingActionButton>(find.byType(FloatingActionButton))) {
      final r = tester.getRect(find.byWidget(fab));
      expect(r.overlaps(bar), isFalse, reason: 'nút ${fab.tooltip} che dòng ghi công');
    }
  });

  testWidgets('Nút chat ẩn khi thẻ khu mở, hiện lại khi đóng thẻ', (tester) async {
    final api = ApiClient();
    final mapProvider = MapProvider(api, campusRepository: _PendingCampusRepository());
    await tester.pumpWidget(
      MultiProvider(
        providers: [
          ChangeNotifierProvider.value(value: mapProvider),
          ChangeNotifierProvider(
            create: (_) => GuardChatProvider(chatService: GuardChatService(api), stompService: StompService()),
          ),
        ],
        child: const MaterialApp(home: GuardMapScreen()),
      ),
    );
    await tester.pump();
    expect(find.byType(FloatingGuardChatButton), findsOneWidget);

    mapProvider.selectArea(const AreaPinModel(
      id: 'a1',
      name: 'KT Điện',
      areaLevel: 'HIGHLY_CONFIDENTIAL',
      building: 'Tòa Epsilon',
      floor: 'Tầng Trệt',
      centerLatitude: 10.840506,
      centerLongitude: 106.811247,
      isActive: true,
    ));
    await tester.pump();
    expect(find.byType(AreaDetailCard), findsOneWidget);
    expect(find.byType(FloatingGuardChatButton), findsNothing);
    expect(find.text('© OpenStreetMap contributors'), findsOneWidget);

    mapProvider.selectArea(null);
    await tester.pump();
    expect(find.byType(FloatingGuardChatButton), findsOneWidget);
  });
}
