import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/features/map/models/area_pin_model.dart';
import 'package:guard_mobile_app/features/map/widgets/area_detail_card.dart';

AreaPinModel _pin({String? building, String? floor}) => AreaPinModel(
      id: 'a1',
      name: 'Phòng học 01-01',
      areaLevel: 'PUBLIC',
      building: building,
      floor: floor,
      centerLatitude: 10.840623,
      centerLongitude: 106.811645,
      isActive: true,
    );

Future<void> _pumpCard(WidgetTester tester, AreaPinModel pin) {
  return tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: AreaDetailCard(
        area: pin,
        isLoadingRoute: false,
        onNavigateTap: () {},
        onCancelRoute: () {},
        onClose: () {},
      ),
    ),
  ));
}

void main() {
  testWidgets('Thẻ khu: tên toà / tầng từ API đã có tiền tố → không ghép thêm "Tòa" / "Tầng"', (tester) async {
    await _pumpCard(tester, _pin(building: 'Tòa Alpha', floor: 'Tầng Trệt'));
    expect(find.text('Tòa Alpha • Tầng Trệt'), findsOneWidget);
    expect(find.textContaining('Tòa Tòa'), findsNothing);
    expect(find.textContaining('Tầng Tầng'), findsNothing);
  });

  testWidgets('Thẻ khu: tên chưa có tiền tố → thêm "Tòa" / "Tầng"', (tester) async {
    await _pumpCard(tester, _pin(building: 'Epsilon', floor: '2'));
    expect(find.text('Tòa Epsilon • Tầng 2'), findsOneWidget);
  });

  testWidgets('Thẻ khu: khu ngoài trời (không toà / tầng)', (tester) async {
    await _pumpCard(tester, _pin());
    expect(find.text('Khuôn viên ngoài trời'), findsOneWidget);
  });

  test('withPrefix: không phân biệt hoa thường, cách viết "Toà"', () {
    expect(AreaPinModel.withPrefix('toà Epsilon', 'Tòa', const ['tòa', 'toà', 'toa']), 'toà Epsilon');
    expect(AreaPinModel.withPrefix('TẦNG 3', 'Tầng', const ['tầng', 'tang']), 'TẦNG 3');
    expect(AreaPinModel.withPrefix('Toastmaster', 'Tòa', const ['tòa', 'toà', 'toa']), 'Tòa Toastmaster');
  });
}
