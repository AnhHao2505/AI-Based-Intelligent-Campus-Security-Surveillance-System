import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_data.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_repository.dart';

/// Đọc asset thật từ đĩa (mobile/assets/campus/), giống rootBundle trong app.
class DiskAssetBundle extends CachingAssetBundle {
  final Map<String, String> overrides;
  final Set<String> missing;

  DiskAssetBundle({this.overrides = const {}, this.missing = const {}});

  @override
  Future<ByteData> load(String key) async {
    if (missing.contains(key)) throw FlutterError('Unable to load asset: $key');
    final text = overrides[key] ?? File(key).readAsStringSync();
    final bytes = utf8.encode(text);
    return ByteData.view(Uint8List.fromList(bytes).buffer);
  }
}

Future<CampusMapData> _parseSync(Map<String, String> files) async => parseCampusAssets(files);

Map<String, dynamic> _manifestOnDisk() =>
    jsonDecode(File('assets/campus/manifest.json').readAsStringSync()) as Map<String, dynamic>;

void main() {
  group('Nạp asset bản đồ khuôn viên thật (mobile/assets/campus)', () {
    late CampusMapData data;
    late Map<String, dynamic> manifest;

    setUpAll(() async {
      manifest = _manifestOnDisk();
      data = await CampusMapRepository(bundle: DiskAssetBundle(), parser: _parseSync).load();
    });

    test('đọc seedVersion, schemaVersion, toà và tầng đúng manifest', () {
      expect(data.seedVersion, manifest['seedVersion']);
      expect(data.seedVersion, isNotEmpty);
      expect(data.schemaVersion, supportedCampusSchemaVersion);
      final names = (manifest['buildings'] as List).map((b) => b['name']).toList();
      expect(data.buildings.map((b) => b.name).toList(), names);
      final floorCount = (manifest['buildings'] as List).fold<int>(0, (s, b) => s + (b['floors'] as List).length);
      expect(data.floors.length, floorCount);
    });

    test('số khối mỗi toà / tầng bằng số trong manifest', () {
      for (final b in manifest['buildings'] as List) {
        for (final f in b['floors'] as List) {
          final layer = data.floors[CampusMapData.floorKey(b['name'], f['floorOrder'])];
          expect(layer, isNotNull, reason: '${b['name']} F${f['floorOrder']}');
          expect(layer!.featureCount, f['features'], reason: '${b['name']} F${f['floorOrder']}');
        }
      }
    });

    test('đúng số khối theo loại (seed 2026-11-12-epsilon-1, báo cáo P-MAP-0 mục 2.2)', () {
      final eps2 = data.floors[CampusMapData.floorKey('Tòa Epsilon', 2)]!.countsByLayer;
      expect(eps2, {'slab': 28, 'wall': 315, 'room': 23, 'door': 36, 'portal': 4});
      final alpha0 = data.floors[CampusMapData.floorKey('Tòa Alpha', 0)]!.countsByLayer;
      expect(alpha0, {'slab': 20, 'wall': 223, 'room': 19, 'door': 29, 'portal': 2});
    });

    test('mọi khối sàn / phòng / tường (lớp được vẽ) có màu, chân, đỉnh cho fill-extrusion', () {
      const drawn = {'slab', 'room', 'wall'};
      for (final floor in data.floors.values) {
        for (final f in floor.features) {
          final p = f['properties'] as Map;
          if ((f['geometry'] as Map)['type'] != 'Polygon' || !drawn.contains(p['layer'])) continue;
          expect(p['color'], isA<String>());
          expect(p['baseHeight'], isA<num>());
          expect(p['extrudeHeight'], isA<num>());
          expect((p['extrudeHeight'] as num) >= (p['baseHeight'] as num), isTrue);
        }
      }
    });

    test('khung bao chứa tâm khu vực seed; tâm khuôn viên lấy từ khung bao', () {
      // KT Điện, Tòa Epsilon, Tầng Trệt (V120_1__seed_epsilon_areas.sql)
      expect(data.bounds.contains(10.840506, 106.811247), isTrue);
      expect(data.bounds.contains(data.bounds.centerLatitude, data.bounds.centerLongitude), isTrue);
    });

    test('lọc khối theo toà (kể cả alias) / tầng / loại', () {
      final rooms = data.featureCollectionFor(building: 'Tòa Espilon', floorOrder: 2, layers: {'room'});
      expect((rooms['features'] as List).length, 23);
      final allBuildingsF0 = data.featureCollectionFor(floorOrder: 0);
      expect((allBuildingsF0['features'] as List).length,
          data.floors[CampusMapData.floorKey('Tòa Alpha', 0)]!.featureCount +
              data.floors[CampusMapData.floorKey('Tòa Epsilon', 0)]!.featureCount);
      expect(data.floorOrdersFor('Tòa HCM'), [0, 1, 2]);
      expect(data.floorOrdersFor(null), [0, 1, 2, 3, 4]);
    });

    test('tìm phòng chứa tâm khu (KT Điện, Epsilon tầng trệt)', () {
      final id = data.roomIdAt(latitude: 10.840506, longitude: 106.811247, building: 'Tòa Epsilon', floorOrder: 0);
      expect(id, isNotNull);
      expect(data.roomIdAt(latitude: 0, longitude: 0, floorOrder: 0), isNull);
    });

    test('zoom vừa khung bao: hợp lý, màn rộng hơn thì zoom lớn hơn', () {
      final small = zoomToFitBounds(data.bounds, 360, 640, paddingPx: 40);
      final large = zoomToFitBounds(data.bounds, 1080, 1920, paddingPx: 40);
      expect(small, inInclusiveRange(14, 19));
      expect(large, greaterThan(small));
    });

    test('phân tích trong isolate (compute) cho cùng kết quả', () async {
      final viaIsolate = await CampusMapRepository(bundle: DiskAssetBundle()).load();
      expect(viaIsolate.seedVersion, data.seedVersion);
      expect(viaIsolate.floors.length, data.floors.length);
    });
  });

  group('Lỗi đọc asset → CampusMapLoadException, không crash', () {
    Future<Object?> loadError(DiskAssetBundle bundle) async {
      try {
        await CampusMapRepository(bundle: bundle, parser: _parseSync).load();
        return null;
      } catch (e) {
        return e;
      }
    }

    test('thiếu manifest.json', () async {
      final e = await loadError(DiskAssetBundle(missing: {'assets/campus/manifest.json'}));
      expect(e, isA<CampusMapLoadException>());
      expect((e as CampusMapLoadException).message, isNotEmpty);
    });

    test('thiếu một file GeoJSON', () async {
      final e = await loadError(DiskAssetBundle(missing: {'assets/campus/layers/toa-epsilon__f3.geojson'}));
      expect(e, isA<CampusMapLoadException>());
    });

    test('GeoJSON hỏng', () async {
      final e = await loadError(DiskAssetBundle(overrides: {'assets/campus/layers/toa-alpha__f1.geojson': '{oops'}));
      expect(e, isA<CampusMapLoadException>());
      expect((e as CampusMapLoadException).message, contains('toa-alpha__f1'));
    });

    test('manifest thiếu seedVersion / sai schemaVersion', () async {
      final m = _manifestOnDisk();
      final noSeed = Map<String, dynamic>.from(m)..remove('seedVersion');
      final e1 = await loadError(DiskAssetBundle(overrides: {'assets/campus/manifest.json': jsonEncode(noSeed)}));
      expect((e1 as CampusMapLoadException).message, contains('seedVersion'));
      final badSchema = Map<String, dynamic>.from(m)..['schemaVersion'] = 99;
      final e2 = await loadError(DiskAssetBundle(overrides: {'assets/campus/manifest.json': jsonEncode(badSchema)}));
      expect(e2, isA<CampusMapLoadException>());
    });

    test('số khối lệch manifest', () async {
      final m = _manifestOnDisk();
      ((m['buildings'] as List).first['floors'] as List).first['features'] = 1;
      final e = await loadError(DiskAssetBundle(overrides: {'assets/campus/manifest.json': jsonEncode(m)}));
      expect((e as CampusMapLoadException).message, contains('không đồng bộ'));
    });
  });
}
