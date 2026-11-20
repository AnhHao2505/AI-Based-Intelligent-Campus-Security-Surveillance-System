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
    expect(find.text('$minutes phút đi bộ · ${route.formattedDistance}'), findsOneWidget);
    expect(find.textContaining('Tầng G → Tầng 4'), findsOneWidget);
    expect(find.text('Lên tầng 4'), findsNothing);
    expect(find.text(CampusRoute.directFallbackMessage), findsNothing);
    expect(find.textContaining('Từ '), findsNothing);
    expect(find.textContaining('OSRM'), findsNothing);
    expect(find.text('Hủy'), findsOneWidget);
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
    expect(find.text('3 phút đi bộ · 100 m'), findsOneWidget);
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
    expect(find.text('Từ Tầng 2 (Tòa Epsilon)'), findsOneWidget);
    expect(find.textContaining('Tầng 2 → Tầng 4'), findsOneWidget);
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

    test('Clean Tòa Alpha: danh sách toà và tuyến đường chỉ dùng Tòa Epsilon + lối đi ngoài trời', () async {
      final p = MapProvider(ApiClient(),
          campusRepository: _DiskCampusRepository(campus), routeSettingsService: _FixedRouteSettings(RouteEtaSettings.defaults));
      await p.loadCampusMap();
      expect(p.buildings, ['Tòa Epsilon']);
      expect(p.effectiveBuilding, 'Tòa Epsilon');
      expect(p.visibleCampus!.buildings.map((b) => b.name).toList(), ['Tòa Epsilon']);

      // Ngay cả khi GPS nằm tại vị trí Tòa Alpha cũ (10.8414, 106.81), tuyến tới Tòa Epsilon
      // không bao giờ đi xuyên qua hành lang hay giếng trời của Tòa Alpha.
      p.updateGuardLocation(const LatLng(10.8414, 106.81));
      await p.calculateRouteTo(destPin);
      final route = p.currentRoute!;
      expect(route.isDirectFallback, isFalse);
      expect(route.points.any((pt) => pt.building == 'Tòa Alpha'), isFalse);
      expect(route.points.last.building, 'Tòa Epsilon');

      // focusIncident hoặc selectBuilding với pin/tên Tòa Alpha cũ không được phép bật lại Tòa Alpha trên MapProvider
      final alphaIncident = _pin(building: 'Tòa Alpha', floor: 'Tầng 1', lat: 10.8414, lon: 106.8100);
      p.focusIncident(alphaIncident);
      expect(p.selectedBuilding, 'Tòa Epsilon');
      expect(p.effectiveBuilding, 'Tòa Epsilon');
      p.dispose();
    });

    test('Real-time GPS: không ép tọa độ về mock cố định và tự động tính lại lộ trình khi di chuyển', () async {
      final p = MapProvider(ApiClient(),
          campusRepository: _DiskCampusRepository(campus), routeSettingsService: _FixedRouteSettings(RouteEtaSettings.defaults));
      await p.loadCampusMap();

      // Tọa độ đường N3 / khu vực lân cận không bị ép về campusCenter cố định
      const realGps1 = LatLng(10.840483, 106.811052);
      p.updateGuardLocation(realGps1, heading: 45);
      expect(p.guardLocation, realGps1);
      expect(p.guardHeading, 45);

      await p.calculateRouteTo(destPin);
      final route1 = p.currentRoute!;
      expect(route1.points.first.latLng, realGps1);

      // Bảo vệ di chuyển sang điểm GPS mới → currentRoute tự động cập nhật real-time
      const realGps2 = LatLng(10.840792, 106.811328);
      p.updateGuardLocation(realGps2, heading: 90);
      final route2 = p.currentRoute!;
      expect(route2.points.first.latLng, realGps2);
      expect(route2.distanceMeters, isNot(equals(route1.distanceMeters)));
      p.dispose();
    });

    test('Chọn tầng đứng riêng (guardFloorOrder) khi GPS trong tòa nhà: gắn (x, y) đúng tầng và giữ qua real-time GPS', () async {
      final cleaned = campus.withoutAlpha();
      final f2Nodes = cleaned.graph!.nodes
          .where(
            (n) =>
                n.building == 'Tòa Epsilon' &&
                n.floorOrder == 2 &&
                !n.isVirtual &&
                cleaned.buildingAt(latitude: n.latitude, longitude: n.longitude) == 'Tòa Epsilon',
          )
          .toList();
      final f2Node = f2Nodes.first;
      final f2Node2 = f2Nodes.last;
      final outdoorGps = cleaned.graph!.nodes.firstWhere((n) => n.isOutdoor);

      final p = MapProvider(ApiClient(),
          campusRepository: _DiskCampusRepository(campus), routeSettingsService: _FixedRouteSettings(RouteEtaSettings.defaults));
      await p.loadCampusMap();

      // 1. GPS ngoài sân → không cần hỏi tầng (needsStartFloorPrompt == false)
      p.updateGuardLocation(LatLng(outdoorGps.latitude, outdoorGps.longitude));
      expect(p.needsStartFloorPrompt, isFalse);

      // 2. GPS trong tòa nhà → cần hỏi tầng (needsStartFloorPrompt == true)
      final indoorGps1 = LatLng(f2Node.latitude, f2Node.longitude);
      p.updateGuardLocation(indoorGps1);
      expect(p.needsStartFloorPrompt, isTrue);

      // Đang mở xem Tầng 4 trên bản đồ, nhưng bảo vệ chọn đang đứng ở Tầng 2
      p.selectFloor(4);
      await p.calculateRouteTo(destPin, startFloorOrder: 2);
      final r1 = p.currentRoute!;
      expect(r1.startFloorOrder, 2);
      expect(r1.points.first.floorOrder, 2);

      // Cập nhật GPS real-time khi di chuyển sang vị trí khác trên Tầng 2 → vẫn giữ startFloorOrder = 2 dù đang xem Tầng 4
      final indoorGps2 = LatLng(f2Node2.latitude, f2Node2.longitude);
      p.updateGuardLocation(indoorGps2);
      final r2 = p.currentRoute!;
      expect(r2.startFloorOrder, 2);
      expect(r2.points.first.floorOrder, 2);

      // Chọn "Đang ở ngoài sân (Tầng G)" (forceOutdoorStart) → xuất phát từ outdoorGps (floor 0)
      await p.calculateRouteTo(destPin, startFloorOrder: 0, forceOutdoorStart: true);
      final rOutdoor = p.currentRoute!;
      expect(rOutdoor.startMode, CampusRouteStartMode.outdoorGps);
      expect(rOutdoor.startFloorOrder, 0);

      // Khi GPS đang đứng ở ngoài sân xa tòa nhà nhưng bảo vệ bấm nhầm tầng 2 → không vẽ đường bay ngang sân,
      // xuất phát an toàn từ cầu thang/thang máy Tầng 2 kèm cảnh báo.
      p.updateGuardLocation(LatLng(outdoorGps.latitude, outdoorGps.longitude));
      await p.calculateRouteTo(destPin, startFloorOrder: 2);
      final rPickedFromOutside = p.currentRoute!;
      expect(rPickedFromOutside.startMode, CampusRouteStartMode.selectedFloor);
      expect(rPickedFromOutside.startFloorOrder, 2);
      expect(rPickedFromOutside.points.first.latLng, isNot(LatLng(outdoorGps.latitude, outdoorGps.longitude)));
      expect(rPickedFromOutside.message, contains('ngoài tòa nhà'));
      p.dispose();
    });
  });

  testWidgets('UX dẫn đường gọn: đoạn ngoài trời luôn nổi bật; chạm tiêu đề focus phòng đích & chip đổi tầng tương tác',
      (tester) async {
    final o = campus.withoutAlpha().graph!.nodes.firstWhere((n) => n.isOutdoor);
    final route = planCampusRoute(
      graph: campus.withoutAlpha().graph,
      campus: campus.withoutAlpha(),
      gps: LatLng(o.latitude, o.longitude),
      destinationLatitude: destPin.centerLatitude,
      destinationLongitude: destPin.centerLongitude,
      destinationBuilding: destPin.building,
      destinationFloorOrder: 4,
    );

    // Khi đang xem Tầng 4, chỉ vẽ đoạn thuộc Tầng 4 (current = true); đoạn ngoài sân (Tầng G) là current = false
    final geoAtF4 = routeGeoJson(route, currentFloor: 4);
    final featsAtF4 = (geoAtF4['features'] as List).cast<Map>();
    expect(featsAtF4.first['properties']['current'], isFalse);
    expect(featsAtF4.last['properties']['current'], isTrue);

    // Khi đang xem Tầng G (0), đoạn ngoài sân có current = true
    final geoAtG = routeGeoJson(route, currentFloor: 0);
    final featsAtG = (geoAtG['features'] as List).cast<Map>();
    expect(featsAtG.first['properties']['current'], isTrue);

    CampusFloorChange? tappedStep;
    var tappedDest = false;

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: AreaDetailCard(
          area: destPin,
          currentRoute: route,
          isLoadingRoute: false,
          onNavigateTap: () {},
          onCancelRoute: () {},
          onClose: () {},
          onFocusDestination: () => tappedDest = true,
          onSelectFloorStep: (step) => tappedStep = step,
        ),
      ),
    ));

    // Không hiển thị các nút/text thừa (tọa độ thô, khoảng cách chim bay, nút lặp)
    expect(find.textContaining('10.840'), findsNothing);
    expect(find.textContaining('Cách bạn'), findsNothing);
    expect(find.text('Bám GPS'), findsNothing);
    expect(find.text('Toàn tuyến'), findsNothing);
    expect(find.text('Phòng đích'), findsNothing);
    expect(find.textContaining('Tầng G → Tầng 4'), findsOneWidget);

    await tester.tap(find.text(destPin.name));
    await tester.tap(find.textContaining('Tầng G → Tầng 4'));

    expect(tappedDest, isTrue);
    expect(tappedStep, isNotNull);
    expect(tappedStep!.toFloor, 4);
  });
}
