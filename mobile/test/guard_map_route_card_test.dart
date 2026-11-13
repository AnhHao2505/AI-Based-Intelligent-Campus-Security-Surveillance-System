import 'dart:convert';
import 'dart:io';

import 'package:dio/dio.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:guard_mobile_app/core/network/api_client.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_data.dart';
import 'package:guard_mobile_app/features/map/data/campus_map_repository.dart';
import 'package:guard_mobile_app/features/map/data/campus_route_planner.dart';
import 'package:guard_mobile_app/features/map/models/area_pin_model.dart';
import 'package:guard_mobile_app/features/map/providers/map_provider.dart';
import 'package:guard_mobile_app/features/map/services/route_settings_service.dart';
import 'package:guard_mobile_app/features/map/widgets/area_detail_card.dart';
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

class _DiskCampusRepository extends CampusMapRepository {
  final CampusMapData data;

  _DiskCampusRepository(this.data);

  @override
  Future<CampusMapData> load() async => data;
}

class _FixedRouteSettings extends RouteSettingsService {
  final RouteEtaSettings value;
  int calls = 0;

  _FixedRouteSettings(this.value) : super(Dio());

  @override
  Future<RouteEtaSettings> load() async {
    calls++;
    return value;
  }
}

AreaPinModel _pin({String? building, String? floor, required double lat, required double lon}) => AreaPinModel(
      id: 'area-1',
      name: 'Phòng Lab 4.01',
      areaLevel: 'CONFIDENTIAL',
      building: building,
      floor: floor,
      centerLatitude: lat,
      centerLongitude: lon,
      isActive: true,
    );

Future<void> _pumpCard(WidgetTester tester, AreaPinModel pin, CampusRoute? route) {
  return tester.pumpWidget(MaterialApp(
    home: Scaffold(
      body: SingleChildScrollView(
        child: AreaDetailCard(
          area: pin,
          currentRoute: route,
          isLoadingRoute: false,
          onNavigateTap: () {},
          onCancelRoute: () {},
          onClose: () {},
        ),
      ),
    ),
  ));
}

void main() {
  final campus = _campusOnDisk();
  final graph = campus.graph!;
  final epsilonF4 = graph.nodes.firstWhere((n) => n.building == 'Tòa Epsilon' && n.floorOrder == 4 && n.type == 'NODE');
  final destPin = _pin(building: 'Tòa Epsilon', floor: 'Tầng 4', lat: epsilonF4.latitude, lon: epsilonF4.longitude);

  testWidgets('Thẻ khu: tuyến trên đồ thị → quãng đường, phút (làm tròn lên), chỗ đổi tầng, nút Hủy', (tester) async {
    final o = graph.nodes.firstWhere((n) => n.isOutdoor);
    final route = planCampusRoute(
      graph: graph,
      campus: campus,
      gps: LatLng(o.latitude, o.longitude),
      destinationLatitude: destPin.centerLatitude,
      destinationLongitude: destPin.centerLongitude,
      destinationBuilding: destPin.building,
      destinationFloorOrder: 4,
      eta: const RouteEtaSettings(walkingSpeedMps: 1.0, floorChangeSeconds: 60),
    );
    await _pumpCard(tester, destPin, route);
    final minutes = ((route.distanceMeters / 1.0 + route.floorsClimbed * 60) / 60).ceil();
    expect(find.text('Lộ trình: ${route.formattedDistance} • $minutes phút đi bộ'), findsOneWidget);
    expect(find.text('Lên tầng 4'), findsOneWidget);
    expect(find.text(CampusRoute.directFallbackMessage), findsNothing);
    expect(find.textContaining('Xuất phát'), findsNothing);
    expect(find.textContaining('OSRM'), findsNothing);
    expect(find.text('Hủy dẫn đường'), findsOneWidget);
  });

  testWidgets('Thẻ khu: không có đường trên đồ thị → câu "Chưa có đường đi…, hiển thị hướng thẳng"', (tester) async {
    final route = CampusRoute(
      points: [
        CampusRoutePoint(const LatLng(10.8400, 106.8100), building: 'OUTDOOR', floorOrder: 0),
        CampusRoutePoint(destPin.latLng, building: 'Tòa Epsilon', floorOrder: 4),
      ],
      distanceMeters: 100,
      floorsClimbed: 4,
      floorChanges: const [],
      isDirectFallback: true,
      startMode: CampusRouteStartMode.outdoorGps,
      eta: RouteEtaSettings.defaults,
    );
    await _pumpCard(tester, destPin, route);
    expect(find.text('Chưa có đường đi trên bản đồ, hiển thị hướng thẳng'), findsOneWidget);
    // 100 / 1,45 + 4 × 14 = 124,97 giây → làm tròn lên 3 phút
    expect(find.text('Lộ trình: 100 m • 3 phút đi bộ'), findsOneWidget);
  });

  testWidgets('Thẻ khu: xuất phát theo toà / tầng đang chọn → ghi rõ điểm xuất phát', (tester) async {
    final route = planCampusRoute(
      graph: graph,
      campus: campus,
      gps: null,
      selectedBuilding: 'Tòa Epsilon',
      selectedFloorOrder: 2,
      destinationLatitude: destPin.centerLatitude,
      destinationLongitude: destPin.centerLongitude,
      destinationBuilding: destPin.building,
      destinationFloorOrder: 4,
    );
    await _pumpCard(tester, destPin, route);
    expect(find.text('Xuất phát: cầu thang / thang máy Tòa Epsilon • Tầng 2 (toà / tầng đang chọn)'), findsOneWidget);
    expect(find.text('Lên tầng 4'), findsOneWidget);
  });

  group('MapProvider.calculateRouteTo', () {
    test('đọc route-settings mỗi lần dẫn đường; tuyến đi qua đồ thị theo toà / tầng đang chọn', () async {
      final settings = _FixedRouteSettings(const RouteEtaSettings(walkingSpeedMps: 1.0, floorChangeSeconds: 30));
      final p = MapProvider(ApiClient(), campusRepository: _DiskCampusRepository(campus), routeSettingsService: settings);
      await p.loadCampusMap();
      p.selectBuilding('Tòa Epsilon');
      p.selectFloor(0);
      await p.calculateRouteTo(destPin);
      final r = p.currentRoute!;
      expect(r.isDirectFallback, isFalse);
      expect(r.startMode, CampusRouteStartMode.selectedFloor);
      expect(r.points.last.floorOrder, 4);
      expect(r.eta.walkingSpeedMps, 1.0);
      expect(r.etaMinutes, ((r.distanceMeters + r.floorsClimbed * 30) / 60).ceil());
      await p.calculateRouteTo(destPin);
      expect(settings.calls, 2);
      p.dispose();
    });

    test('không GPS, chưa chọn toà → không có tuyến, errorMessage hướng dẫn chọn toà / tầng', () async {
      final p = MapProvider(ApiClient(),
          campusRepository: _DiskCampusRepository(campus), routeSettingsService: _FixedRouteSettings(RouteEtaSettings.defaults));
      await p.loadCampusMap();
      await p.calculateRouteTo(destPin);
      expect(p.currentRoute, isNull);
      expect(p.errorMessage, contains('Chọn toà và tầng'));
      expect(p.isLoadingRoute, isFalse);
      p.dispose();
    });
  });
}
