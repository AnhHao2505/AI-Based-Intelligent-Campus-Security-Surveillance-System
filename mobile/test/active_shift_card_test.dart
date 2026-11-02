import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/shift/models/guard_shift_model.dart';
import 'package:guard_mobile_app/features/shift/providers/shift_provider.dart';
import 'package:guard_mobile_app/features/shift/widgets/active_shift_card.dart';
import 'package:intl/date_symbol_data_local.dart';
import 'package:provider/provider.dart';

void main() {
  setUpAll(() async {
    await initializeDateFormatting('vi', null);
  });

  Future<void> pumpCard(WidgetTester tester, Map<String, dynamic> extra) async {
    final shift = GuardShiftModel.fromJson({
      'id': 'shift-1',
      'shiftDate': '2026-10-31',
      'shiftType': 'SHIFT_MORNING',
      'startTime': '06:00',
      'endTime': '14:00',
      'status': 'SCHEDULED',
      ...extra,
    });
    await tester.pumpWidget(
      ChangeNotifierProvider(
        create: (_) => ShiftProvider(ApiClient()),
        child: MaterialApp(
          home: Scaffold(
            body: SingleChildScrollView(
              child: ActiveShiftCard(shift: shift, selectedDate: DateTime(2026, 10, 31)),
            ),
          ),
        ),
      ),
    );
  }

  testWidgets('Có dutyPostLabel: hiện "Vị trí trực: {nhãn}"; khu vực ở dòng riêng, không ghép toà; dòng Địa điểm giữ nguyên',
      (tester) async {
    await pumpCard(tester, {'areaName': 'Sảnh A', 'building': 'Tòa Alpha', 'dutyPostLabel': 'Cổng chính'});

    expect(find.text('Vị trí trực: Cổng chính'), findsOneWidget);
    expect(find.text('Khu vực: Sảnh A'), findsOneWidget);
    expect(find.text('Địa điểm: Tòa Alpha'), findsOneWidget);
    expect(find.textContaining('Sảnh A · Tòa Alpha'), findsNothing);
  });

  testWidgets('Không có dutyPostLabel (backend chưa có): không hiện dòng "Vị trí trực"', (tester) async {
    await pumpCard(tester, {'areaName': 'Sảnh A', 'building': 'Tòa Alpha'});

    expect(find.textContaining('Vị trí trực'), findsNothing);
    expect(find.text('Khu vực: Sảnh A'), findsOneWidget);
    expect(find.text('Địa điểm: Tòa Alpha'), findsOneWidget);
  });

  testWidgets('Ca luân chuyển: hiện "Luân chuyển: {buildingName}"; dòng Địa điểm giữ nguyên theo field building',
      (tester) async {
    await pumpCard(tester, {
      'building': 'Tòa Alpha',
      'buildingName': 'Tòa Beta',
      'crossBuilding': true,
      'dutyPostLabel': 'Tuần tra',
    });

    expect(find.text('Luân chuyển: Tòa Beta'), findsOneWidget);
    expect(find.text('Địa điểm: Tòa Alpha'), findsOneWidget);
    expect(find.text('Vị trí trực: Tuần tra'), findsOneWidget);
  });

  testWidgets('Ca không luân chuyển (hoặc backend chưa có crossBuilding): không hiện nhãn "Luân chuyển"', (tester) async {
    await pumpCard(tester, {'building': 'Tòa Alpha', 'buildingName': 'Tòa Alpha', 'crossBuilding': false});
    expect(find.textContaining('Luân chuyển'), findsNothing);

    await pumpCard(tester, {'building': 'Tòa Alpha'});
    expect(find.textContaining('Luân chuyển'), findsNothing);
  });

  testWidgets('Không có khu vực: không hiện dòng "Khu vực"', (tester) async {
    await pumpCard(tester, {'building': 'Tòa Alpha', 'dutyPostLabel': 'Trực cổng'});
    expect(find.textContaining('Khu vực'), findsNothing);
    expect(find.text('Vị trí trực: Trực cổng'), findsOneWidget);
  });
}
