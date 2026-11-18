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

// Tên phòng nằm trên ghim khu (phương án C), ghim hiện ở mọi zoom, bấm nhãn mở đúng khu (P-MAP-4).

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

  group('thứ tự lớp (phương án C)', () {
    int at(String id) {
      final i = campusLayerOrder.indexOf(id);
      expect(i, greaterThanOrEqualTo(0), reason: '$id phải có trong campusLayerOrder');
      return i;
    }

    test('ghim khu → quầng sự cố → nhãn → ghim sự cố → vị trí bảo vệ: quầng dưới nhãn, ghim sự cố trên nhãn', () {
      expect(at(campusIncidentHaloLayerId), at(campusPinsLayerId) + 1, reason: 'quầng ngay trên ghim khu');
      expect(at(campusRoomLabelsLayerId), at(campusIncidentHaloLayerId) + 1, reason: 'quầng ngay dưới tên phòng');
      expect(at(campusIncidentPinLayerId), at(campusRoomLabelsLayerId) + 1, reason: 'ghim sự cố ngay trên tên phòng');
      expect(at('campus-guard-halo'), at(campusIncidentPinLayerId) + 1);
      expect(campusLayerOrder.sublist(at(campusPinsLayerId), at('campus-guard-halo') + 1), [
        'campus-pins-layer',
        'campus-incident-halo-layer',
        'campus-room-labels-layer',
        'campus-incident-pin-layer',
        'campus-guard-halo',
      ]);
    });

    test('id lớp quầng sự cố cố định; quầng đỏ đứng yên gấp đôi ghim khu đang chọn', () {
      expect(campusIncidentHaloLayerId, 'campus-incident-halo-layer');
      expect(campusLayerOrder[campusLayerOrder.indexOf(campusRoomLabelsLayerId) - 1], 'campus-incident-halo-layer');
      expect(incidentHaloColor.toLowerCase(), '#ef4444');
      expect(incidentHaloRadius, areaPinMaxRadius * 2);
      expect(incidentHaloOpacity, 0.25);
      expect(incidentHaloStrokeWidth, 2);
      expect(incidentHaloStrokeOpacity, 0.6);
    });

    test('tên phòng nằm trên ghim khu và quầng sự cố, dưới vị trí bảo vệ', () {
      expect(at('campus-room-labels-layer'), greaterThan(at('campus-pins-layer')));
      expect(at('campus-room-labels-layer'), greaterThan(at(campusIncidentHaloLayerId)));
      expect(at('campus-room-labels-layer'), lessThan(at('campus-guard-halo')));
      expect(at('campus-room-labels-layer'), lessThan(at('campus-guard-dot')));
    });

    test('sàn → phòng → tường → cầu thang → tuyến đường → ghim khu; không trùng id', () {
      expect(campusLayerOrder.take(4), ['campus-slab-layer', 'campus-room-layer', 'campus-wall-layer', 'campus-portals-layer']);
      for (final route in ['campus-route-outline', 'campus-route-line', 'campus-route-direct', 'campus-route-floors-layer']) {
        expect(at(route), greaterThan(at('campus-portals-layer')), reason: route);
        expect(at(route), lessThan(at('campus-pins-layer')), reason: route);
      }
      expect(campusLayerOrder.last, 'campus-guard-dot');
      expect(campusLayerOrder.toSet().length, campusLayerOrder.length);
    });
  });

  group('ghim khu hiện ở mọi zoom (bỏ ẩn ghim)', () {
    test('GeoJSON ghim không còn cờ hasLabel; mọi khu đều có ghim', () {
      final byId = propsById(pinsGeoJson(pins, selectedId: 'in-0'));
      expect(byId.keys.toSet(), {'in-0', 'in-1', 'out'});
      for (final p in byId.values) {
        expect(p.keys.toSet(), {'id', 'name', 'color', 'selected'});
      }
      expect(byId['in-0']!['selected'], isTrue);
    });
  });

  group('chạm trúng cả nhãn và ghim', () {
    test('ghim khu A nằm dưới nhãn khu B → chọn khu B của nhãn', () {
      // MapLibre trả ghim 'out' (khu khác), nhưng tại điểm chạm có nhãn của khu 'in-1'
      final areaId = tappedAreaId(layerId: campusPinsLayerId, featureId: 'out', labelIdsAtPoint: ['in-1']);
      expect(areaId, 'in-1');
      expect(findAreaPinById(pins, areaId)!.name, 'Phòng B');
    });

    test('không có nhãn tại điểm chạm → khu của ghim; id rỗng / null bị bỏ qua', () {
      expect(tappedAreaId(layerId: campusPinsLayerId, featureId: 'out'), 'out');
      expect(tappedAreaId(layerId: campusPinsLayerId, featureId: 'out', labelIdsAtPoint: [null, '']), 'out');
      expect(tappedAreaId(layerId: campusPinsLayerId, featureId: 'out', labelIdsAtPoint: [null, 'in-0']), 'in-0');
    });

    test('nhãn thật của tầng: id feature nhãn đưa vào tappedAreaId → đúng khu của nhãn', () {
      for (final f in labelsAlphaG()['features'] as List) {
        final label = f as Map;
        final areaId = tappedAreaId(layerId: campusPinsLayerId, featureId: 'out', labelIdsAtPoint: [label['id']]);
        expect(areaId, label['properties']['areaId']);
        expect(findAreaPinById(pins, areaId)!.id, label['properties']['areaId']);
      }
    });
  });

  group('ghim sự cố tách lớp riêng', () {
    final temp = AreaPinModel(
      id: 'incident_target',
      name: 'Vị trí sự cố (CAM-006)',
      areaLevel: AreaPinModel.unknownLevel,
      centerLatitude: room0.point[1],
      centerLongitude: room0.point[0],
      isActive: true,
    );

    test('cấp chưa xác định: nhãn "Chưa xác định cấp", màu xám mặc định, không phải cấp cũ RESTRICTED', () {
      expect(AreaPinModel.unknownLevel, isNot('RESTRICTED'));
      expect(temp.levelDisplayName, 'Chưa xác định cấp');
      expect(temp.levelColor.toARGB32(), 0xFF6B7280);
      expect(temp.levelColor, AreaPinModel.levelColorOf('KHONG-CO'), reason: 'cùng màu mặc định của cấp lạ');
    });

    test('provider: sự cố không khớp khu → ghim tạm cấp chưa xác định; payload có cấp → dùng cấp đó', () {
      final provider = MapProvider(ApiClient(), campusRepository: _DiskCampusRepository(campus));
      final noLevel = provider.findPinForIncident(areaId: 'khu-khong-ton-tai', cameraCode: 'CAM-006')!;
      expect(noLevel.id, 'khu-khong-ton-tai');
      expect(noLevel.name, 'Vị trí sự cố (CAM-006)');
      expect(noLevel.areaLevel, AreaPinModel.unknownLevel);
      expect(noLevel.levelDisplayName, 'Chưa xác định cấp');

      expect(provider.findPinForIncident(areaId: 'khu-khong-ton-tai', areaLevel: '   ')!.areaLevel,
          AreaPinModel.unknownLevel, reason: 'cấp rỗng coi như không có');
      final withLevel = provider.findPinForIncident(areaId: 'khu-khong-ton-tai', areaLevel: 'confidential')!;
      expect(withLevel.areaLevel, 'CONFIDENTIAL');
      expect(withLevel.levelDisplayName, 'Bảo mật (Cấp 2)');
    });

    test('ghim tạm sự cố không còn trong GeoJSON ghim khu; nằm ở nguồn ghim sự cố', () {
      final split = splitIncidentPin(pins, selected: temp, incidentAreaId: 'incident_target');
      expect(split.incident, same(temp));
      expect(split.areas.map((p) => p.id), isNot(contains('incident_target')));
      expect(propsById(pinsGeoJson(split.areas)).keys, isNot(contains('incident_target')));
      final incidentFc = pinsGeoJson([?split.incident], selectedId: split.incident?.id);
      expect(propsById(incidentFc).keys, ['incident_target']);
      expect(propsById(incidentFc)['incident_target']!['selected'], isTrue, reason: 'giữ hình ghim đang chọn như trước');
    });

    test('sự cố ở khu có thật: ghim khu đó chỉ vẽ ở lớp sự cố, không vẽ 2 lần', () {
      final split = splitIncidentPin(pins, selected: pins[1], incidentAreaId: 'in-1');
      expect(split.incident!.id, 'in-1');
      expect(split.areas.map((p) => p.id), ['in-0', 'out']);
    });

    test('không có sự cố: khu đang chọn ngoài danh sách vẫn thêm vào ghim khu như trước', () {
      final split = splitIncidentPin(pins.take(2).toList(), selected: pins[2]);
      expect(split.incident, isNull);
      expect(split.areas.map((p) => p.id), ['in-0', 'in-1', 'out']);
      expect(splitIncidentPin(pins, selected: pins[0], incidentAreaId: 'khac').incident, isNull);
    });

    test('chạm trúng cả ghim sự cố và nhãn → chọn sự cố', () {
      final areaId = tappedAreaId(
        layerId: campusIncidentPinLayerId,
        featureId: 'incident_target',
        labelIdsAtPoint: ['in-0'],
      );
      expect(areaId, 'incident_target');
      expect(findAreaPinById(pins, areaId, selected: temp), same(temp));
      // Chạm vào nhãn (lớp nhãn) → khu của nhãn; chạm vào ghim khu có nhãn đè → khu của nhãn
      expect(tappedAreaId(layerId: campusRoomLabelsLayerId, featureId: 'in-0'), 'in-0');
      expect(tappedAreaId(layerId: campusPinsLayerId, featureId: 'out', labelIdsAtPoint: ['in-0']), 'in-0');
    });

    test('provider: mở sự cố → nhớ khu sự cố; chọn khu khác / đóng thẻ → hết', () async {
      final provider = MapProvider(ApiClient(), campusRepository: _DiskCampusRepository(campus));
      await provider.loadCampusMap();
      provider.focusIncident(temp);
      expect(provider.incidentAreaId, 'incident_target');
      provider.selectArea(temp);
      expect(provider.incidentAreaId, 'incident_target', reason: 'bấm lại chính ghim sự cố vẫn là sự cố');
      provider.selectArea(pins[0]);
      expect(provider.incidentAreaId, isNull);
      provider.focusIncident(temp);
      provider.selectArea(null);
      expect(provider.incidentAreaId, isNull);
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
