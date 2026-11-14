import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_data.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_overlays.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_repository.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_sprites.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_style.dart';
import 'package:guard_mobile_app/features/map/models/area_pin_model.dart';
import 'package:guard_mobile_app/features/map/providers/map_provider.dart';

// Ẩn ghim của khu đã có nhãn khi phóng to, và bấm nhãn mở đúng khu (P-MAP-4).

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

class _DiskCampusRepository extends CampusMapRepository {
  final CampusMapData data;

  _DiskCampusRepository(this.data);

  @override
  Future<CampusMapData> load() async => data;
}

/// Điểm đặt nhãn của phòng thứ [index] (layer = room) ở toà / tầng cho trước, và id phòng.
({String roomId, List<double> point}) _roomPoint(CampusMapData campus, String building, int floor, int index) {
  final room = campus.floors[CampusMapData.floorKey(building, floor)]!
      .features
      .where((f) => f['properties']['layer'] == 'room')
      .elementAt(index);
  final rings = [
    for (final r in room['geometry']['coordinates'] as List)
      [for (final p in r as List) [((p as List)[0] as num).toDouble(), (p[1] as num).toDouble()]],
  ];
  return (roomId: room['properties']['id'] as String, point: polygonInteriorPoint(rings.first, rings.skip(1).toList()));
}

AreaPinModel _pin(String id, String name, List<double> lonLat, {String? building = 'Tòa Alpha'}) => AreaPinModel(
      id: id,
      name: name,
      areaLevel: 'PUBLIC',
      building: building,
      floor: 'Tầng Trệt',
      centerLatitude: lonLat[1],
      centerLongitude: lonLat[0],
      isActive: true,
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final campus = _campusOnDisk();
  final room0 = _roomPoint(campus, 'Tòa Alpha', 0, 0);
  final room1 = _roomPoint(campus, 'Tòa Alpha', 0, 1);
  // Ngoài khung bao khuôn viên → không nằm trong phòng nào (như khu ngoài trời / khu không khớp phòng)
  final outside = [campus.bounds.west - 0.001, campus.bounds.south - 0.001];
  final pins = [
    _pin('in-0', 'Phòng A', room0.point),
    _pin('in-1', 'Phòng B', room1.point),
    _pin('out', 'Sân ngoài trời', outside),
  ];

  Map<String, dynamic> labelsAlphaG({String? selectedAreaId}) =>
      roomLabelsGeoJson(campus, pins, building: 'Tòa Alpha', floorOrder: 0, selectedAreaId: selectedAreaId);

  Map<String, Map> propsById(Map<String, dynamic> fc) => {
        for (final f in fc['features'] as List) (f as Map)['properties']['id'] as String: f['properties'] as Map,
      };

  group('ghim khu: cờ hasLabel', () {
    test('khu có nhãn → hasLabel true; khu không khớp phòng → false', () {
      final labelled = roomLabelAreaIds(labelsAlphaG());
      expect(labelled, {'in-0', 'in-1'});

      final byId = propsById(pinsGeoJson(pins, labelledAreaIds: labelled));
      expect(byId['in-0']!['hasLabel'], isTrue);
      expect(byId['in-1']!['hasLabel'], isTrue);
      expect(byId['out']!['hasLabel'], isFalse);
    });

    test('"Tất cả" toà (không có nhãn) → mọi ghim hasLabel false', () {
      final labelled = roomLabelAreaIds(roomLabelsGeoJson(campus, pins, floorOrder: 0));
      expect(labelled, isEmpty);
      expect(propsById(pinsGeoJson(pins, labelledAreaIds: labelled)).values.every((p) => p['hasLabel'] == false),
          isTrue);
    });

    test('không truyền danh sách khu có nhãn → hasLabel false (như trước P-MAP-4)', () {
      expect(propsById(pinsGeoJson(pins)).values.every((p) => p['hasLabel'] == false), isTrue);
    });
  });

  group('biểu thức ẩn ghim', () {
    test('dưới zoom nhãn giữ giá trị cũ; từ zoom nhãn: 0 khi hasLabel và không phải khu đang chọn', () {
      const value = ['case', ['get', 'selected'], areaPinMaxRadius, 9];
      final expr = areaPinHiddenWhenLabelled(value);
      expect(expr, [
        'step',
        ['zoom'],
        value,
        roomLabelMinZoom,
        [
          'case',
          [
            'all',
            ['get', 'hasLabel'],
            ['!', ['get', 'selected']],
          ],
          0,
          value,
        ],
      ]);
      expect(roomLabelMinZoom, 16.4, reason: 'cùng minzoom với lớp nhãn (web 16.4)');
    });
  });

  group('bấm nhãn → mở đúng khu', () {
    test('feature nhãn mang id khu (feature id = areaId); tra ra đúng khu', () {
      for (final f in labelsAlphaG()['features'] as List) {
        final feature = f as Map;
        final areaId = feature['properties']['areaId'];
        expect(feature['id'], areaId);
        expect(findAreaPinById(pins, feature['id'] as String)!.id, areaId);
      }
      expect(findAreaPinById(pins, 'khong-co'), isNull);
    });

    test('khu không có trong danh sách nhưng đang chọn (ghim tạm của sự cố) vẫn tra được', () {
      final temp = _pin('incident_target', 'Vị trí sự cố', room0.point);
      expect(findAreaPinById(pins, 'incident_target', selected: temp), same(temp));
      expect(findAreaPinById(pins, 'in-1', selected: temp)!.id, 'in-1');
    });

    test('provider: chọn khu từ nhãn → khu đang chọn đúng, tô đúng phòng của nhãn', () async {
      final provider = MapProvider(ApiClient(), campusRepository: _DiskCampusRepository(campus));
      await provider.loadCampusMap();

      final label = (labelsAlphaG()['features'] as List).cast<Map>().firstWhere((f) => f['id'] == 'in-1');
      provider.selectArea(findAreaPinById(pins, label['id'] as String));

      expect(provider.selectedArea!.id, 'in-1');
      expect(provider.highlightRoomId, label['properties']['id']);
      expect(provider.highlightRoomId, room1.roomId);
    });
  });
}
