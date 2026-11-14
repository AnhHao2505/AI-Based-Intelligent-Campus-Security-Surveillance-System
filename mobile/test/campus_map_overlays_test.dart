import 'dart:convert';
import 'dart:io';
import 'dart:ui' as ui;

import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_data.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_overlays.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_sprites.dart';
import 'package:guard_mobile_app/features/map/models/area_pin_model.dart';

CampusMapData _campusOnDisk() {
  final manifestRaw = File('assets/campus/manifest.json').readAsStringSync();
  final manifest = jsonDecode(manifestRaw) as Map<String, dynamic>;
  final files = <String, String>{'manifest.json': manifestRaw};
  for (final b in manifest['buildings'] as List) {
    for (final f in (b as Map)['floors'] as List) {
      final rel = (f as Map)['file'] as String;
      files[rel] = File('assets/campus/$rel').readAsStringSync();
    }
  }
  return parseCampusAssets(files);
}

/// Các điểm `layer = portal` đọc thẳng từ file asset (không qua code đang test).
List<Map<String, dynamic>> _portalsInFile(String rel) {
  final fc = jsonDecode(File('assets/campus/$rel').readAsStringSync()) as Map<String, dynamic>;
  return [
    for (final f in fc['features'] as List)
      if ((f as Map)['properties']['layer'] == 'portal') Map<String, dynamic>.from(f),
  ];
}

List<Map<String, dynamic>> _features(Map<String, dynamic> fc) => (fc['features'] as List).cast<Map<String, dynamic>>();

/// Phòng (layer = room) đầu tiên của toà / tầng: vòng ngoài + lỗ.
({String id, List<List<double>> outer, List<List<List<double>>> holes}) _room(
    CampusMapData campus, String building, int floor,
    [int index = 0]) {
  final rooms = campus.floors[CampusMapData.floorKey(building, floor)]!
      .features
      .where((f) => f['properties']['layer'] == 'room')
      .toList();
  final f = rooms[index];
  final rings = [
    for (final r in f['geometry']['coordinates'] as List)
      [for (final p in r as List) [((p as List)[0] as num).toDouble(), (p[1] as num).toDouble()]],
  ];
  return (id: f['properties']['id'] as String, outer: rings.first, holes: rings.skip(1).toList());
}

AreaPinModel _pinAt(List<double> lonLat,
        {String id = 'area-1',
        String name = 'Phòng Lab Bảo mật',
        String level = 'CONFIDENTIAL',
        String? building = 'Tòa Alpha',
        String? floor = 'Tầng Trệt'}) =>
    AreaPinModel(
      id: id,
      name: name,
      areaLevel: level,
      building: building,
      floor: floor,
      centerLatitude: lonLat[1],
      centerLongitude: lonLat[0],
      isActive: true,
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final campus = _campusOnDisk();

  group('portalSymbolsGeoJson', () {
    test('tầng 0 Tòa Alpha: đúng số điểm portal trong layers/toa-alpha__f0.geojson', () {
      final expected = _portalsInFile('layers/toa-alpha__f0.geojson');
      expect(expected, isNotEmpty, reason: 'asset phải có điểm portal để test có ý nghĩa');

      final fc = portalSymbolsGeoJson(campus, building: 'Tòa Alpha', floorOrder: 0);
      final got = _features(fc);

      expect(got.length, expected.length);
      expect(got.map((f) => f['properties']['id']).toSet(), expected.map((f) => f['properties']['id']).toSet());
      expect(got.every((f) => f['geometry']['type'] == 'Point'), isTrue);
    });

    test('tên toà theo alias vẫn lọc đúng; tầng khác chỉ lấy điểm của tầng đó', () {
      final f1 = _portalsInFile('layers/toa-alpha__f1.geojson');
      final got = _features(portalSymbolsGeoJson(campus, building: 'Tòa HCM', floorOrder: 1));
      expect(got.length, f1.length);
      expect(got.every((f) => f['properties']['floorOrder'] == 1), isTrue);
    });

    test('"Tất cả" toà: chỉ tầng 0 của mọi toà, kể cả khi đang xem tầng khác (quy tắc web không giãn tầng)', () {
      final expected = _portalsInFile('layers/toa-alpha__f0.geojson').length +
          _portalsInFile('layers/toa-epsilon__f0.geojson').length;
      for (final floor in [0, 2]) {
        final got = _features(portalSymbolsGeoJson(campus, floorOrder: floor));
        expect(got.length, expected, reason: 'tầng đang xem $floor');
        expect(got.every((f) => f['properties']['floorOrder'] == 0), isTrue);
      }
    });

    test('STAIRS → portal-stairs, ELEVATOR → portal-elevator', () {
      expect(portalIconImageId('STAIRS'), 'portal-stairs');
      expect(portalIconImageId('ELEVATOR'), 'portal-elevator');

      // Mọi điểm trong asset: ảnh khớp loại nút (Epsilon có thang máy)
      var elevators = 0;
      for (final b in campus.buildings) {
        for (final floor in b.floorOrders) {
          for (final f in _features(portalSymbolsGeoJson(campus, building: b.name, floorOrder: floor))) {
            final p = f['properties'] as Map;
            expect(p['iconImage'], p['nodeType'] == 'ELEVATOR' ? 'portal-elevator' : 'portal-stairs');
            if (p['nodeType'] == 'ELEVATOR') elevators++;
          }
        }
      }
      expect(elevators, greaterThan(0), reason: 'asset phải có ít nhất một thang máy');
    });

    test('chưa có dữ liệu bản đồ → rỗng', () {
      expect(_features(portalSymbolsGeoJson(null, building: 'Tòa Alpha', floorOrder: 0)), isEmpty);
    });
  });

  group('roomLabelsGeoJson', () {
    final room = _room(campus, 'Tòa Alpha', 0);
    final inside = polygonInteriorPoint(room.outer, room.holes);

    test('phòng có khu → đúng 1 điểm nhãn mang tên khu; phòng không có khu → không có nhãn', () {
      final pin = _pinAt(inside);
      final got = _features(roomLabelsGeoJson(campus, [pin], building: 'Tòa Alpha', floorOrder: 0));

      expect(got.length, 1, reason: 'chỉ phòng chứa tâm khu có nhãn, các phòng khác không');
      final p = got.single['properties'] as Map;
      expect(p['id'], room.id);
      expect(p['name'], 'Phòng Lab Bảo mật');
      expect(p['areaId'], 'area-1');
      expect(p['areaLevelCode'], 'CONFIDENTIAL');
      expect(p['imageId'], roomLabelImageId(name: 'Phòng Lab Bảo mật', levelCode: 'CONFIDENTIAL', selected: false));
      expect(got.single['geometry'], {'type': 'Point', 'coordinates': inside});
    });

    test('không có khu nào → không có nhãn', () {
      expect(_features(roomLabelsGeoJson(campus, const [], building: 'Tòa Alpha', floorOrder: 0)), isEmpty);
    });

    test('chế độ "Tất cả" toà → không có nhãn', () {
      final pin = _pinAt(inside);
      expect(_features(roomLabelsGeoJson(campus, [pin], floorOrder: 0)), isEmpty);
    });

    test('khu ở tầng khác / toà khác → không có nhãn', () {
      expect(
        _features(roomLabelsGeoJson(campus, [_pinAt(inside, floor: 'Tầng 2')], building: 'Tòa Alpha', floorOrder: 0)),
        isEmpty,
      );
      expect(
        _features(
            roomLabelsGeoJson(campus, [_pinAt(inside, building: 'Tòa Epsilon')], building: 'Tòa Alpha', floorOrder: 0)),
        isEmpty,
      );
    });

    test('khu không ghi toà vẫn khớp phòng (như web); khu không có tầng tính là tầng trệt', () {
      final got = _features(roomLabelsGeoJson(campus, [_pinAt(inside, building: null, floor: null)],
          building: 'Tòa Alpha', floorOrder: 0));
      expect(got.length, 1);
    });

    test('khu đang chọn: ảnh riêng, ưu tiên đặt (sortKey 0); khu khác sortKey 10', () {
      final room2 = _room(campus, 'Tòa Alpha', 0, 1);
      final pins = [
        _pinAt(inside, id: 'a'),
        _pinAt(polygonInteriorPoint(room2.outer, room2.holes), id: 'b', name: 'Kho', level: 'PUBLIC'),
      ];
      final got = _features(
          roomLabelsGeoJson(campus, pins, building: 'Tòa Alpha', floorOrder: 0, selectedAreaId: 'a'));
      final byArea = {for (final f in got) f['properties']['areaId']: f['properties'] as Map};

      expect(byArea.keys.toSet(), {'a', 'b'});
      expect(byArea['a']!['isSelected'], isTrue);
      expect(byArea['a']!['sortKey'], 0);
      expect(byArea['b']!['sortKey'], 10);
      expect(byArea['a']!['imageId'], isNot(roomLabelImageId(name: 'Phòng Lab Bảo mật', levelCode: 'CONFIDENTIAL', selected: false)));
      expect(roomLabelImageIds({'features': got}), {byArea['a']!['imageId'], byArea['b']!['imageId']});
    });

    test('id ảnh nhãn: một id cho mỗi (tên, cấp, đang chọn) và đọc ngược lại được', () {
      final id = roomLabelImageId(name: 'Phòng Họp: Tầng 1', levelCode: 'highly_confidential', selected: true);
      expect(id, startsWith('room-label::1:HIGHLY_CONFIDENTIAL:'));
      final parsed = parseRoomLabelImageId(id)!;
      expect(parsed.name, 'Phòng Họp: Tầng 1');
      expect(parsed.levelCode, 'HIGHLY_CONFIDENTIAL');
      expect(parsed.selected, isTrue);
      expect(parseRoomLabelImageId('portal-stairs'), isNull);
    });
  });

  group('polygonInteriorPoint', () {
    test('mọi phòng trong asset: điểm đặt nhãn nằm trong phòng', () {
      for (final b in campus.buildings) {
        for (final floor in b.floorOrders) {
          final rooms = campus.floors[CampusMapData.floorKey(b.name, floor)]!
              .features
              .where((f) => f['properties']['layer'] == 'room')
              .length;
          for (var i = 0; i < rooms; i++) {
            final r = _room(campus, b.name, floor, i);
            final pt = polygonInteriorPoint(r.outer, r.holes);
            expect(pointInPolygonWithHoles(pt, r.outer, r.holes), isTrue, reason: '${r.id} tầng $floor');
          }
        }
      }
    });

    test('hình chữ U: tâm nằm ngoài → lấy dây cung ngang dài nhất', () {
      final u = [
        [0.0, 0.0], [3.0, 0.0], [3.0, 3.0], [2.0, 3.0], [2.0, 1.0], [1.0, 1.0], [1.0, 3.0], [0.0, 3.0], [0.0, 0.0],
      ];
      final pt = polygonInteriorPoint(u);
      expect(pointInPolygonWithHoles(pt, u, const []), isTrue);
      expect(pt[1], lessThan(1.0), reason: 'dây cung dài nhất nằm ở đáy chữ U');
    });
  });

  group('ảnh vẽ bằng Canvas', () {
    Future<ui.Image> decode(CampusSprite s) async {
      final codec = await ui.instantiateImageCodec(s.png);
      return (await codec.getNextFrame()).image;
    }

    test('cầu thang / thang máy: PNG khác rỗng, 64×64 (@2x của 32 px), hai ảnh khác nhau', () async {
      final stairs = (await renderPortalIcon('portal-stairs'))!;
      final elevator = (await renderPortalIcon('portal-elevator'))!;
      for (final s in [stairs, elevator]) {
        expect(s.png, isNotEmpty);
        expect((s.width, s.height), (64, 64));
        final img = await decode(s);
        expect((img.width, img.height), (64, 64));
        final bytes = (await img.toByteData(format: ui.ImageByteFormat.rawRgba))!.buffer.asUint8List();
        expect(bytes.any((b) => b != 0), isTrue, reason: 'ảnh không được trong suốt hoàn toàn');
      }
      expect(stairs.png, isNot(equals(elevator.png)));
      expect(await renderPortalIcon('khong-co'), isNull);
    });

    test('nhãn phòng: cao 56 px (@2x của 22 + 2×3), rộng chẵn ≥ 84; đang chọn khác ảnh thường', () async {
      final normal = (await renderRoomLabel(name: 'Phòng Lab Bảo mật', levelCode: 'CONFIDENTIAL', selected: false))!;
      final selected = (await renderRoomLabel(name: 'Phòng Lab Bảo mật', levelCode: 'CONFIDENTIAL', selected: true))!;
      for (final s in [normal, selected]) {
        expect(s.png, isNotEmpty);
        expect(s.height, (22 + 3 * 2) * 2);
        expect(s.width, greaterThanOrEqualTo((36 + 3 * 2) * 2));
        expect(s.width.isEven, isTrue);
        final img = await decode(s);
        expect((img.width, img.height), (s.width, s.height));
      }
      expect(normal.png, isNot(equals(selected.png)));
      expect(await renderRoomLabel(name: '   ', levelCode: 'PUBLIC', selected: false), isNull);

      final fromId = (await renderRoomLabelForImageId(
          roomLabelImageId(name: 'Phòng Lab Bảo mật', levelCode: 'CONFIDENTIAL', selected: false)))!;
      expect((fromId.width, fromId.height), (normal.width, normal.height));
    });

    test('tên dài cắt còn 24 ký tự kèm "…"', () {
      expect(truncateRoomLabel('Phòng thí nghiệm an toàn thông tin số 1'), hasLength(24));
      expect(truncateRoomLabel('Phòng thí nghiệm an toàn thông tin số 1'), endsWith('…'));
      expect(truncateRoomLabel('Kho'), 'Kho');
    });
  });

  group('nhãn nằm trên ghim khu', () {
    /// Khoảng (dp) từ điểm neo tới cạnh dưới viên nhãn khi icon-size = [size] và offset = [offsetY]:
    /// MapLibre nhân offset với icon-size; dưới viên nhãn còn lề bóng trong suốt (cũng nhân icon-size).
    double pillBottomLift(double size, double offsetY) => (-offsetY + roomLabelShadowPad) * size;

    test('neo cạnh dưới, khoảng dịch = bán kính (13) + viền (3) + hở (4) = 20 dp', () {
      expect(roomLabelIconAnchor, 'bottom');
      expect(areaPinMaxRadius, 13);
      expect(areaPinMaxStrokeWidth, 3);
      expect(roomLabelPinGap, 4);
      expect(roomLabelLiftAbovePin, 20);
      // Mép nhìn thấy của mọi ghim (bán kính + viền) cách cạnh dưới nhãn ít nhất 4 dp
      for (final (radius, stroke) in [(9.0, 2.0), (areaPinMaxRadius, areaPinMaxStrokeWidth)]) {
        expect(roomLabelLiftAbovePin - (radius + stroke), greaterThanOrEqualTo(roomLabelPinGap));
      }
    });

    test('tại mỗi mốc icon-size: cạnh dưới viên nhãn cách điểm neo đúng 20 dp, chỉ dịch lên', () {
      for (final (zoom, size) in roomLabelIconSizeStops) {
        final offset = roomLabelIconOffset(size);
        expect(offset[0], 0, reason: 'zoom $zoom: không dịch ngang');
        expect(offset[1], lessThan(0), reason: 'zoom $zoom: số âm = dịch lên');
        expect(pillBottomLift(size, offset[1]), closeTo(roomLabelLiftAbovePin, 1e-9), reason: 'zoom $zoom');
      }
      // Ví dụ số: icon-size 1 → offset −17 (17 + lề 3 = 20)
      expect(roomLabelIconOffset(1), [0, -17]);
    });

    test('biểu thức icon-size / icon-offset: cùng mốc zoom; giữa hai mốc sai lệch dưới 1 %', () {
      final sizeExpr = roomLabelIconSizeExpression();
      final offsetExpr = roomLabelIconOffsetExpression();
      expect(sizeExpr.take(3), ['interpolate', ['linear'], ['zoom']]);
      expect(offsetExpr.take(3), ['interpolate', ['linear'], ['zoom']]);
      expect(sizeExpr.length, 3 + 2 * roomLabelIconSizeStops.length);
      expect(offsetExpr.length, sizeExpr.length);
      for (var i = 0; i < roomLabelIconSizeStops.length; i++) {
        final (zoom, size) = roomLabelIconSizeStops[i];
        expect(sizeExpr[3 + 2 * i], zoom);
        expect(sizeExpr[4 + 2 * i], size);
        expect(offsetExpr[3 + 2 * i], zoom);
        expect(offsetExpr[4 + 2 * i], ['literal', roomLabelIconOffset(size)]);
      }

      // MapLibre nội suy tuyến tính cả icon-size lẫn icon-offset theo zoom
      for (var i = 0; i + 1 < roomLabelIconSizeStops.length; i++) {
        final (z0, s0) = roomLabelIconSizeStops[i];
        final (z1, s1) = roomLabelIconSizeStops[i + 1];
        final o0 = roomLabelIconOffset(s0)[1], o1 = roomLabelIconOffset(s1)[1];
        for (var t = 0.0; t <= 1.0; t += 0.1) {
          final lift = pillBottomLift(s0 + (s1 - s0) * t, o0 + (o1 - o0) * t);
          expect(lift, greaterThanOrEqualTo(roomLabelLiftAbovePin - 1e-9),
              reason: 'zoom ${z0 + (z1 - z0) * t}: không thấp hơn khoảng dịch');
          expect(lift, lessThan(roomLabelLiftAbovePin * 1.01), reason: 'zoom ${z0 + (z1 - z0) * t}');
        }
      }
    });

    test('lề bóng của offset khớp ảnh nhãn: cao (22 + 2 × lề) × pixelRatio', () async {
      final s = (await renderRoomLabel(name: 'Kho', levelCode: 'PUBLIC', selected: false))!;
      expect(s.height, ((22 + 2 * roomLabelShadowPad) * campusSpritePixelRatio).round());
    });
  });
}
