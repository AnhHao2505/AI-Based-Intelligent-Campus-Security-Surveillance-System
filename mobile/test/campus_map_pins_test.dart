import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/map/data/campus_floor_resolver.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_data.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_repository.dart';
import 'package:guard_mobile_app/features/map/models/area_pin_model.dart';
import 'package:guard_mobile_app/features/map/providers/map_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

class _DiskBundle extends CachingAssetBundle {
  final bool fail;
  _DiskBundle({this.fail = false});

  @override
  Future<ByteData> load(String key) async {
    if (fail) throw FlutterError('Unable to load asset: $key');
    return ByteData.view(Uint8List.fromList(utf8.encode(File(key).readAsStringSync())).buffer);
  }
}

CampusMapRepository _repo({bool fail = false}) =>
    CampusMapRepository(bundle: _DiskBundle(fail: fail), parser: (f) async => parseCampusAssets(f));

AreaPinModel _pin(String id, {String? building, String? floor, double lat = 10.840506, double lon = 106.811247}) =>
    AreaPinModel(
      id: id,
      name: 'Khu $id',
      areaLevel: 'CONFIDENTIAL',
      building: building,
      floor: floor,
      centerLatitude: lat,
      centerLongitude: lon,
      isActive: true,
    );

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  late CampusMapData campus;

  setUpAll(() async {
    SharedPreferences.setMockInitialValues({});
    campus = await _repo().load();
  });

  group('resolveFloorOrder — cùng quy tắc web (campusIndoor3dData.js:182-235)', () {
    test('các dạng tên tầng', () {
      expect(resolveFloorOrder(null), 0);
      expect(resolveFloorOrder('Tầng Trệt'), 0);
      expect(resolveFloorOrder('G'), 0);
      expect(resolveFloorOrder('Tầng 1'), 1);
      expect(resolveFloorOrder('Tầng 2'), 2);
      expect(resolveFloorOrder('Tầng 3'), 3);
      expect(resolveFloorOrder('Tầng 3', isAlpha: true), 2);
      expect(resolveFloorOrder('Tầng 4'), 4);
      expect(resolveFloorOrder('LV3'), 2);
      expect(resolveFloorOrder('Lầu 1'), 1);
      expect(resolveFloorOrder('không rõ'), 0);
    });
  });

  group('Lọc ghim khu theo toà / tầng', () {
    final pins = [
      _pin('eps-g', building: 'Tòa Epsilon', floor: 'Tầng Trệt'),
      _pin('eps-2', building: 'Tòa Epsilon', floor: 'Tầng 2'),
      _pin('alpha-1', building: 'Tòa HCM', floor: 'Tầng 1'),
      _pin('outdoor', building: null, floor: null),
      _pin('no-coords', building: 'Tòa Epsilon', floor: 'Tầng Trệt', lat: 0, lon: 0),
    ];
    List<String> ids(List<AreaPinModel> l) => l.map((p) => p.id).toList();

    test('bỏ khu không có toạ độ', () {
      expect(ids(filterPinsForView(pins: pins, campus: campus)), isNot(contains('no-coords')));
    });

    test('toà theo alias + tầng; khu không có tầng vẫn hiện', () {
      expect(ids(filterPinsForView(pins: pins, building: 'Tòa Espilon', floorOrder: 0, campus: campus)), ['eps-g']);
      expect(ids(filterPinsForView(pins: pins, building: 'Tòa Epsilon', floorOrder: 2, campus: campus)), ['eps-2']);
      expect(ids(filterPinsForView(pins: pins, building: 'Tòa Alpha', floorOrder: 1, campus: campus)), ['alpha-1']);
      expect(ids(filterPinsForView(pins: pins, floorOrder: 0, campus: campus)), ['eps-g', 'outdoor']);
    });
  });

  group('Mở bản đồ từ thẻ sự cố', () {
    test('khu Epsilon tầng trệt → chọn Epsilon, tầng 0, tô phòng chứa tâm khu', () {
      final focus = resolveIncidentFocus(_pin('kt-dien', building: 'Tòa Espilon', floor: 'Tầng Trệt'), campus);
      expect(focus.building, 'Tòa Epsilon');
      expect(focus.floorOrder, 0);
      expect(focus.roomId, isNotNull);
    });

    test('ghim tạm không có toà / tầng → tầng trệt, không đổi toà', () {
      final focus = resolveIncidentFocus(_pin('incident_target', lat: 0, lon: 0), campus);
      expect(focus.building, isNull);
      expect(focus.floorOrder, 0);
      expect(focus.roomId, isNull);
    });

    test('MapProvider.focusIncident chuyển toà / tầng, tô phòng', () async {
      final provider = MapProvider(ApiClient(), campusRepository: _repo());
      await provider.loadCampusMap();
      provider.selectFloor(3);
      final focus = provider.focusIncident(_pin('kt-dien', building: 'Tòa Epsilon', floor: 'Tầng Trệt'));
      expect(provider.selectedBuilding, 'Tòa Epsilon');
      expect(provider.selectedFloorOrder, 0);
      expect(provider.selectedArea?.id, 'kt-dien');
      expect(provider.highlightRoomId, focus.roomId);
      expect(provider.highlightRoomId, isNotNull);
      provider.dispose();
    });
  });

  group('MapProvider + asset', () {
    test('tâm khuôn viên lấy từ khung bao asset, mặc định tầng trệt', () async {
      final provider = MapProvider(ApiClient(), campusRepository: _repo());
      expect(provider.campusCenter, isNull);
      await provider.loadCampusMap();
      expect(provider.campusError, isNull);
      expect(provider.selectedFloorOrder, 0);
      expect(provider.campusCenter!.latitude, closeTo(campus.bounds.centerLatitude, 1e-12));
      expect(provider.campusCenter!.longitude, closeTo(campus.bounds.centerLongitude, 1e-12));
      provider.selectBuilding('Tòa Alpha');
      expect(provider.availableFloorOrders, [0, 1, 2]);
      provider.selectFloor(2);
      provider.selectBuilding('Tòa Epsilon');
      expect(provider.selectedFloorOrder, 2);
      provider.dispose();
    });

    test('đổi sang toà ít tầng hơn → tầng được kéo về tầng hợp lệ', () async {
      final provider = MapProvider(ApiClient(), campusRepository: _repo());
      await provider.loadCampusMap();
      provider.selectBuilding('Tòa Epsilon');
      provider.selectFloor(4);
      provider.selectBuilding('Tòa Alpha');
      expect(provider.selectedFloorOrder, 0);
      provider.dispose();
    });

    test('lỗi đọc asset → campusError, không ném lỗi, không có dữ liệu', () async {
      final provider = MapProvider(ApiClient(), campusRepository: _repo(fail: true));
      await provider.loadCampusMap();
      expect(provider.campus, isNull);
      expect(provider.campusError, 'Không đọc được dữ liệu bản đồ khuôn viên');
      expect(provider.campusCenter, isNull);
      expect(provider.isLoadingCampus, isFalse);
      provider.dispose();
    });
  });
}
