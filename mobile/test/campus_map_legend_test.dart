import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/constants/app_colors.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/chat/providers/guard_chat_provider.dart';
import 'package:guard_mobile_app/features/chat/services/guard_chat_service.dart';
import 'package:guard_mobile_app/features/chat/widgets/floating_guard_chat_button.dart';
import 'package:guard_mobile_app/features/incident/services/stomp_service.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_data.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_repository.dart';
import 'package:guard_mobile_app/features/map/models/area_pin_model.dart';
import 'package:guard_mobile_app/features/map/providers/map_provider.dart';
import 'package:guard_mobile_app/features/map/screens/guard_map_screen.dart';
import 'package:guard_mobile_app/features/map/widgets/campus_map_legend.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Kho asset không bao giờ nạp xong: màn bản đồ giữ trạng thái "đang tải", không dựng platform view MapLibre.
class _PendingCampusRepository extends CampusMapRepository {
  final _never = Completer<CampusMapData>();

  @override
  Future<CampusMapData> load() => _never.future;
}

AreaPinModel _area(String level) => AreaPinModel(
      id: 'a-$level',
      name: 'Khu $level',
      areaLevel: level,
      centerLatitude: 10.840506,
      centerLongitude: 106.811247,
      isActive: true,
    );

Color _dotColor(WidgetTester tester, String level) {
  final dot = tester.widget<Container>(find.byKey(ValueKey('campus-map-legend-dot-$level')));
  return (dot.decoration! as BoxDecoration).color!;
}

BoxDecoration _legendBox(WidgetTester tester) =>
    tester.widget<Container>(find.byKey(const ValueKey('campus-map-legend'))).decoration! as BoxDecoration;

Widget _legendApp({required Brightness brightness}) => MaterialApp(
      theme: ThemeData(brightness: brightness),
      home: const Scaffold(body: Center(child: CampusMapLegend())),
    );

void main() {
  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('CampusMapLegend', () {
    testWidgets('đủ 3 nhãn như badgeLabel web, đúng thứ tự, không có "Phòng trống"', (tester) async {
      await tester.pumpWidget(_legendApp(brightness: Brightness.light));

      expect(campusMapLegendEntries.map((e) => e.label), ['Công khai', 'Bảo mật', 'Bảo mật cao']);
      final xs = [
        for (final label in ['Công khai', 'Bảo mật', 'Bảo mật cao'])
          tester.getTopLeft(find.text(label)).dx,
      ];
      expect(xs, orderedEquals([...xs]..sort()), reason: 'các mục xếp ngang theo thứ tự cấp 1 → 3');
      expect(find.text('Phòng trống'), findsNothing);
    });

    testWidgets('màu chấm khớp AreaPinModel.levelColor của từng cấp', (tester) async {
      await tester.pumpWidget(_legendApp(brightness: Brightness.light));

      for (final level in ['PUBLIC', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL']) {
        expect(_dotColor(tester, level), _area(level).levelColor, reason: level);
      }
      expect(_dotColor(tester, 'PUBLIC'), const Color(0xFF10B981));
      expect(_dotColor(tester, 'CONFIDENTIAL'), const Color(0xFFF59E0B));
      expect(_dotColor(tester, 'HIGHLY_CONFIDENTIAL'), const Color(0xFFEF4444));

      final dotSize = tester.getSize(find.byKey(const ValueKey('campus-map-legend-dot-PUBLIC')));
      expect(dotSize, const Size(campusMapLegendDotSize, campusMapLegendDotSize));
    });

    testWidgets('theme sáng / tối: nền trắng 92 % hoặc slate-900 92 % như web, chữ màu phụ của theme', (tester) async {
      await tester.pumpWidget(_legendApp(brightness: Brightness.light));
      expect(_legendBox(tester).color, Colors.white.withAlpha(235));
      expect(_legendBox(tester).borderRadius, BorderRadius.circular(campusMapLegendRadius));
      expect(tester.widget<Text>(find.text('Bảo mật')).style!.color, AppColors.lightTextSecondary);
      expect(tester.widget<Text>(find.text('Bảo mật')).style!.fontSize, campusMapLegendFontSize);

      await tester.pumpWidget(_legendApp(brightness: Brightness.dark));
      await tester.pumpAndSettle(); // MaterialApp chuyển theme có hiệu ứng
      expect(_legendBox(tester).color, const Color.fromARGB(235, 15, 23, 42));
      expect(tester.widget<Text>(find.text('Bảo mật')).style!.color, AppColors.darkTextSecondary);
      // Màu chấm không đổi theo theme
      expect(_dotColor(tester, 'CONFIDENTIAL'), _area('CONFIDENTIAL').levelColor);
    });
  });

  group('GuardMapScreen — chú giải', () {
    Future<MapProvider> pumpScreen(WidgetTester tester) async {
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
      return mapProvider;
    }

    testWidgets('ẩn mặc định để mặt bản đồ thoáng, bấm nút (i) góc trên để bật/tắt chú giải', (tester) async {
      await pumpScreen(tester);

      // Mặc định ẩn khỏi bản đồ
      expect(find.byType(CampusMapLegend), findsNothing);
      final toggle = find.byKey(const ValueKey('map-legend-toggle'));
      expect(toggle, findsOneWidget);

      // Bấm nút (i) → hiện chú giải ở phía trên, không chồng nút chat, FAB hay dòng ghi công OSM
      await tester.tap(toggle);
      await tester.pump();
      expect(find.byType(CampusMapLegend), findsOneWidget);

      final legend = tester.getRect(find.byType(CampusMapLegend));
      final chat = tester.getRect(find.byType(FloatingGuardChatButton));
      final osm = tester.getRect(find.byKey(const ValueKey('osm-attribution-bar')));
      expect(legend.overlaps(chat), isFalse);
      expect(legend.overlaps(osm), isFalse, reason: 'không che dòng ghi công');
      for (final fab in tester.widgetList<FloatingActionButton>(find.byType(FloatingActionButton))) {
        final r = tester.getRect(find.byWidget(fab));
        expect(legend.overlaps(r), isFalse, reason: 'chú giải chồng nút ${fab.tooltip}');
      }

      // Bấm lại nút (i) → đóng chú giải
      await tester.tap(toggle);
      await tester.pump();
      expect(find.byType(CampusMapLegend), findsNothing);
    });
  });
}
