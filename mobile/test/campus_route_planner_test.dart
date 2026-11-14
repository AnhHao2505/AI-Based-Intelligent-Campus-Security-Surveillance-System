import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/features/map/data/campus_graph.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_data.dart';
import 'package:guard_mobile_app/features/map/data/campus_route_planner.dart';
import 'package:guard_mobile_app/features/map/services/route_settings_service.dart';
import 'package:latlong2/latlong.dart';

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
  files['graph.json'] = File('assets/campus/graph.json').readAsStringSync();
  return parseCampusAssets(files);
}

/// Điểm cách (lat, lon) [meters] về phía bắc.
LatLng _north(double lat, double lon, double meters) => LatLng(lat + meters / 111320, lon);

void main() {
  final campus = _campusOnDisk();
  final graph = campus.graph!;
  final golden = (jsonDecode(File('assets/campus/golden-routes.json').readAsStringSync()) as Map)['cases'] as List;

  CampusGraphNode outdoorNode() => graph.nodes.firstWhere((n) => n.isOutdoor);

  group('điểm xuất phát', () {
    test('(a) GPS ngoài trời trong khuôn viên → nút ngoài trời gần nhất, tuyến bắt đầu tại GPS', () {
      final o = outdoorNode();
      final gps = _north(o.latitude, o.longitude, 3);
      expect(campus.buildingAt(latitude: gps.latitude, longitude: gps.longitude), isNull);
      final dest = graph.nodes.firstWhere((n) => n.building == 'Tòa Epsilon' && n.floorOrder == 2 && n.type == 'NODE');
      final r = planCampusRoute(
        graph: graph,
        campus: campus,
        gps: gps,
        destinationLatitude: dest.latitude,
        destinationLongitude: dest.longitude,
        destinationBuilding: 'Tòa Epsilon',
        destinationFloorOrder: 2,
      );
      expect(r.startMode, CampusRouteStartMode.outdoorGps);
      expect(r.isDirectFallback, isFalse);
      expect(r.points.first.latLng, gps);
      expect(r.points[1].building, 'OUTDOOR');
      expect(r.points.last.building, 'Tòa Epsilon');
      expect(r.points.last.floorOrder, 2);
      expect(r.floorsClimbed, 2);
      expect(r.floorChanges.map((c) => c.label), ['Lên tầng 2']);
    });

    test('(b) GPS xa khuôn viên (> ngưỡng) → theo toà + tầng đang chọn, bắt đầu ở cầu thang / thang máy', () {
      final o = outdoorNode();
      final far = _north(o.latitude, o.longitude, kGpsFarFromCampusMeters + 2000);
      final dest = graph.nodes.firstWhere((n) => n.building == 'Tòa Epsilon' && n.floorOrder == 0 && n.type == 'NODE');
      final r = planCampusRoute(
        graph: graph,
        campus: campus,
        gps: far,
        selectedBuilding: 'Tòa Epsilon',
        selectedFloorOrder: 3,
        destinationLatitude: dest.latitude,
        destinationLongitude: dest.longitude,
        destinationBuilding: 'Tòa Epsilon',
        destinationFloorOrder: 0,
      );
      expect(r.startMode, CampusRouteStartMode.selectedFloor);
      expect(r.startBuilding, 'Tòa Epsilon');
      expect(r.startFloorOrder, 3);
      final startNode = graph.nodes.firstWhere(
          (n) => n.latitude == r.points.first.latLng.latitude && n.longitude == r.points.first.latLng.longitude && n.floorOrder == 3);
      expect(startNode.isVerticalAccess, isTrue);
      expect(r.floorChanges.map((c) => c.label), ['Xuống tầng G']);
    });

    test('(b) không có GPS và chưa chọn toà → NO_START', () {
      expect(
        () => planCampusRoute(
          graph: graph,
          campus: campus,
          gps: null,
          destinationLatitude: outdoorNode().latitude,
          destinationLongitude: outdoorNode().longitude,
        ),
        throwsA(isA<CampusRouteUnavailable>().having((e) => e.reasonCode, 'reasonCode', 'NO_START')),
      );
    });

    test('(b) GPS nằm trong toà → xuất phát theo toà chứa GPS + tầng đang chọn', () {
      final inside = graph.nodes.firstWhere((n) =>
          n.building == 'Tòa Alpha' &&
          n.floorOrder == 0 &&
          n.type == 'NODE' &&
          campus.buildingAt(latitude: n.latitude, longitude: n.longitude) == 'Tòa Alpha');
      final o = outdoorNode();
      final r = planCampusRoute(
        graph: graph,
        campus: campus,
        gps: LatLng(inside.latitude, inside.longitude),
        selectedBuilding: null,
        selectedFloorOrder: 1,
        destinationLatitude: o.latitude,
        destinationLongitude: o.longitude,
      );
      expect(r.startMode, CampusRouteStartMode.selectedFloor);
      expect(r.startBuilding, 'Tòa Alpha');
      expect(r.startFloorOrder, 1);
      expect(r.points.last.building, 'OUTDOOR');
    });
  });

  group('điểm đến', () {
    test('cùng toà + cùng tầng → nút gần tâm khu nhất của tầng đó', () {
      final n = graph.nodes.firstWhere((x) => x.building == 'Tòa Epsilon' && x.floorOrder == 4 && x.type == 'NODE');
      final picked = resolveDestinationNode(graph,
          latitude: n.latitude, longitude: n.longitude, building: 'Tòa Epsilon', floorOrder: 4);
      expect(picked!.id, n.id);
    });

    test('toà không có tầng đó → nút gần nhất cùng toà', () {
      final n = graph.nodes.firstWhere((x) => x.building == 'Tòa Alpha' && x.type == 'NODE');
      final picked = resolveDestinationNode(graph,
          latitude: n.latitude, longitude: n.longitude, building: 'Tòa Alpha', floorOrder: 9);
      expect(picked!.building, 'Tòa Alpha');
    });

    test('khu ngoài trời / toà không có trong đồ thị → nút ngoài trời gần nhất', () {
      final o = outdoorNode();
      expect(resolveDestinationNode(graph, latitude: o.latitude, longitude: o.longitude)!.id, o.id);
      expect(
          resolveDestinationNode(graph, latitude: o.latitude, longitude: o.longitude, building: 'Toà không có')!.isOutdoor,
          isTrue);
    });

    test('thiếu toạ độ đích (GR-033) → INVALID_COORDS', () {
      final c = golden.firstWhere((x) => x['id'] == 'GR-033') as Map;
      final start = c['start'] as Map;
      expect(
        () => planCampusRoute(
          graph: graph,
          campus: campus,
          gps: LatLng((start['lat'] as num).toDouble(), (start['lon'] as num).toDouble()),
          destinationLatitude: null,
          destinationLongitude: null,
          destinationBuilding: 'Tòa Epsilon',
        ),
        throwsA(isA<CampusRouteUnavailable>().having((e) => e.reasonCode, 'reasonCode', 'INVALID_COORDS')),
      );
    });
  });

  test('đồ thị không có đường → đoạn thẳng xuất phát → đích kèm thông báo', () {
    const g = [
      CampusGraphNode(id: 'o1', latitude: 10.8400, longitude: 106.8100, building: 'OUTDOOR', floorOrder: 0, type: 'NODE', isOutdoor: true, isVirtual: false),
      CampusGraphNode(id: 'o2', latitude: 10.8410, longitude: 106.8100, building: 'OUTDOOR', floorOrder: 0, type: 'NODE', isOutdoor: true, isVirtual: false),
    ];
    final r = planCampusRoute(
      graph: CampusGraph.build(g, const []),
      gps: const LatLng(10.84001, 106.8100),
      destinationLatitude: 10.84099,
      destinationLongitude: 106.8100,
    );
    expect(r.isDirectFallback, isTrue);
    expect(r.message, 'Chưa có đường đi trên bản đồ, hiển thị hướng thẳng');
    expect(r.points, hasLength(2));
    final fc = routeGeoJson(r, currentFloor: 0);
    expect((fc['features'] as List).single['properties'], {'current': true, 'direct': true});
  });

  group('thời gian dự kiến (route-settings)', () {
    test('Σ chiều dài / tốc độ + số tầng đổi × giây, làm tròn LÊN phút', () {
      const eta = RouteEtaSettings(walkingSpeedMps: 1.0, floorChangeSeconds: 30);
      expect(eta.minutesFor(60, 0), 1);
      expect(eta.minutesFor(61, 0), 2);
      expect(eta.minutesFor(30, 1), 1);
      expect(eta.minutesFor(31, 1), 2);
      expect(eta.minutesFor(0, 0), 0);
      expect(RouteEtaSettings.defaults.walkingSpeedMps, 1.45);
      expect(RouteEtaSettings.defaults.floorChangeSeconds, 14);
    });

    test('tuyến dùng tham số truyền vào: đổi tốc độ → đổi phút, đường không đổi', () {
      final o = outdoorNode();
      final dest = graph.nodes.firstWhere((n) => n.building == 'Tòa Epsilon' && n.floorOrder == 4 && n.type == 'NODE');
      CampusRoute plan(RouteEtaSettings eta) => planCampusRoute(
            graph: graph,
            campus: campus,
            gps: LatLng(o.latitude, o.longitude),
            destinationLatitude: dest.latitude,
            destinationLongitude: dest.longitude,
            destinationBuilding: 'Tòa Epsilon',
            destinationFloorOrder: 4,
            eta: eta,
          );
      final slow = plan(const RouteEtaSettings(walkingSpeedMps: 0.5, floorChangeSeconds: 120));
      final fast = plan(const RouteEtaSettings(walkingSpeedMps: 3.0, floorChangeSeconds: 0));
      expect(slow.latLngs, fast.latLngs);
      expect(slow.etaMinutes, ((slow.distanceMeters / 0.5 + slow.floorsClimbed * 120) / 60).ceil());
      expect(fast.etaMinutes, (fast.distanceMeters / 3.0 / 60).ceil());
      expect(slow.etaMinutes, greaterThan(fast.etaMinutes));
    });

    test('fromJson: thiếu / sai giá trị → mặc định', () {
      expect(RouteEtaSettings.fromJson({'walkingSpeedMps': 1.2, 'floorChangeSeconds': 30}).walkingSpeedMps, 1.2);
      final bad = RouteEtaSettings.fromJson({'walkingSpeedMps': 0, 'floorChangeSeconds': -1});
      expect(bad.walkingSpeedMps, 1.45);
      expect(bad.floorChangeSeconds, 14);
      expect(RouteEtaSettings.fromJson(const {}).walkingSpeedMps, 1.45);
    });

    test('RouteSettingsService: lỗi mạng → mặc định (có log), phản hồi đúng → giá trị server', () async {
      final logs = <String>[];
      final oldPrint = debugPrint;
      debugPrint = (String? m, {int? wrapWidth}) => logs.add(m ?? '');
      try {
        final failing = Dio()
          ..interceptors.add(InterceptorsWrapper(
            onRequest: (o, h) => h.reject(DioException(requestOptions: o, message: 'offline')),
          ));
        final def = await RouteSettingsService(failing).load();
        expect(def.walkingSpeedMps, 1.45);
        expect(def.floorChangeSeconds, 14);
        expect(logs.any((l) => l.contains('route-settings')), isTrue);

        final ok = Dio()
          ..interceptors.add(InterceptorsWrapper(
            onRequest: (o, h) => h.resolve(Response(
              requestOptions: o,
              statusCode: 200,
              data: {'walkingSpeedMps': 1.2, 'floorChangeSeconds': 30},
            )),
          ));
        final got = await RouteSettingsService(ok).load();
        expect(got.walkingSpeedMps, 1.2);
        expect(got.floorChangeSeconds, 30);
      } finally {
        debugPrint = oldPrint;
      }
    });
  });

  group('vẽ nhiều tầng', () {
    CampusGraphNode n(String id, int floor, {String type = 'NODE'}) => CampusGraphNode(
        id: id, latitude: 10.84 + floor * 1e-5, longitude: 106.81 + id.length * 1e-6, building: 'Tòa Epsilon',
        floorOrder: floor, type: type, isOutdoor: false, isVirtual: false);

    test('chuỗi cầu thang qua nhiều tầng gộp thành một chỗ đổi tầng', () {
      final path = [n('a', 0), n('s0', 0, type: 'STAIRS'), n('s1', 1, type: 'STAIRS'), n('s2', 2, type: 'STAIRS'), n('b', 2),
        n('e2', 2, type: 'ELEVATOR'), n('e1', 1, type: 'ELEVATOR'), n('c', 1)];
      final changes = floorChangesOf(path);
      expect(changes.map((c) => c.label), ['Lên tầng 2', 'Xuống tầng 1']);
      expect(changes.map((c) => c.via), ['STAIRS', 'ELEVATOR']);
    });

    test('đoạn ở tầng đang xem đậm (current), tầng khác / đoạn đổi tầng mờ', () {
      final pts = [
        for (final (lat, f) in [(0.0, 0), (1.0, 0), (2.0, 1), (3.0, 1)])
          CampusRoutePoint(LatLng(10.84 + lat * 1e-4, 106.81), building: 'Tòa Epsilon', floorOrder: f),
      ];
      final route = CampusRoute(
        points: pts,
        distanceMeters: 30,
        floorsClimbed: 1,
        floorChanges: const [],
        isDirectFallback: false,
        startMode: CampusRouteStartMode.outdoorGps,
        eta: RouteEtaSettings.defaults,
      );
      final onG = (routeGeoJson(route, currentFloor: 0)['features'] as List).cast<Map>();
      expect(onG.map((f) => f['properties']['current']), [true, false]);
      expect((onG[0]['geometry']['coordinates'] as List), hasLength(2));
      expect((onG[1]['geometry']['coordinates'] as List), hasLength(3));
      final on1 = (routeGeoJson(route, currentFloor: 1)['features'] as List).cast<Map>();
      expect(on1.map((f) => f['properties']['current']), [false, true]);
      expect(routeGeoJson(null, currentFloor: 0)['features'], isEmpty);
    });
  });

  test('so với web: ca golden xuất phát ngoài trời → gắn điểm + Dijkstra mobile cho đúng chuỗi nút web', () {
    var same = 0, total = 0;
    for (final raw in golden) {
      final c = raw as Map;
      final ex = c['expected'] as Map;
      if (ex['reachable'] != true) continue;
      final start = c['start'] as Map;
      final end = c['end'] as Map;
      if (start['building'] != 'OUTDOOR') continue; // mobile chỉ gắn GPS ngoài trời
      total++;
      final r = planCampusRoute(
        graph: graph,
        campus: campus,
        gps: LatLng((start['lat'] as num).toDouble(), (start['lon'] as num).toDouble()),
        destinationLatitude: (end['lat'] as num).toDouble(),
        destinationLongitude: (end['lon'] as num).toDouble(),
        destinationBuilding: end['building'] as String?,
        destinationFloorOrder: (end['floorOrder'] as num?)?.toInt(),
      );
      expect(r.isDirectFallback, isFalse, reason: '${c['id']}');
      final ids = (ex['pathNodeIds'] as List).cast<String>();
      if (listEquals(r.nodeIds, ids)) {
        same++;
      } else {
        debugPrint('ATTACH_DIFF ${c['id']} ${c['category']} web=${ids.length} nút (cuối ${ids.last}) '
            'mobile=${r.nodeIds.length} nút (cuối ${r.nodeIds.last})');
      }
    }
    debugPrint('ATTACH_VS_WEB outdoorStartCases=$total sameNodes=$same');
    expect(total, 13);
    expect(same, total);
  });
}
